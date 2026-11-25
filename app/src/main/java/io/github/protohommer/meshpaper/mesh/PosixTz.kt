package io.github.protohommer.meshpaper.mesh

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import kotlin.math.abs

/**
 * Zona horaria del teléfono en el formato POSIX que espera el firmware (`config.device.tzdef`,
 * el mismo que usa la variable TZ de C): "STD3" para Argentina (UTC−3, sin horario de verano),
 * "STD-1DST,M3.5.0/2,M10.5.0/3" para Europa Central.
 *
 * Los nombres van fijos en STD/DST: el nodo solo usa el desplazamiento y las reglas, y con letras
 * el `tzset()` de cualquier libc lo entiende (la forma "<-03>" no siempre).
 */
object PosixTz {
    fun of(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        val rules = zone.rules
        val std = rules.getStandardOffset(now)
        val t1 = rules.nextTransition(now)
        val t2 = t1?.let { rules.nextTransition(it.instant) }
        // Sin cambios de hora en el próximo año (o reglas que no son las de todos los años): fija.
        if (t1 == null || t2 == null || t2.instant.isAfter(now.plusSeconds(400L * 86_400))) {
            return "STD" + offset(std)
        }
        val start = listOf(t1, t2).firstOrNull { rules.isDaylightSavings(it.instant) } ?: return "STD" + offset(std)
        val end = if (start === t1) t2 else t1
        if (rules.isDaylightSavings(end.instant)) return "STD" + offset(std) // dos cambios de horario de verano: raro, no lo intentamos
        return "STD" + offset(std) + "DST" + offset(start.offsetAfter) + "," + rule(start) + "," + rule(end)
    }

    /** POSIX cuenta al revés: UTC−3 es "3", UTC+1 es "-1". Con minutos: "3:30". */
    private fun offset(o: ZoneOffset): String {
        val s = -o.totalSeconds
        val a = abs(s)
        val h = a / 3600
        val m = (a % 3600) / 60
        return (if (s < 0) "-" else "") + h + if (m != 0) ":%02d".format(m) else ""
    }

    /** "M3.5.0/2": marzo, última semana, domingo, a las 2 (hora local antes del cambio). */
    private fun rule(t: ZoneOffsetTransition): String {
        val d = t.dateTimeBefore
        val len = d.toLocalDate().lengthOfMonth()
        val week = if (d.dayOfMonth + 7 > len) 5 else (d.dayOfMonth - 1) / 7 + 1
        val dow = d.dayOfWeek.value % 7 // domingo = 0
        val time = d.hour.toString() + if (d.minute != 0) ":%02d".format(d.minute) else ""
        return "M${d.monthValue}.$week.$dow/$time"
    }
}
