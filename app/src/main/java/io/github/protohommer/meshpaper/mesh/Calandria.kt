package io.github.protohommer.meshpaper.mesh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Nodos Calandria (firmware propio, repo proto-hommer/calandria-mk1-epaper).
 *
 * La tabla oficial de placas no tiene la nuestra (el nodo dice PRIVATE_HW), así que el firmware se
 * anuncia en un puerto privado (CalandriaBeacon.h): PRIVATE_APP + 67 = 323, por el canal primario,
 * en broadcast, a los 5 min de arrancar y cada 6 h. Texto UTF-8 separado por ';':
 *
 *   CALANDRIA/1;model=Calandria MK1 epaper;fw=2.8.3.de48e4f;env=calandria-mk1-epaper
 *
 * Un pedido en ese puerto con want_response se contesta directo (como mucho uno por minuto).
 * fw = APP_VERSION del firmware: "mayor.menor.build.<hash corto de git>".
 */
object Calandria {
    const val PORT = 323

    /**
     * [ver] = versión propia del Calandria ("0.6.0"), desde la 0.6.0; vacía en firmwares anteriores.
     * [fw] = la de Meshtastic sobre la que está hecho, con el commit ("2.8.3.edc1ef7").
     */
    data class Info(val model: String, val fw: String, val env: String, val seen: Long, val ver: String = "") {
        /** El hash de git del build ("de48e4f"), si el fw lo trae. */
        val commit: String? get() = fw.split('.').getOrNull(3)?.takeIf { h -> h.length >= 7 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
        /** "2.8.3" */
        val base: String get() = fw.split('.').take(3).joinToString(".")
    }

    fun parse(payload: ByteArray, now: Long): Info? {
        val s = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val parts = s.split(';')
        if (parts.isEmpty() || !parts[0].startsWith("CALANDRIA/")) return null
        val kv = parts.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
        val model = kv["model"]?.takeIf { it.isNotBlank() } ?: return null
        val ver = kv["ver"].orEmpty().take(20).takeIf { v -> v.all { it.isDigit() || it == '.' } }.orEmpty()
        return Info(model.take(40), kv["fw"].orEmpty().take(40), kv["env"].orEmpty().take(40), now, ver)
    }

    fun encode(i: Info) = listOf(i.model, i.fw, i.env, i.seen.toString(), i.ver).joinToString("\u001f")
    fun decode(s: String): Info? = s.split('\u001f').takeIf { it.size == 4 || it.size == 5 }
        ?.let { Info(it[0], it[1], it[2], it[3].toLongOrNull() ?: 0, it.getOrElse(4) { "" }) }

    /** Nombre corto de un release: "calandria-0.6.0" → "0.6.0"; los viejos "v2.8.3-ws154.5" → "ws154.5". */
    fun shortTag(tag: String): String =
        if (tag.startsWith("calandria-")) tag.removePrefix("calandria-") else tag.substringAfterLast('-')

    // ---------- Última versión publicada del firmware ----------

    data class Release(val tag: String, val commit: String, val url: String)

    private val _latest = MutableStateFlow<Release?>(null)
    /** La última versión publicada en GitHub, si ya se consultó (una vez por arranque). */
    val latest: StateFlow<Release?> = _latest
    private var checked = false

    private const val REPO = "https://api.github.com/repos/proto-hommer/calandria-mk1-epaper"

    /** Consulta GitHub una vez por arranque (sin datos del usuario ni del nodo). */
    suspend fun checkLatest() {
        if (checked) return
        checked = true
        try {
            _latest.value = withContext(Dispatchers.IO) {
                try {
                    val rel = JSONObject(get("$REPO/releases/latest"))
                    val tag = rel.optString("tag_name").takeIf { it.isNotEmpty() } ?: return@withContext null
                    val sha = JSONObject(get("$REPO/commits/$tag")).optString("sha")
                    if (sha.length < 7) null else Release(tag, sha, rel.optString("html_url"))
                } catch (e: Exception) {
                    // Límite de la API (datos móviles con IP compartida): las páginas web no lo tienen.
                    if (!io.github.protohommer.meshpaper.GitHubWeb.isRateLimit(e)) throw e
                    val repo = "proto-hommer/calandria-mk1-epaper"
                    val tag = io.github.protohommer.meshpaper.GitHubWeb.latestTag(repo) ?: return@withContext null
                    val sha = io.github.protohommer.meshpaper.GitHubWeb.tagCommit(repo, tag) ?: return@withContext null
                    Release(tag, sha, "https://github.com/$repo/releases/tag/$tag")
                }
            }
        } catch (e: Exception) {
            checked = false // otro intento la próxima vez
            Log.w("Calandria", "no pude consultar la última versión: ${e.message}")
        }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "CalandriApp-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub respondió ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /**
     * null = no se puede saber (build sin hash o sin consulta).
     * Con versión propia de los dos lados ("0.7.0" contra "calandria-0.6.0") manda el número: un build
     * más nuevo que el último publicado (uno de prueba, grabado antes del release) está al día.
     * Sin versión, el commit: los firmwares de antes de la 0.6.0.
     */
    fun upToDate(i: Info, r: Release?): Boolean? {
        r ?: return null
        compareVersions(i.ver, r)?.let { return it >= 0 }
        val c = i.commit ?: return null
        return r.commit.startsWith(c, ignoreCase = true)
    }

    /** El nodo tiene una versión posterior a la última publicada (un build de prueba). */
    fun newerThanLatest(i: Info, r: Release?): Boolean = r != null && (compareVersions(i.ver, r) ?: 0) > 0

    /** ver del nodo contra el tag del release: <0, 0, >0; null si alguno no es "calandria-x.y.z". */
    private fun compareVersions(ver: String, r: Release): Int? {
        if (!r.tag.startsWith("calandria-")) return null
        val a = numbers(ver) ?: return null
        val b = numbers(r.tag.removePrefix("calandria-")) ?: return null
        for (k in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(k) { 0 }.compareTo(b.getOrElse(k) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun numbers(v: String): List<Int>? =
        v.takeIf { it.isNotEmpty() }?.split('.')?.map { it.toIntOrNull() ?: return null }
}
