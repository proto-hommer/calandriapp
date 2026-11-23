package io.github.protohommer.meshpaper.map

import android.content.Context
import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/**
 * Rutas por calles y senderos, calculadas en el teléfono con el motor de BRouter (MIT,
 * https://github.com/abrensch/brouter, jar "ro" de la versión 1.7.10 en app/libs).
 *
 * Datos: archivos `.rd5` de brouter.de (carpeta segments4), uno por cuadro de 5°×5° (Córdoba es
 * W65_S35, unos 11 MB), sacados de OpenStreetMap (ODbL). Se bajan una vez desde Ajustes o desde
 * el panel de ruta y después todo anda sin señal. Al bajarlos el servidor solo se entera del
 * cuadro de 5°, no de dónde está uno.
 *
 * Uso del motor tomado de BRouterWorker (brouter-routing-app): RoutingContext con
 * `localFunction` = ruta del perfil .brf (lookups.dat al lado), RoutingEngine(null, null,
 * segmentDir, waypoints, rc), doRun(ms), getErrorMessage() / getFoundTrack().
 */
object Routing {
    private const val TAG = "Routing"
    private const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"
    const val ATTRIBUTION = "Rutas: BRouter con datos de © OpenStreetMap (ODbL)"

    enum class Mode(val label: String, val profile: String, val kmh: Double) {
        WALK("A PIE", "hiking-mountain", 4.5),
        BIKE("BICI", "trekking", 15.0),
        CAR("AUTO", "car-vario", 45.0),
    }

    data class Route(
        val points: List<Pair<Double, Double>>, // lat, lon
        val distanceM: Int,
        val seconds: Int,
        val ascendM: Int,
        val mode: Mode,
    )

    class MissingData(val tiles: List<String>) : Exception("faltan datos de rutas: ${tiles.joinToString()}")

    private val engineLock = Mutex()

    private fun base(ctx: Context) = File(ctx.filesDir, "brouter")
    fun segmentDir(ctx: Context) = File(base(ctx), "segments4").apply { mkdirs() }
    private fun profileDir(ctx: Context) = File(base(ctx), "profiles2")

    /** Nombre del cuadro de 5° de BRouter que contiene el punto: "W65_S35" para Córdoba. */
    fun tileName(lat: Double, lon: Double): String {
        val lo = (floor(lon / 5.0) * 5).toInt()
        val la = (floor(lat / 5.0) * 5).toInt()
        return (if (lo < 0) "W${-lo}" else "E$lo") + "_" + (if (la < 0) "S${-la}" else "N$la")
    }

