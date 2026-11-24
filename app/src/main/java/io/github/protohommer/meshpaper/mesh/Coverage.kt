package io.github.protohommer.meshpaper.mesh

import android.content.Context
import android.graphics.Bitmap
import io.github.protohommer.meshpaper.map.Dem
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cobertura de un nodo según el relieve: la señal que le llegaría a uno de mano (1,5 m, 2 dBi)
 * en cada punto alrededor, y la sombra detrás de los cerros.
 *
 * Se trabaja sobre la grilla del relieve (Web Mercator, zoom 12, ~32 m por píxel). Desde el nodo
 * salen rayos hacia cada píxel del borde del cuadrado (el barrido "R2" de los viewsheds), así cada
 * píxel queda en algún rayo. A lo largo del rayo se recuerda el punto del terreno que más se levanta
 * visto desde la antena: ese es el filo que tapa. Para cada punto:
 *
 *   señal = potencia + ganancias − cable − propagación − difracción en ese filo (ITU-R P.526)
 *
 * Propagación: la mayor entre espacio libre y tierra plana (dos rayos, como en [Range]), con la
 * altura de la antena medida sobre el terreno del punto: desde un cerro hacia el valle cuenta
 * todo el desnivel, que es lo que hace que un nodo alto llegue lejos.
 *
 * Es la versión de un solo filo de lo que hace [Profile] con Deygout: unas mil veces más barato,
 * algo optimista cuando hay varias lomas seguidas. Tierra con curvatura k = 4/3. Sin árboles ni
 * edificios (el SRTM es el suelo).
 */
object Coverage {
    const val RADIUS_M = 25_000.0
    /** Margen sobre la sensibilidad que se pinta con el color más fuerte. */
    const val FULL_SCALE_DB = 40.0
    private const val EARTH_R = 6_371_000.0
    private const val K = 4.0 / 3.0

    data class Result(
        val bitmap: Bitmap,
        val north: Double, val west: Double, val south: Double, val east: Double,
        val sensitivityDbm: Double,
        /** Porcentaje del círculo con señal. */
        val coveredPct: Int,
    )

    sealed interface Outcome {
        data class Done(val result: Result) : Outcome
        /** Faltan tiles de relieve y no se pudieron bajar. */
        data class MissingTerrain(val missing: Int, val total: Int) : Outcome
        data object NoPreset : Outcome
    }

