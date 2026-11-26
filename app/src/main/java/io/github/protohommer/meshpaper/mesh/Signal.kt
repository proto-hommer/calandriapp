package io.github.protohommer.meshpaper.mesh

import io.github.protohommer.meshpaper.MeshNode
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.Role

/**
 * Cómo se pinta un nodo:
 *  - el propio: ME
 *  - del grupo: GOOD / FAIR / POOR / WEAK según los saltos
 *  - del grupo con rol de instalación fija (router, base): FIXED
 *  - ajeno: RELAY si repite (aporta cobertura), MUTE si es CLIENT_MUTE (no repite)
 * FIXED, RELAY y MUTE no dicen nada de la señal: el anillo va aparte ([ring]).
 */
enum class Tone { ME, GOOD, FAIR, POOR, WEAK, RELAY, MUTE, FIXED }

object Signal {
    /** Más de esto sin oírlo = perdido (anillo cortado naranja). */
    const val LOST_AFTER_S = 3600L

    /**
     * Router y router tardío: el firmware les estira a 12 h la posición y la telemetría (NodeInfo,
     * cada 3 h). Siguen repitiendo lo de los demás, pero eso no cuenta como "oído": sin esto, a la
     * hora aparecían perdidos aunque estuvieran andando. Damos un margen sobre las 12 h.
     */
    const val ROUTER_LOST_AFTER_S = 13 * 3600L

    // Routers y Base hablan poco (y la Base queda quieta en un techo): 13 h antes de darlos por perdidos.
    private val QUIET_ROLES = setOf(Role.ROUTER, Role.ROUTER_LATE, Role.CLIENT_BASE)

    private fun lostAfter(n: MeshNode) = if (n.role in QUIET_ROLES) ROUTER_LOST_AFTER_S else LOST_AFTER_S

    fun isLost(n: MeshNode, now: Long): Boolean = n.lastHeard == 0L || now - n.lastHeard > lostAfter(n)

    /** Más de esto sin oírlo = no se dibuja en el mapa hasta que se lo vuelva a oír (los routers, como arriba). */
    const val HIDE_AFTER_S = 1800L

    fun isStale(n: MeshNode, now: Long): Boolean =
        n.lastHeard == 0L || now - n.lastHeard > (if (n.role in QUIET_ROLES) ROUTER_LOST_AFTER_S else HIDE_AFTER_S)

    /**
     * Calidad del enlace, por saltos:
     *  - directo o 1 salto: verde
     *  - 2 a 3 saltos: amarillo
     *  - 4 a 5 saltos: naranja
     *  - 6 o más, o sin noticias hace más de una hora: rojo
     *  - saltos desconocidos (firmware viejo): amarillo
     */
    fun quality(n: MeshNode, now: Long): Tone {
        if (isLost(n, now)) return Tone.WEAK
        val hops = n.hopsAway ?: return Tone.FAIR
        return when {
            hops <= 1 -> Tone.GOOD
            hops <= 3 -> Tone.FAIR
            hops <= 5 -> Tone.POOR
            else -> Tone.WEAK
        }
    }

    /** Roles pensados para un nodo que queda quieto en un lugar (techo, cerro, torre). */
    private val FIXED_ROLES = setOf(
        Role.ROUTER, Role.ROUTER_LATE, Role.CLIENT_BASE, Role.REPEATER, Role.ROUTER_CLIENT,
    )

    fun isFixedRole(n: MeshNode): Boolean = n.role in FIXED_ROLES

    /** Rol Base (CLIENT_BASE): fijo, pero repite como router solo lo de sus favoritos. Se dibuja hexágono. */
    fun isBaseRole(n: MeshNode): Boolean = n.role == Role.CLIENT_BASE

    fun tone(n: MeshNode, me: Int?, member: Boolean, now: Long): Tone = when {
        n.num == me -> Tone.ME
        member && isFixedRole(n) -> Tone.FIXED
        member -> quality(n, now)
        n.mute -> Tone.MUTE
        else -> Tone.RELAY
    }

    fun isForeign(tone: Tone): Boolean = tone == Tone.RELAY || tone == Tone.MUTE

    /** Color de la señal para mostrar: el propio no tiene, los demás según los saltos. */
    fun linkTone(tone: Tone, n: MeshNode, now: Long): Tone = if (tone == Tone.ME) Tone.ME else quality(n, now)

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
