package io.github.protohommer.meshpaper.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Dos paletas: noche (HUD neón sobre casi negro, la original) y día (fondo claro y los mismos
 * colores de señal, más oscuros para que se lean al sol). Los colores de abajo leen la paleta
 * activa: al cambiar de modo, todo lo que los usa se redibuja solo.
 */
data class Palette(
    val bg: Color, val surface: Color, val surfaceHi: Color, val line: Color,
    val cyan: Color, val cyanDim: Color, val text: Color, val textDim: Color,
    val copper: Color, val green: Color,
    val good: Color, val fair: Color, val poor: Color, val weak: Color,
    val relay: Color, val mute: Color, val fixed: Color,
)

val NightPalette = Palette(
    bg = Color(0xFF05090D), surface = Color(0xFF0B1219), surfaceHi = Color(0xFF111B24), line = Color(0xFF1C4255),
    cyan = Color(0xFF1EF4FF), cyanDim = Color(0xFF0B3D48), text = Color(0xFFD8E6EE), textDim = Color(0xFF8EA4B3),
    copper = Color(0xFFFF9A3C), green = Color(0xFF2BFF88),
    good = Color(0xFF2BFF88), fair = Color(0xFFFFE135), poor = Color(0xFFFF8A1F), weak = Color(0xFFFF2E63),
    relay = Color(0xFFC45CFF), mute = Color(0xFF6B7785), fixed = Color(0xFF2E8BFF),
)

/** Día: contraste alto sobre fondo claro (amarillo y verde neón no se leen sobre blanco). */
val DayPalette = Palette(
    bg = Color(0xFFF2F5F7), surface = Color(0xFFFFFFFF), surfaceHi = Color(0xFFE6ECF0), line = Color(0xFFAFC3CF),
    cyan = Color(0xFF007C9C), cyanDim = Color(0xFFCDECF3), text = Color(0xFF0C1A23), textDim = Color(0xFF4D6271),
    copper = Color(0xFFC4560E), green = Color(0xFF0B8F4D),
    good = Color(0xFF0B8F4D), fair = Color(0xFFA37A00), poor = Color(0xFFD15A00), weak = Color(0xFFCC1444),
    relay = Color(0xFF8A2BD6), mute = Color(0xFF6E7B87), fixed = Color(0xFF1A5FD1),
)

/** Modo elegido en Ajustes → Apariencia. AUTO sigue al sistema (tema oscuro del teléfono). */
enum class ThemeMode(val label: String) { NIGHT("NOCHE"), DAY("DÍA"), AUTO("AUTO") }

object Appearance {
    private const val PREFS = "ui"

    var mode by mutableStateOf(ThemeMode.NIGHT)
        private set

    /** Lo que se ve ahora (AUTO ya resuelto). Lo fija el tema en cada composición. */
    var isDay by mutableStateOf(false)
        internal set

    val palette: Palette get() = if (isDay) DayPalette else NightPalette

    fun load(ctx: Context) {
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

// La marca en versión neón: el cobre y el verde, encendidos (la intro usa siempre los de noche).
val NeonCopper: Color get() = p.copper
val NeonGreen: Color get() = p.green

// Estado de los nodos en el mapa y en la lista.
val SigGood: Color get() = p.good     // directo o 1 salto
val SigFair: Color get() = p.fair     // 2 a 3 saltos
val SigPoor: Color get() = p.poor     // 4 a 5 saltos
val SigWeak: Color get() = p.weak     // 6 o más saltos, o perdido
val SigRelay: Color get() = p.relay   // ajeno que repite (aporta cobertura): el nombre va en violeta
val SigMute: Color get() = p.mute     // ajeno que no repite
val SigFixed: Color get() = p.fixed   // del grupo, instalación fija (router, base): azul
val SigMe: Color get() = p.cyan       // este teléfono / nodo propio
