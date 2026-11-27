package io.github.protohommer.meshpaper.mesh

import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Perfil de terreno entre dos nodos y si el enlace pasa.
 *
 * - Tierra con curvatura efectiva k = 4/3 (la refracción normal de la atmósfera).
 * - Línea de vista recta entre las dos antenas (terreno + altura de antena).
 * - Primera zona de Fresnel: r = √(λ·d₁·d₂ / D). Despejar el 60 % alcanza para no perder señal.
 * - Pérdida = espacio libre + difracción por filo de cuchillo (ITU-R P.526) con el método de
 *   Deygout de 3 filos: el obstáculo principal y, a cada lado, el que más tape. Con más filos
 *   Deygout sobreestima mucho en terreno que roza la línea todo el camino.
 * - Margen = presupuesto del enlace − pérdida, en el sentido más débil de los dos.
 *
 * No incluye edificios ni árboles: el SRTM es el suelo (más o menos, en el bosque mide la copa).
 */
object Profile {
    private const val EARTH_R = 6_371_000.0
    private const val K = 4.0 / 3.0
    const val DEFAULT_FREQ_MHZ = 915.0

    /** Un extremo del enlace: dónde está y su antena. */
    data class End(val lat: Double, val lon: Double, val antenna: Antenna)

    data class Result(
        val distanceM: Double,
        val xs: DoubleArray,          // distancia desde A de cada muestra (m)
        val ground: DoubleArray,      // terreno sobre el nivel del mar (m)
        val terrain: DoubleArray,     // terreno + curvatura (lo que se dibuja)
        val los: DoubleArray,         // línea de vista
        val fresnel: DoubleArray,     // radio de la 1ª zona de Fresnel
        val worst: Int,               // muestra con menos despeje (relativo a Fresnel)
        val worstRatio: Double,       // despeje / radio de Fresnel en el peor punto (<0 = tapado)
        val diffractionDb: Double,
        val pathLossDb: Double,
        val marginDb: Double?,        // null si no se conoce la sensibilidad del preset
    ) {
        val verdict: Verdict
            get() = when {
                marginDb == null -> if (worstRatio >= 0.6) Verdict.PASA else if (worstRatio >= 0) Verdict.LIMITE else Verdict.NO_PASA
                marginDb >= Range.FADE_MARGIN_DB -> Verdict.PASA
                marginDb >= 0 -> Verdict.LIMITE
                else -> Verdict.NO_PASA
            }
    }

    enum class Verdict { PASA, LIMITE, NO_PASA }

