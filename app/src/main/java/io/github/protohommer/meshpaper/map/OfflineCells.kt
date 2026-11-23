package io.github.protohommer.meshpaper.map

import android.content.Context
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Cuadrantes para bajar mapas y relieve. Es una grilla fija: cada cuadrante es un tile de
 * Web Mercator a zoom [Z] (≈17 km de lado a la latitud de Córdoba), así lo descargado queda
 * marcado igual aunque se mueva el mapa. Cada cuadrante tiene justo 2×2 tiles de relieve.
 *
 * Qué se baja de cada uno:
 *  - relieve (DEM): siempre;
 *  - mapa: solo si la fuente permite descargas (servidor propio). OpenStreetMap no lo permite.
 * El relieve se mira en los archivos; el mapa queda anotado al terminar (fuente y zoom).
 */
object OfflineCells {
    const val Z = 11
    /** El mapa se baja desde este zoom (vista general) hasta el que se elija. */
    const val MAP_MIN_ZOOM = 8
    private const val PREFS = "offline"

    enum class Status { DONE, PARTIAL, NONE }

    data class Cell(val x: Int, val y: Int) {
        val north get() = lat(y)
        val south get() = lat(y + 1)
        val west get() = lon(x)
        val east get() = lon(x + 1)
        val key get() = "$x/$y"
    }

    private val n = 1 shl Z

    fun lon(x: Int): Double = x.toDouble() / n * 360.0 - 180.0
    fun lat(y: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / n))))

    fun at(lat: Double, lon: Double): Cell {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val y = floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return Cell(x, y)
    }

    /** Los cuadrantes por donde pasa una línea (la ruta), en orden y sin repetir. */
    fun along(points: List<Pair<Double, Double>>): List<Cell> {
        val out = linkedSetOf<Cell>()
        for (i in points.indices) {
            out += at(points[i].first, points[i].second)
            // Entre puntos lejanos (tramos rectos largos) se miran puntos intermedios.
            if (i + 1 < points.size) {
                val (a, b) = points[i] to points[i + 1]
                val steps = (maxOf(kotlin.math.abs(a.first - b.first), kotlin.math.abs(a.second - b.second)) / 0.05).toInt()
                for (k in 1 until steps) {
                    val t = k.toDouble() / steps
                    out += at(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
                }
            }
        }
        return out.toList()
    }

    /** Los 4 tiles de relieve (zoom 12) de un cuadrante. */
    fun demTiles(c: Cell): List<Pair<Int, Int>> {
        val f = 1 shl (Dem.ZOOM - Z)
        return buildList { for (dx in 0 until f) for (dy in 0 until f) add(c.x * f + dx to c.y * f + dy) }
    }

    /** Tiles de mapa de un cuadrante, de [MAP_MIN_ZOOM] a [maxZoom] (para estimar). */
    fun mapTileCount(maxZoom: Int): Int {
        var total = 0
        for (z in MAP_MIN_ZOOM..maxZoom) total += if (z <= Z) 1 else (1 shl (z - Z)) * (1 shl (z - Z))
        return total
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Anota que el mapa de [c] quedó bajado de [source] hasta [maxZoom]. */
    fun markMap(ctx: Context, c: Cell, source: String, maxZoom: Int) {
        prefs(ctx).edit().putString(c.key, "$source|$maxZoom").apply()
    }

    private fun mapDone(ctx: Context, c: Cell, source: String, maxZoom: Int): Boolean {
        val v = prefs(ctx).getString(c.key, null) ?: return false
        val s = v.substringBeforeLast('|')
        val z = v.substringAfterLast('|').toIntOrNull() ?: return false
        return s == source && z >= maxZoom
    }

    /**
     * [mapSource]: nombre de la fuente de mapa que se puede bajar, o null si no se puede (OSM):
     * en ese caso cuenta solo el relieve.
     */
    fun status(ctx: Context, c: Cell, mapSource: String?, maxZoom: Int): Status {
        val dem = demTiles(c).count { Dem.has(ctx, it.first, it.second) }
        val map = mapSource?.let { mapDone(ctx, c, it, maxZoom) } ?: true
        return when {
            dem == 4 && map -> Status.DONE
            dem > 0 || (mapSource != null && prefs(ctx).contains(c.key)) -> Status.PARTIAL
            else -> Status.NONE
        }
    }

    /** Etiqueta tipo "3-C" según la posición en la vista: columnas 1, 2, 3…; filas A, B, C… */
    fun label(col: Int, row: Int): String = "${col + 1}-${rowName(row)}"

    fun rowName(row: Int): String {
        var r = row
        var s = ""
        do { s = ('A' + r % 26) + s; r = r / 26 - 1 } while (r >= 0)
        return s
    }
}
