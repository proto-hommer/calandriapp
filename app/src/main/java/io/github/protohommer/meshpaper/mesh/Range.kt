package io.github.protohommer.meshpaper.mesh

import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Antena de un nodo, cargada a mano: Meshtastic no transmite la ganancia ni la altura.
 * [txDbm] es la potencia de salida de la radio (el SX1262 llega a 22 dBm).
 */
data class Antenna(val gainDbi: Double, val heightM: Double, val cableDb: Double = 0.0, val txDbm: Int = 22) {
    fun encode(): String = "$gainDbi;$heightM;$cableDb;$txDbm"

    companion object {
        fun decode(s: String): Antenna? {
            val p = s.split(';')
            if (p.size < 4) return null
            return Antenna(
                p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null,
                p[2].toDoubleOrNull() ?: 0.0, p[3].toIntOrNull() ?: 22,
            )
        }
    }
}

/**
 * Estimación gruesa del alcance de un nodo hacia uno de mano (1,5 m, antena de 2 dBi).
 *
 * Presupuesto del enlace: potencia + ganancias − cable − sensibilidad − margen de 10 dB.
 * Pérdida por tierra plana (dos rayos): PL = 40·log d − 20·log hₜ − 20·log hᵣ, que a estas
 * distancias es la que manda la altura de las antenas. Tope: el horizonte de radio (k = 4/3)
 * y el espacio libre. "Ciudad" le suma 20 dB de edificios y árboles.
 * No mira el relieve: un cerro en el medio corta el enlace aunque el número diga otra cosa.
 */
object Range {
    const val RX_HEIGHT_M = 1.5
    const val RX_GAIN_DBI = 2.0
    const val FADE_MARGIN_DB = 10.0
    const val URBAN_CLUTTER_DB = 20.0
    private const val NOISE_FIGURE_DB = 6.0
    private const val FREQ_MHZ = 915.0

    /** Spreading factor de cada preset (MeshRadio.h del firmware). */
    fun spreadingFactor(p: ModemPreset): Int? = when (p) {
        ModemPreset.SHORT_TURBO, ModemPreset.SHORT_FAST -> 7
        ModemPreset.SHORT_SLOW -> 8
        ModemPreset.MEDIUM_FAST, ModemPreset.MEDIUM_TURBO -> 9
        ModemPreset.MEDIUM_SLOW -> 10
        ModemPreset.LONG_FAST, ModemPreset.LONG_TURBO, ModemPreset.LONG_MODERATE -> 11
        ModemPreset.LONG_SLOW -> 12
        else -> null
    }

    /** Ancho de banda (kHz) de cada preset, sin "wide LoRa" (MeshRadio.h). */
    fun bandwidthKHz(p: ModemPreset): Double? = when (p) {
        ModemPreset.SHORT_TURBO, ModemPreset.MEDIUM_TURBO, ModemPreset.LONG_TURBO -> 500.0
        ModemPreset.SHORT_FAST, ModemPreset.SHORT_SLOW, ModemPreset.MEDIUM_FAST,
        ModemPreset.MEDIUM_SLOW, ModemPreset.LONG_FAST -> 250.0
        ModemPreset.LONG_MODERATE, ModemPreset.LONG_SLOW -> 125.0
        else -> null
    }

    /** Sensibilidad (dBm): ruido térmico + figura de ruido + SNR mínima de LoRa (−2,5 dB por SF). */
    fun sensitivityDbm(p: ModemPreset): Double? {
        val sf = spreadingFactor(p) ?: return null
        val bw = bandwidthKHz(p) ?: return null
        return -174 + 10 * log10(bw * 1000) + NOISE_FIGURE_DB - 2.5 * (sf - 4)
    }

    data class Estimate(val openM: Double, val urbanM: Double, val horizonM: Double, val maxLossDb: Double)

    fun estimate(a: Antenna, preset: ModemPreset): Estimate? {
        val sens = sensitivityDbm(preset) ?: return null
        val ht = a.heightM.coerceAtLeast(0.5)
        val maxLoss = a.txDbm + a.gainDbi - a.cableDb + RX_GAIN_DBI - sens - FADE_MARGIN_DB
        val horizon = 4120.0 * (sqrt(ht) + sqrt(RX_HEIGHT_M))
        fun reach(loss: Double): Double {
            val planeEarth = 10.0.pow((loss + 20 * log10(ht) + 20 * log10(RX_HEIGHT_M)) / 40)
            val freeSpace = 10.0.pow((loss - 20 * log10(FREQ_MHZ) + 27.55) / 20)
            return min(min(planeEarth, freeSpace), horizon)
        }
        return Estimate(reach(maxLoss), reach(maxLoss - URBAN_CLUTTER_DB), horizon, maxLoss)
    }
}