    /** Frecuencia real del nodo: la forzada, la del slot, o el medio de la banda. */
    fun frequencyMHz(lora: LoRaConfig?): Double {
        if (lora == null) return DEFAULT_FREQ_MHZ
        if (lora.overrideFrequency > 0f) return lora.overrideFrequency.toDouble()
        Regions.frequencyMHz(lora.region, lora.modemPreset, lora.channelNum)?.let { return it }
        return Regions.band(lora.region)?.let { (it.start + it.end) / 2 } ?: DEFAULT_FREQ_MHZ
    }

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
        val dp = p2 - p1; val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(h)))
    }

    /** Puntos sobre el círculo máximo de A a B, uno cada ~30 m (la grilla del SRTM). */
    fun samplePoints(a: End, b: End): List<Pair<Double, Double>> {
        val d = distanceM(a.lat, a.lon, b.lat, b.lon)
        val n = (d / 30).roundToInt().coerceIn(32, 800)
        val p1 = Math.toRadians(a.lat); val l1 = Math.toRadians(a.lon)
        val p2 = Math.toRadians(b.lat); val l2 = Math.toRadians(b.lon)
        val delta = d / EARTH_R
        return List(n + 1) { i ->
            val f = i.toDouble() / n
            if (delta < 1e-9) return@List a.lat to a.lon
            val s = sin(delta)
            val ka = sin((1 - f) * delta) / s
            val kb = sin(f * delta) / s
            val x = ka * cos(p1) * cos(l1) + kb * cos(p2) * cos(l2)
            val y = ka * cos(p1) * sin(l1) + kb * cos(p2) * sin(l2)
            val z = ka * sin(p1) + kb * sin(p2)
            Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to Math.toDegrees(atan2(y, x))
        }
    }

    /** Pérdida por un filo de cuchillo, J(v) de ITU-R P.526 (0 si v ≤ −0,78). */
    fun knifeEdgeDb(v: Double): Double =
        if (v <= -0.78) 0.0 else 6.9 + 20 * log10(sqrt((v - 0.1) * (v - 0.1) + 1) + v - 0.1)

    /** Solo la pérdida del camino (dB): espacio libre + difracción, como en [compute]. */
    fun pathLossDb(a: End, b: End, ground: List<Double>, freqMHz: Double): Double =
        compute(a, b, ground, freqMHz, ModemPreset.LONG_FAST).pathLossDb

    /**
     * [ground]: alturas del terreno en los puntos de [samplePoints] (mismo orden).
     */
    fun compute(a: End, b: End, ground: List<Double>, freqMHz: Double, preset: ModemPreset): Result {
        val n = ground.size - 1
        val dist = distanceM(a.lat, a.lon, b.lat, b.lon)
        val lambda = 299.792458 / freqMHz
        val xs = DoubleArray(n + 1) { dist * it / n }
        val g = ground.toDoubleArray()
        val terrain = DoubleArray(n + 1) { xs[it] * (dist - xs[it]) / (2 * K * EARTH_R) + g[it] }
        val hA = g[0] + a.antenna.heightM
        val hB = g[n] + b.antenna.heightM
        val los = DoubleArray(n + 1) { hA + (hB - hA) * xs[it] / max(dist, 1.0) }
        val fres = DoubleArray(n + 1) { sqrt(lambda * xs[it] * (dist - xs[it]) / max(dist, 1.0)) }

        var worst = 0
        var worstRatio = Double.MAX_VALUE
        for (i in 1 until n) {
            if (fres[i] < 0.01) continue
            val r = (los[i] - terrain[i]) / fres[i]
            if (r < worstRatio) { worstRatio = r; worst = i }
        }
        if (worstRatio == Double.MAX_VALUE) worstRatio = 1.0

        // Deygout: el filo principal entre i0 e i1 y, recursivamente, uno a cada lado.
        fun edges(i0: Int, i1: Int, depth: Int): Double {
            if (i1 - i0 < 2 || depth > 1) return 0.0
            val d = xs[i1] - xs[i0]
            val y0 = terrain[i0] + if (i0 == 0) a.antenna.heightM else 0.0
            val y1 = terrain[i1] + if (i1 == n) b.antenna.heightM else 0.0
            var best = -1; var bestV = -Double.MAX_VALUE
            for (i in i0 + 1 until i1) {
                val d1 = xs[i] - xs[i0]; val d2 = xs[i1] - xs[i]
                if (d1 <= 0 || d2 <= 0) continue
                val line = y0 + (y1 - y0) * d1 / d
                val v = (terrain[i] - line) * sqrt(2 * d / (lambda * d1 * d2))
                if (v > bestV) { bestV = v; best = i }
            }
            if (best < 0 || bestV <= -0.78) return 0.0
            return knifeEdgeDb(bestV) + edges(i0, best, depth + 1) + edges(best, i1, depth + 1)
        }
        val diff = edges(0, n, 0)
        val fspl = 20 * log10(max(dist, 1.0) / 1000) + 20 * log10(freqMHz) + 32.44
        val loss = fspl + diff
        val margin = Range.sensitivityDbm(preset)?.let { sens ->
            fun budget(tx: Antenna, rx: Antenna) =
                tx.txDbm + tx.gainDbi - tx.cableDb + rx.gainDbi - rx.cableDb - sens
            min(budget(a.antenna, b.antenna), budget(b.antenna, a.antenna)) - loss
        }
        return Result(dist, xs, g, terrain, los, fres, worst, worstRatio, diff, loss, margin)
    }
}
