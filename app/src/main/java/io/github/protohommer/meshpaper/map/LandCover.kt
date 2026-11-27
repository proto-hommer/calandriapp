package io.github.protohommer.meshpaper.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Qué hay sobre el suelo: ESA WorldCover 2021 (10 m), llevado a la misma grilla que el relieve
 * ([Dem]: Web Mercator, zoom 12, ~33 m por píxel en Córdoba). Cada píxel es la clase que más aparece
 * ahí: 10 árboles, 20 arbustos, 30 pastizal, 40 cultivo, 50 construido, 60 suelo desnudo, 70 nieve,
 * 80 agua, 90 humedal, 95 manglar, 100 musgo; 0 = sin dato.
 *
 * Los tiles (PNG en escala de grises, el valor es la clase) los arma `tools/wc_tiles.py` a partir de
 * los originales de ESA, y se bajan y guardan igual que el relieve: `files/landcover/12/x/y.png`.
 *
 * Licencia de los datos: CC BY 4.0. Atribución: "© ESA WorldCover project 2021 / Contains modified
 * Copernicus Sentinel data (2021) processed by ESA WorldCover consortium".
 *
 * Para la radio, cada clase es una "altura de obstáculos" R y un tipo:
 *  - En los extremos (el nodo y el de mano): si la antena queda debajo de R, pérdida de terminal de
 *    ITU-R P.2108 §3.1 (difracción sobre el borde del obstáculo, calle de 27 m), y la propagación se
 *    calcula como si la antena estuviera a la altura R (así lo pide el método).
 *  - En el medio del camino: R se suma al relieve como obstáculo, a la mitad, porque el SRTM ya mide
 *    parte de las copas y los techos.
 * Los valores son de partida: se ajustan comparando con las mediciones reales (ModelCheck).
 */
object LandCover {
    const val ZOOM = Dem.ZOOM
    const val SIZE = Dem.SIZE
    const val ATTRIBUTION = "Cobertura del suelo: © ESA WorldCover 2021 (CC BY 4.0), datos Copernicus Sentinel modificados"
    /** Dónde están publicados los tiles ({x}, {y}). Vacío = solo los que ya estén en el teléfono. */
    const val URL_TEMPLATE = "https://proto-hommer.github.io/calandria-datos/landcover/12/%d/%d.png"
    /** Fracción de la altura de obstáculos que se suma al relieve en el medio del camino. */
    const val MID_PATH_FACTOR = 0.5
    private const val STREET_WIDTH_M = 27.0

    /** Altura de los obstáculos de cada clase (m). 0 = abierto. */
    fun clutterHeightM(cls: Int): Double = when (cls) {
        10 -> 10.0  // monte: algarrobos, espinillos, pinares (en las sierras, 6 a 15 m)
        20 -> 3.0   // arbustos, fachinal
        50 -> 8.0   // construido: en Córdoba casi todo casas de 1 o 2 plantas
        95 -> 8.0
        90 -> 1.0
        40 -> 0.5   // cultivo: maíz y soja, según la época
        else -> 0.0 // pastizal, suelo desnudo, agua, nieve, musgo, sin dato
    }

    fun className(cls: Int): String = when (cls) {
        10 -> "monte"; 20 -> "arbustos"; 30 -> "pastizal"; 40 -> "cultivo"; 50 -> "construido"
        60 -> "suelo desnudo"; 70 -> "nieve"; 80 -> "agua"; 90 -> "humedal"; 95 -> "manglar"; 100 -> "musgo"
        else -> "sin dato"
    }

    /** Pérdida (dB) por un filo de cuchillo, J(v) de ITU-R P.526 (igual que en Profile). */
    private fun knifeEdgeDb(v: Double): Double =
        if (v <= -0.78) 0.0 else 6.9 + 20 * log10(sqrt((v - 0.1) * (v - 0.1) + 1) + v - 0.1)