    /** Los cuadros que cubre el rectángulo entre dos puntos (la ruta casi siempre cae adentro). */
    fun tilesBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): List<String> {
        val out = linkedSetOf<String>()
        var la = floor(minOf(aLat, bLat) / 5.0) * 5
        while (la <= maxOf(aLat, bLat)) {
            var lo = floor(minOf(aLon, bLon) / 5.0) * 5
            while (lo <= maxOf(aLon, bLon)) { out += tileName(la + 0.1, lo + 0.1); lo += 5 }
            la += 5
        }
        return out.toList()
    }

    fun file(ctx: Context, tile: String) = File(segmentDir(ctx), "$tile.rd5")
    fun has(ctx: Context, tile: String) = file(ctx, tile).let { it.isFile && it.length() > 0 }
    fun missing(ctx: Context, tiles: List<String>) = tiles.filterNot { has(ctx, it) }

    data class Stored(val tile: String, val bytes: Long)
    fun stored(ctx: Context): List<Stored> =
        segmentDir(ctx).listFiles { f -> f.name.endsWith(".rd5") }.orEmpty()
            .map { Stored(it.name.removeSuffix(".rd5"), it.length()) }.sortedBy { it.tile }

    fun delete(ctx: Context, tile: String) { file(ctx, tile).delete() }

    /** Tamaño en el servidor (para avisar antes de bajar), o null si no hay o no se pudo consultar. */
    suspend fun remoteSize(tile: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(SEGMENTS_URL + "$tile.rd5").apply { requestMethod = "HEAD" }
            try { if (c.responseCode == 200) c.contentLengthLong.takeIf { it > 0 } else null } finally { c.disconnect() }
        }.getOrNull()
    }

    /** Baja un cuadro. [progress] de 0 a 1. Se guarda con otro nombre y se renombra al terminar. */
    suspend fun download(ctx: Context, tile: String, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dst = file(ctx, tile)
        val tmp = File(dst.path + ".part")
        val c = open(SEGMENTS_URL + "$tile.rd5")
        try {
            when (c.responseCode) {
                200 -> Unit
                404 -> throw IllegalStateException("no hay datos de rutas para $tile")
                else -> throw IllegalStateException("el servidor de rutas respondió ${c.responseCode}")
            }
            val total = c.contentLengthLong
            var done = 0L
            var last = 0f
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val p = done.toFloat() / total
                            if (p - last >= 0.01f) { last = p; progress(p) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("la descarga quedó incompleta")
            if (!tmp.renameTo(dst)) throw IllegalStateException("no pude guardar $tile")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "CalandriApp-Android")
    }

    /** Copia los perfiles de assets a archivos (el motor los lee de disco). Se rehace al actualizar la app. */
    private fun ensureProfiles(ctx: Context): File {
        val dir = profileDir(ctx)
        val stamp = File(dir, ".version")
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString() }.getOrDefault("0")
        if (stamp.isFile && stamp.readText() == version) return dir
        dir.mkdirs()
        for (name in ctx.assets.list("brouter/profiles2").orEmpty()) {
            ctx.assets.open("brouter/profiles2/$name").use { i -> File(dir, name).outputStream().use { o -> i.copyTo(o) } }
        }
        stamp.writeText(version)
        return dir
    }

    private fun node(name: String, lat: Double, lon: Double) = OsmNodeNamed().apply {
        this.name = name
        // Misma codificación que RoutingParamCollector.readPositions.
        ilon = ((lon + 180.0) * 1_000_000.0 + 0.5).toInt()
        ilat = ((lat + 90.0) * 1_000_000.0 + 0.5).toInt()
    }

    /**
     * Calcula la ruta. Tira [MissingData] si falta algún cuadro, o una excepción con el mensaje
     * del motor (p. ej. un punto lejos de cualquier calle o sendero).
     */
    suspend fun route(ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: Mode): Route =
        engineLock.withLock {
            withContext(Dispatchers.Default) {
                val need = missing(ctx, tilesBetween(fromLat, fromLon, toLat, toLon))
                if (need.isNotEmpty()) throw MissingData(need)
                val rc = RoutingContext()
                rc.localFunction = File(ensureProfiles(ctx), "${mode.profile}.brf").path
                val wps = listOf(node("from", fromLat, fromLon), node("to", toLat, toLon))
                val engine = RoutingEngine(null, null, segmentDir(ctx), wps, rc)
                engine.quite = true
                val t0 = System.currentTimeMillis()
                engine.doRun(60_000)
                engine.getErrorMessage()?.let { throw IllegalStateException(friendly(it)) }
                val track = engine.getFoundTrack() ?: throw IllegalStateException("no encontré un camino")
                val pts = track.nodes.map { (it.getILat() / 1_000_000.0 - 90.0) to (it.getILon() / 1_000_000.0 - 180.0) }
                val secs = track.getTotalSeconds().takeIf { it > 0 } ?: (track.distance / (mode.kmh / 3.6)).toInt()
                Log.i(TAG, "ruta ${mode.name}: ${track.distance} m, ${pts.size} pts en ${System.currentTimeMillis() - t0} ms")
                Route(pts, track.distance, secs, track.ascend, mode)
            }
        }

    private fun friendly(msg: String): String = when {
        "not mapped" in msg || "position not mapped" in msg ->
            "uno de los puntos está lejos de cualquier calle o sendero"
        "no track found" in msg.lowercase() || "island" in msg ->
            "no hay un camino que una los dos puntos con este medio"
        "operation killed" in msg || "timeout" in msg.lowercase() || "memory limit" in msg ->
            "el cálculo tardó demasiado: probá con un destino más cerca"
        else -> msg
    }

    fun formatTime(seconds: Int): String {
        val m = (seconds + 30) / 60
        return if (m < 60) "$m min" else "%d h %02d".format(m / 60, m % 60)
    }
}
