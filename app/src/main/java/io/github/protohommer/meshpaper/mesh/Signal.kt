package io.github.protohommer.meshpaper.mesh

import io.github.protohommer.meshpaper.MeshNode

/**
 * Cómo se pinta un nodo:
 *  - el propio: ME
 *  - del grupo: GOOD / FAIR / WEAK según la calidad del enlace
 *  - ajeno: RELAY si repite (aporta cobertura), MUTE si es CLIENT_MUTE (no repite)
 */
enum class Tone { ME, GOOD, FAIR, WEAK, RELAY, MUTE }

object Signal {
    /** Más de esto sin oírlo = perdido (se pinta apagado; si es del grupo, rojo). */
    const val LOST_AFTER_S = 3600L

    // LongFast (SF11) demodula hasta ~-17,5 dB de SNR. Márgenes: >= 10 dB bueno, >= 4 dB medio.
    const val SNR_GOOD = -7f
    const val SNR_FAIR = -13f
    // SX1262 en LongFast llega a ~-137 dBm; arriba de -110 sobra, abajo de -120 está justo.
    const val RSSI_GOOD = -110
    const val RSSI_FAIR = -120

    fun isLost(n: MeshNode, now: Long): Boolean = n.lastHeard == 0L || now - n.lastHeard > LOST_AFTER_S

    /**
     * Calidad del enlace con un miembro del grupo:
     *  - directo (0 saltos): SNR y RSSI del último paquete que nos llegó directo
     *  - por repetidores: 1 salto = medio, 2 o más = malo
     *  - sin noticias hace más de una hora = malo
     */
    fun quality(n: MeshNode, now: Long): Tone {
        if (isLost(n, now)) return Tone.WEAK
        return when (val hops = n.hopsAway) {
            null -> Tone.FAIR
            0 -> {
                val bySnr = when {
                    n.snr >= SNR_GOOD -> Tone.GOOD
                    n.snr >= SNR_FAIR -> Tone.FAIR
                    else -> Tone.WEAK
                }
                val r = n.rssi
                val byRssi = when {
                    r == null || r >= RSSI_GOOD -> Tone.GOOD
                    r >= RSSI_FAIR -> Tone.FAIR
                    else -> Tone.WEAK
                }
                maxOf(bySnr, byRssi) // el peor de los dos (GOOD < FAIR < WEAK en el enum)
            }
            1 -> Tone.FAIR
            else -> if (hops >= 2) Tone.WEAK else Tone.FAIR
        }
    }

    fun tone(n: MeshNode, me: Int?, member: Boolean, now: Long): Tone = when {
        n.num == me -> Tone.ME
        member -> quality(n, now)
        n.mute -> Tone.MUTE
        else -> Tone.RELAY
    }

    /** Texto corto de la señal para fichas y listas. */
    fun describe(n: MeshNode): String? = when (n.hopsAway) {
        0 -> buildString {
            append("SNR %.1f dB".format(n.snr))
            n.rssi?.let { append(" · RSSI $it dBm") }
        }
        null -> null
        1 -> "1 salto"
        else -> "${n.hopsAway} saltos"
    }
}
