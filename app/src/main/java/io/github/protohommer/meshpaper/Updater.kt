package io.github.protohommer.meshpaper

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualizaciones desde GitHub Releases, sin tienda.
 *
 * 1. Pregunta a la API de GitHub cuál es la última versión publicada (una consulta chica, sin
 *    datos del usuario ni del nodo).
 * 2. Si es más nueva, baja el APK al caché de la app.
 * 3. Antes de instalar comprueba dos cosas: el SHA-256 que publica GitHub para ese archivo y
 *    que esté firmado con el MISMO certificado que la app instalada. Si algo no coincide, se
 *    borra y no se instala.
 * 4. Abre el instalador de Android, que siempre pide confirmar (Android no deja instalar solo).
 */
object Updater {
    private const val TAG = "Updater"
    private const val LATEST = "https://api.github.com/repos/proto-hommer/calandriapp/releases/latest"
    private const val PREFS = "updates"
    private const val SNOOZE_MS = 24 * 3600 * 1000L

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String?)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val r: Release) : State
        data class Downloading(val r: Release, val progress: Float) : State
        /** Bajado y verificado, listo para instalar. */
        data class Ready(val r: Release, val file: File) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var checkedThisRun = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoCheck(ctx: Context): Boolean = prefs(ctx).getBoolean("auto", true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Al abrir la app: una vez por arranque, si está activado y hay internet. */
    suspend fun checkOnLaunch(ctx: Context) {
        if (checkedThisRun || !autoCheck(ctx) || !online(ctx)) return
        checkedThisRun = true
        check(ctx, manual = false)
    }

    /** [manual] = el botón "Buscar ahora": ignora el "más tarde" y avisa también si está al día. */
    suspend fun check(ctx: Context, manual: Boolean) {
        if (_state.value is State.Downloading) return
        if (manual) _state.value = State.Checking
        try {
            val r = withContext(Dispatchers.IO) { fetchLatest() }
            when {
                r == null -> _state.value = if (manual) State.Error("No encontré el APK en la última versión.") else State.Idle
                !isNewer(r.version, installedVersion(ctx)) -> _state.value = if (manual) State.UpToDate else State.Idle
                !manual && snoozed(ctx, r.version) -> _state.value = State.Idle
                else -> _state.value = State.Available(r)
            }
        } catch (e: Exception) {
            Log.e(TAG, "check", e)
            _state.value = if (manual) State.Error("No pude consultar GitHub: ${e.message ?: e.javaClass.simpleName}") else State.Idle
        }
    }

    /** "Más tarde": no vuelve a preguntar por esta versión hasta mañana. */
    fun snooze(ctx: Context, version: String) {
        prefs(ctx).edit().putString("skipVersion", version).putLong("skipUntil", System.currentTimeMillis() + SNOOZE_MS).apply()
        _state.value = State.Idle
    }

    fun dismiss() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private fun snoozed(ctx: Context, version: String): Boolean {
        val p = prefs(ctx)
        return p.getString("skipVersion", null) == version && System.currentTimeMillis() < p.getLong("skipUntil", 0)
    }

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "CalandriApp-Android")
    }

    private fun fetchLatest(): Release? {
        val c = open(LATEST).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub respondió ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val version = o.optString("tag_name").removePrefix("v").trim()
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (!a.optString("name").endsWith(".apk")) continue
                val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                return Release(version, cleanNotes(o.optString("body")), a.getString("browser_download_url"), a.optLong("size"), digest)
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Las notas en castellano, sin la tabla de verificación ni el resumen en inglés, en texto plano. */
    private fun cleanNotes(md: String): String = md
        .substringBefore("### Verificación").substringBefore("\n---")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")   // [texto](link) → texto
        .replace("**", "").replace("*", "").replace("### ", "")
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** "1.3.10" > "1.3.9". */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "CalandriApp.apk")

    /** Baja el APK, lo verifica (SHA-256 y firma) y deja el estado en [State.Ready]. */
    suspend fun download(ctx: Context, r: Release) {
        _state.value = State.Downloading(r, 0f)
        val file = apkFile(ctx)
        try {
            withContext(Dispatchers.IO) {
                file.delete()
                val md = MessageDigest.getInstance("SHA-256")
                val c = open(r.apkUrl) // GitHub redirige a su almacenamiento (https → https, se sigue solo)
                try {
                    if (c.responseCode != 200) throw IllegalStateException("la descarga respondió ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                    var done = 0L
                    var lastShown = 0f
                    c.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); done += n
                                val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                if (p - lastShown >= 0.02f) { lastShown = p; _state.value = State.Downloading(r, p) }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256 != null && !sha.equals(r.sha256, ignoreCase = true)) {
                    throw SecurityException("el archivo no coincide con el SHA-256 publicado")
                }
                verifyApk(ctx, file)
            }
            _state.value = State.Ready(r, file)
        } catch (e: Exception) {
            Log.e(TAG, "download", e)
            file.delete()
            _state.value = State.Error(
                if (e is SecurityException) "No se instala: ${e.message}." else "No se pudo bajar: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /** Mismo paquete, versión más nueva y firmado con el mismo certificado que la app instalada. */
    private fun verifyApk(ctx: Context, file: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: throw SecurityException("el APK no se puede leer")
        if (apk.packageName != ctx.packageName) throw SecurityException("el APK es de otra app")
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        val mine = certs(installed)
        val theirs = certs(apk)
        if (mine.isEmpty() || theirs.isEmpty() || theirs != mine) throw SecurityException("el APK no está firmado con el mismo certificado")
        if (versionCode(apk) <= versionCode(installed)) throw SecurityException("el APK no es más nuevo que el instalado")
    }

    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") p.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    /** Android 8+ pide habilitar "Instalar apps desconocidas" para esta app (una sola vez). */
    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Abre el instalador de Android con el APK ya verificado. */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