    /**
     * Un extremo del enlace metido en obstáculos de la clase [cls], con la antena a [heightM].
     * Devuelve (altura a usar para la propagación, pérdida extra en dB).
     */
    fun terminal(cls: Int, heightM: Double, freqMHz: Double): Pair<Double, Double> {
        val r = clutterHeightM(cls)
        if (r < 2.0 || heightM >= r) return heightM to 0.0 // abierto, o la antena ya está por encima
        val hdif = r - heightM
        val thetaDeg = Math.toDegrees(atan(hdif / STREET_WIDTH_M))
        val knu = 0.342 * sqrt(freqMHz / 1000)
        val nu = knu * sqrt(hdif * thetaDeg)
        return r to max(0.0, knifeEdgeDb(nu) - 6.03)
    }

    // ---------- Tiles (como Dem) ----------

    private val cache = LruCache<Long, ByteArray>(64)

    fun dir(ctx: Context) = File(ctx.filesDir, "landcover/$ZOOM")
    private fun file(ctx: Context, x: Int, y: Int) = File(dir(ctx), "$x/$y.png")

    private fun decode(bytes: ByteArray): ByteArray? {
        val opts = BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width != SIZE || bmp.height != SIZE) { bmp.recycle(); return null }
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        bmp.recycle()
        return ByteArray(px.size) { (px[it] and 0xff).toByte() } // gris: R = G = B = la clase
    }

    private fun fetch(x: Int, y: Int): ByteArray? = runCatching {
        val c = URL(URL_TEMPLATE.format(x, y)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "CalandriApp-Android/1.0") // genérico, sin datos del usuario
        try { if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }.getOrNull()

    /** Tiles que ya se pidieron y no existen (fuera de la zona publicada): no volver a pedirlos. */
    private val absent = java.util.Collections.synchronizedSet(HashSet<Long>())

    fun has(ctx: Context, x: Int, y: Int) = file(ctx, x, y).isFile

    fun download(ctx: Context, x: Int, y: Int): Boolean {
        if (has(ctx, x, y)) return true
        val key = (x.toLong() shl 32) or y.toLong()
        if (key in absent) return false
        val bytes = fetch(x, y) ?: run { absent += key; return false }
        if (decode(bytes) == null) return false
        val f = file(ctx, x, y)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeBytes(bytes)
        return tmp.renameTo(f)
    }

    /** Clases de un tile entero (256×256, fila por fila), o null si no está y no se pudo bajar. */
    fun tileData(ctx: Context, x: Int, y: Int, network: Boolean): ByteArray? {
        val n = 1 shl ZOOM
        if (x !in 0 until n || y !in 0 until n) return null
        val key = (x.toLong() shl 32) or y.toLong()
        cache.get(key)?.let { return it }
        val f = file(ctx, x, y)
        if (!f.isFile && !(network && download(ctx, x, y))) return null
        val data = runCatching { decode(f.readBytes()) }.getOrNull() ?: return null
        cache.put(key, data)
        return data
    }

    /** Clase en un punto (la del píxel), o 0 si no hay dato. Bloquea: fuera del hilo principal. */
    fun classAt(ctx: Context, lat: Double, lon: Double, network: Boolean): Int {
        val n = 1 shl ZOOM
        val fx = (lon + 180.0) / 360.0 * n
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val fy = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
        val tx = floor(fx).toInt(); val ty = floor(fy).toInt()
        val t = tileData(ctx, tx, ty, network) ?: return 0
        val px = ((fx - tx) * SIZE).toInt().coerceIn(0, SIZE - 1)
        val py = ((fy - ty) * SIZE).toInt().coerceIn(0, SIZE - 1)
        return t[py * SIZE + px].toInt() and 0xff
    }

    data class Stored(val tiles: Int, val bytes: Long)

    fun stored(ctx: Context): Stored {
        val files = dir(ctx).walkTopDown().filter { it.isFile && it.name.endsWith(".png") }.toList()
        return Stored(files.size, files.sumOf { it.length() })
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "landcover").deleteRecursively()
        cache.evictAll(); absent.clear()
    }
}