    /**
     * Bloquea (relieve + cálculo): llamar fuera del hilo principal.
     * [progress] recibe "bajando relieve" con (hechos, total).
     */
    fun compute(
        ctx: Context, lat: Double, lon: Double, antenna: Antenna, freqMHz: Double, preset: ModemPreset,
        network: Boolean, progress: (Int, Int) -> Unit = { _, _ -> },
    ): Outcome {
        val sens = Range.sensitivityDbm(preset) ?: return Outcome.NoPreset
        val mPerPx = 2 * PI * EARTH_R * cos(Math.toRadians(lat)) / Dem.worldPx
        val r = (RADIUS_M / mPerPx).roundToInt()
        val (cx, cy) = Dem.globalPx(lat, lon)
        val x0 = floor(cx).toInt() - r
        val y0 = floor(cy).toInt() - r
        val n = 2 * r + 1

        // Relieve de todo el cuadrado, en una grilla propia (más rápido que pedir punto por punto).
        val tiles = buildList {
            for (tx in Math.floorDiv(x0, Dem.SIZE)..Math.floorDiv(x0 + n - 1, Dem.SIZE))
                for (ty in Math.floorDiv(y0, Dem.SIZE)..Math.floorDiv(y0 + n - 1, Dem.SIZE)) add(tx to ty)
        }
        val ground = FloatArray(n * n) { Float.NaN }
        var missing = 0
        tiles.forEachIndexed { i, (tx, ty) ->
            progress(i, tiles.size)
            val t = Dem.tileData(ctx, tx, ty, network)
            if (t == null) { missing++; return@forEachIndexed }
            for (py in 0 until Dem.SIZE) {
                val gy = ty * Dem.SIZE + py - y0
                if (gy !in 0 until n) continue
                for (px in 0 until Dem.SIZE) {
                    val gx = tx * Dem.SIZE + px - x0
                    if (gx !in 0 until n) continue
                    ground[gy * n + gx] = t[py * Dem.SIZE + px].toFloat()
                }
            }
        }
        progress(tiles.size, tiles.size)
        if (missing > 0) return Outcome.MissingTerrain(missing, tiles.size)

        val c = r  // el nodo, en la grilla
        val hTx = ground[c * n + c] + antenna.heightM
        val lambda = 299.792458 / freqMHz
        val eirp = antenna.txDbm + antenna.gainDbi - antenna.cableDb + Range.RX_GAIN_DBI
        val fsplConst = 20 * log10(freqMHz) + 32.44
        val margin = FloatArray(n * n) { Float.NEGATIVE_INFINITY }
        val r2 = r.toLong() * r

        /** Relieve interpolado en una posición fraccionaria de la grilla. */
        fun groundAt(x: Double, y: Double): Double {
            val ix = x.toInt().coerceIn(0, n - 2); val iy = y.toInt().coerceIn(0, n - 2)
            val fx = x - ix; val fy = y - iy
            val k = iy * n + ix
            return (ground[k] * (1 - fx) + ground[k + 1] * fx) * (1 - fy) +
                (ground[k + n] * (1 - fx) + ground[k + n + 1] * fx) * fy
        }
        val minEff = antenna.heightM.coerceAtLeast(0.5)
        val rxTerm = 20 * log10(Range.RX_HEIGHT_M)

        fun ray(ex: Int, ey: Int) {
            val dx = ex - c; val dy = ey - c
            val steps = max(abs(dx), abs(dy))
            val sx = dx.toDouble() / steps; val sy = dy.toDouble() / steps
            val stepM = sqrt(sx * sx + sy * sy) * mPerPx
            var bestSlope = Double.NEGATIVE_INFINITY  // pendiente desde la antena al filo más alto visto
            var bestD = 0.0
            for (i in 1..steps) {
                val fx = c + sx * i; val fy = c + sy * i
                val gx = fx.roundToInt(); val gy = fy.roundToInt()
                val ddx = (gx - c).toLong(); val ddy = (gy - c).toLong()
                if (ddx * ddx + ddy * ddy > r2) break
                val d = i * stepM
                val curv = d * d / (2 * K * EARTH_R)
                val g = groundAt(fx, fy) - curv
                // Señal en este punto, con el filo que haya antes.
                val los = (g + Range.RX_HEIGHT_M - hTx) / d
                var diff = 0.0
                if (bestD > 0) {
                    val d1 = bestD; val d2 = d - bestD
                    val h = (bestSlope - los) * d1
                    val v = h * sqrt(2 * d / (lambda * d1 * d2))
                    diff = Profile.knifeEdgeDb(v)
                }
                val fspl = 20 * log10(d / 1000) + fsplConst
                val hEff = max(minEff, hTx - g)
                val planeEarth = 40 * log10(d) - 20 * log10(hEff) - rxTerm
                val m = (eirp - max(fspl, planeEarth) - diff - sens).toFloat()
                val k = gy * n + gx
                if (m > margin[k]) margin[k] = m
                // El terreno de este punto pasa a ser filo para lo que sigue.
                val s = (g - hTx) / d
                if (s > bestSlope) { bestSlope = s; bestD = d }
            }
        }
        for (i in 0 until n) { ray(i, 0); ray(i, n - 1); ray(0, i); ray(n - 1, i) }

        val px = IntArray(n * n)
        var covered = 0; var inside = 0
        for (gy in 0 until n) for (gx in 0 until n) {
            val ddx = (gx - c).toLong(); val ddy = (gy - c).toLong()
            if (ddx * ddx + ddy * ddy > r2) continue
            inside++
            val m = margin[gy * n + gx]
            if (m < 0f) continue
            covered++
            px[gy * n + gx] = color(m / FULL_SCALE_DB.toFloat())
        }
        val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        val (north, west) = Dem.latLonOfPx(x0.toDouble(), y0.toDouble())
        val (south, east) = Dem.latLonOfPx((x0 + n).toDouble(), (y0 + n).toDouble())
        return Outcome.Done(Result(bmp, north, west, south, east, sens, if (inside == 0) 0 else covered * 100 / inside))
    }

    // ---------- CMRmap (matplotlib, _cm._CMRmap_data) ----------

    private val CMR_R = floatArrayOf(0f, .15f, .30f, .60f, 1f, .90f, .90f, .90f, 1f)
    private val CMR_G = floatArrayOf(0f, .15f, .15f, .20f, .25f, .50f, .75f, .90f, 1f)
    private val CMR_B = floatArrayOf(0f, .50f, .75f, .50f, .15f, 0f, .10f, .50f, 1f)

    /** CMRmap en [t] ∈ 0..1, sin la punta negra (no se vería en el mapa oscuro) ni la blanca. */
    const val CMR_FROM = 0.16f
    const val CMR_TO = 0.92f

    fun cmr(t: Float): Int {
        val x = (t.coerceIn(0f, 1f) * 8f)
        val i = x.toInt().coerceAtMost(7)
        val f = x - i
        fun ch(a: FloatArray) = ((a[i] + (a[i + 1] - a[i]) * f) * 255f).roundToInt()
        return (0xFF shl 24) or (ch(CMR_R) shl 16) or (ch(CMR_G) shl 8) or ch(CMR_B)
    }

    /** Color para un margen relativo [m] (0 = al límite, 1 = [FULL_SCALE_DB] o más), translúcido. */
    fun color(m: Float, alpha: Int = 0x9C): Int =
        (cmr(CMR_FROM + (CMR_TO - CMR_FROM) * m.coerceIn(0f, 1f)) and 0xFFFFFF) or (alpha shl 24)
}
