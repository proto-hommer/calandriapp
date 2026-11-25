package io.github.protohommer.meshpaper.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * CalandriApp 2.0: el mismo ADN oscuro, sin neón. Cada color tiene un solo trabajo: el texto y los
 * botones van en blanco (o casi negro de día), el cian es "vos" (este teléfono), el naranja es lo que
 * pide atención (perdido, la ruta, un obstáculo, un aviso) y el violeta es el nombre de un ajeno que
 * repite. La señal no va por color sino por barras; la forma dice qué es cada nodo.
 * Los colores de abajo leen la paleta activa: al cambiar de modo, todo se redibuja solo.
 */
data class Palette(
    val bg: Color, val surface: Color, val surfaceHi: Color, val line: Color,
    /** Acento de la interfaz: textos destacados, botones, pestaña elegida (ya no es cian). */
    val cyan: Color, val cyanDim: Color, val text: Color, val textDim: Color,
    /** Atención: la ruta y lo que hay que mirar. */
    val copper: Color, val green: Color,
    val good: Color, val fair: Color, val poor: Color, val weak: Color,
    val relay: Color, val mute: Color, val fixed: Color,
    /** Este teléfono / nodo propio: el único cian de la app. */
    val me: Color,
    /** Bordes de controles (campos, botones con borde): un poco más visibles que [line]. */
    val control: Color,
)

val NightPalette = Palette(
    bg = Color(0xFF07090C), surface = Color(0xFF0E1217), surfaceHi = Color(0xFF141920), line = Color(0xFF1C232C),
    cyan = Color(0xFFF1F3F5), cyanDim = Color(0xFF1C232C), text = Color(0xFFF1F3F5), textDim = Color(0xFF8A939D),
    copper = Color(0xFFFF7A2F), green = Color(0xFFF1F3F5),
    good = Color(0xFFF1F3F5), fair = Color(0xFFFF7A2F), poor = Color(0xFF8A949E), weak = Color(0xFFFF7A2F),
    relay = Color(0xFFA898E6), mute = Color(0xFF5E6873), fixed = Color(0xFFC2C9D1),
    me = Color(0xFF5CE1E6), control = Color(0xFF2A323C),
)

/** Día: la misma lógica sobre claro, para leer al sol. */
val DayPalette = Palette(
    bg = Color(0xFFF2F3F5), surface = Color(0xFFFFFFFF), surfaceHi = Color(0xFFE9ECEF), line = Color(0xFFD5DAE0),
    cyan = Color(0xFF14181D), cyanDim = Color(0xFFE3E6EA), text = Color(0xFF0E1217), textDim = Color(0xFF5B6570),
    copper = Color(0xFFD9531A), green = Color(0xFF14181D),
    good = Color(0xFF14181D), fair = Color(0xFFC2410C), poor = Color(0xFF6A747F), weak = Color(0xFFD9531A),
    relay = Color(0xFF6B55C9), mute = Color(0xFF8A939D), fixed = Color(0xFF3E4650),
    me = Color(0xFF08777D), control = Color(0xFFC3C9D0),
)

/** Modo elegido en Ajustes → Apariencia. AUTO sigue al sistema (tema oscuro del teléfono). */
enum class ThemeMode(val label: String) { NIGHT("Noche"), DAY("Día"), AUTO("Auto") }

object Appearance {
    private const val PREFS = "ui"

    var mode by mutableStateOf(ThemeMode.NIGHT)
        private set

    /** Lo que se ve ahora (AUTO ya resuelto). Lo fija el tema en cada composición. */
    var isDay by mutableStateOf(false)
        internal set

    /** 2.0: sin neón (brillos, halos, bordes que se encienden). Queda para el código que lo consulta. */
    val neon: Boolean get() = false

    /** 2.0: el estilo nuevo (la prueba "nocturno" de la 1.9.0) es el único. */
    val isNocturne: Boolean get() = true

    val palette: Palette get() = if (isDay) DayPalette else NightPalette

    fun load(ctx: Context) {
        // "NOCTURNE" (la prueba de la 1.9.0) ya es el de noche.
        mode = runCatching { ThemeMode.valueOf(prefs(ctx).getString("theme", null) ?: "") }.getOrDefault(ThemeMode.NIGHT)
    }

    fun set(ctx: Context, m: ThemeMode) {
        mode = m
        prefs(ctx).edit().putString("theme", m.name).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

private val p get() = Appearance.palette

// Paleta HUD: fondo, superficies, líneas, acento y texto.
val HudBg: Color get() = p.bg
val HudSurface: Color get() = p.surface
val HudSurfaceHi: Color get() = p.surfaceHi
val HudLine: Color get() = p.line
val HudCyan: Color get() = p.cyan
val HudCyanDim: Color get() = p.cyanDim
val HudText: Color get() = p.text
val HudTextDim: Color get() = p.textDim

// Marca CalandriApp (assets/brand): azul marino de fondo, cobre de la red, verde del ojo, texto.
val BrandNavy = Color(0xFF14223A)
val BrandCopper = Color(0xFFC98245)
val BrandGreen = Color(0xFF268647)
val BrandText = Color(0xFFF3F5F6)

// Nombres de antes: la ruta (atención) y el verde de la marca.
val NeonCopper: Color get() = p.copper
val NeonGreen: Color get() = p.green

// Estado de los nodos en el mapa y en la lista.
val SigGood: Color get() = p.good     // directo o 1 salto
val SigFair: Color get() = p.fair     // 2 a 3 saltos
val SigPoor: Color get() = p.poor     // 4 a 5 saltos
val SigWeak: Color get() = p.weak     // 6 o más saltos, o perdido
val SigRelay: Color get() = p.relay   // ajeno que repite (aporta cobertura): el nombre va en violeta
val SigMute: Color get() = p.mute     // ajeno que no repite
val SigFixed: Color get() = p.fixed   // del grupo, instalación fija (router, base): la forma es el rombo
val SigMe: Color get() = p.me          // este teléfono / nodo propio: el único cian

/** Borde de controles: campos y botones con borde. */
val HudControl: Color get() = p.control

/**
 * Semáforo de estado (verde bien, amarillo en curso o regular, naranja a medias, rojo mal). Solo
 * donde el color ES el dato y en gris se confunde: la cuadrícula de mapas sin conexión y el GPS
 * de la barra de arriba. El resto de la interfaz sigue la regla de un color por trabajo.
 */
val StatusOk: Color get() = if (Appearance.isDay) Color(0xFF15803D) else Color(0xFF3DDC84)
val StatusBusy: Color get() = if (Appearance.isDay) Color(0xFFB7791F) else Color(0xFFF5C542)
val StatusPartial: Color get() = if (Appearance.isDay) Color(0xFFC2410C) else Color(0xFFFF8A3D)
val StatusBad: Color get() = if (Appearance.isDay) Color(0xFFC81E3A) else Color(0xFFFF4D5E)
