package io.github.protohommer.meshpaper.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Modelo de elevación (DEM) para el perfil entre nodos.
 *
 * Fuente: "Terrain Tiles" de AWS Open Data (Mapzen / Tilezen joerd), formato "terrarium":
 * PNG de 256×256 en Web Mercator, `https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png`,
 * con la altura codificada como `(R·256 + G + B/256) − 32768` metros. Libre y sin cuenta.
 * En Argentina viene del SRTM (30 m de grilla, calidad nominal de 90 m).
 *
 * Usamos zoom 12: un píxel son unos 32 m a la latitud de Córdoba, la resolución del SRTM.
 * Los tiles quedan guardados en `files/dem/12/x/y.png` y sirven sin señal.
 * Atribución pedida por el USGS: "SRTM data courtesy of the U.S. Geological Survey".
 */
object Dem {
    const val ZOOM = 12
    const val MAX_DOWNLOAD_TILES = 2_500
    const val ATTRIBUTION = "Relieve: SRTM, cortesía del U.S. Geological Survey (Terrain Tiles, AWS Open Data)"
    private const val URL_TEMPLATE = "https://elevation-tiles-prod.s3.amazonaws.com/terrarium/%d/%d/%d.png"
    private const val SIZE = 256

    /** Tiles ya decodificados, en metros enteros (128 KB cada uno). */
    private val cache = LruCache<Long, ShortArray>(48)

    fun dir(ctx: Context) = File(ctx.filesDir, "dem/$ZOOM")

    private fun file(ctx: Context, x: Int, y: Int) = File(dir(ctx), "$x/$y.png")

    // ---------- Web Mercator ----------

    private fun tileXf(lon: Double): Double = (lon + 180.0) / 360.0 * (1 shl ZOOM)
    private fun tileYf(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl ZOOM)
    }

    /** Latitud del borde norte de la fila de tiles [y] (para estimar tamaños). */
    fun tileLat(y: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl ZOOM)))))

    /** Los tiles que cubren un rectángulo. */
    fun tilesFor(north: Double, south: Double, west: Double, east: Double): List<Pair<Int, Int>> {
        val n = 1 shl ZOOM
        val x0 = floor(tileXf(west)).toInt().coerceIn(0, n - 1)
        val x1 = floor(tileXf(east)).toInt().coerceIn(0, n - 1)
        val y0 = floor(tileYf(north)).toInt().coerceIn(0, n - 1)
        val y1 = floor(tileYf(south)).toInt().coerceIn(0, n - 1)
        return buildList { for (x in x0..x1) for (y in y0..y1) add(x to y) }
    }

    // ---------- Tiles ----------

    private fun decode(bytes: ByteArray): ShortArray? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bmp.width != SIZE || bmp.height != SIZE) { bmp.recycle(); return null }
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        bmp.recycle()
        return ShortArray(px.size) { i ->
            val c = px[i]
            val m = ((c shr 16) and 0xff) * 256 + ((c shr 8) and 0xff) + (c and 0xff) / 256.0 - 32768
            m.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun fetch(x: Int, y: Int): ByteArray? = runCatching {
        val c = URL(URL_TEMPLATE.format(ZOOM, x, y)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        // Genérico, sin datos del usuario (igual que para los mapas).
        c.setRequestProperty("User-Agent", "CalandriApp-Android/1.0")
        try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** ¿Está guardado en el teléfono? */
    fun has(ctx: Context, x: Int, y: Int) = file(ctx, x, y).isFile

    /** Baja un tile y lo guarda. Devuelve false si falló. */
    fun download(ctx: Context, x: Int, y: Int): Boolean {
        if (has(ctx, x, y)) return true
        val bytes = fetch(x, y) ?: return false
        if (decode(bytes) == null) return false
        val f = file(ctx, x, y)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeBytes(bytes)
        return tmp.renameTo(f)
    }

    private fun tile(ctx: Context, x: Int, y: Int, network: Boolean): ShortArray? {
        val key = (x.toLong() shl 32) or y.toLong()
        cache.get(key)?.let { return it }
        val f = file(ctx, x, y)
        if (!f.isFile && !(network && download(ctx, x, y))) return null
        val data = runCatching { decode(f.readBytes()) }.getOrNull() ?: return null
        cache.put(key, data)
        return data
    }

    // ---------- Alturas ----------

    /**
     * Altura del terreno (m sobre el nivel del mar), interpolada entre los 4 píxeles vecinos.
     * null si no está el tile (y no se pudo bajar). Bloquea: llamar fuera del hilo principal.
     */
    fun elevation(ctx: Context, lat: Double, lon: Double, network: Boolean): Double? {
        val fx = tileXf(lon) * SIZE - 0.5
        val fy = tileYf(lat) * SIZE - 0.5
        fun at(px: Int, py: Int): Double? {
            val max = SIZE * (1 shl ZOOM)
            val gx = px.coerceIn(0, max - 1)
            val gy = py.coerceIn(0, max - 1)
            val t = tile(ctx, gx / SIZE, gy / SIZE, network) ?: return null
            return t[(gy % SIZE) * SIZE + gx % SIZE].toDouble()
        }
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val a = at(x0, y0) ?: return null
        val b = at(x0 + 1, y0) ?: return null
        val c = at(x0, y0 + 1) ?: return null
        val d = at(x0 + 1, y0 + 1) ?: return null
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    /** Alturas de una lista de puntos, bajando lo que falte si se puede. null si falta alguno. */
    suspend fun elevations(ctx: Context, pts: List<Pair<Double, Double>>, network: Boolean): List<Double>? =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Double>(pts.size)
            for ((lat, lon) in pts) out += elevation(ctx, lat, lon, network) ?: return@withContext null
            out
        }

    // ---------- Lo guardado ----------

    data class Stored(val tiles: Int, val bytes: Long)

    fun stored(ctx: Context): Stored {
        val files = dir(ctx).walkTopDown().filter { it.isFile && it.name.endsWith(".png") }.toList()
        return Stored(files.size, files.sumOf { it.length() })
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "dem").deleteRecursively()
        cache.evictAll()
    }
}
