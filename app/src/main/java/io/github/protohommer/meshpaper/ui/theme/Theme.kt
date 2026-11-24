package io.github.protohommer.meshpaper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Noche: interfaz tipo HUD, oscura (ahorra batería en pantallas OLED). */
private fun nightScheme(p: Palette): ColorScheme = darkColorScheme(
    primary = p.cyan,
    onPrimary = Color(0xFF00171C),
    primaryContainer = p.cyanDim,
    onPrimaryContainer = Color(0xFFA6F4FF),
    secondary = Color(0xFF7FA3B8),
    onSecondary = Color(0xFF071118),
    secondaryContainer = Color(0xFF16252F),
    onSecondaryContainer = p.text,
    tertiary = p.relay,
    onTertiary = Color(0xFF1A0733),
    background = p.bg,
    onBackground = p.text,
    surface = p.surface,
    onSurface = p.text,
    surfaceVariant = p.surfaceHi,
    onSurfaceVariant = p.textDim,
    surfaceContainerLowest = p.bg,
    surfaceContainerLow = p.surface,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.surfaceHi,
    surfaceContainerHighest = Color(0xFF16232E),
    outline = p.line,
    outlineVariant = Color(0xFF16252F),
    error = p.weak,
    onError = Color(0xFF2A0006),
)

/** Día: lo mismo, claro y con contraste para leer al sol. */
private fun dayScheme(p: Palette): ColorScheme = lightColorScheme(
    primary = p.cyan,
    onPrimary = Color.White,
    primaryContainer = p.cyanDim,
    onPrimaryContainer = Color(0xFF00313D),
    secondary = Color(0xFF4F6B7C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6EC),
    onSecondaryContainer = p.text,
    tertiary = p.relay,
    onTertiary = Color.White,
    background = p.bg,
    onBackground = p.text,
    surface = p.surface,
    onSurface = p.text,
    surfaceVariant = p.surfaceHi,
    onSurfaceVariant = p.textDim,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = p.bg,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.surfaceHi,
    surfaceContainerHighest = Color(0xFFDCE4E9),
    outline = p.line,
    outlineVariant = Color(0xFFD3DDE3),
    error = p.weak,
    onError = Color.White,
)

/** Esquinas cortadas en vez de redondeadas. */
private val HudShapes = Shapes(
    extraSmall = CutCornerShape(3.dp),
    small = CutCornerShape(5.dp),
    medium = CutCornerShape(8.dp),
    large = CutCornerShape(12.dp),
    extraLarge = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
)

@Composable
fun MeshPaperTheme(content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val day = when (Appearance.mode) {
        ThemeMode.DAY -> true
        ThemeMode.NIGHT -> false
        ThemeMode.AUTO -> !system
    }
    // Se fija acá mismo para que el primer cuadro ya salga con la paleta correcta (solo si cambió).
    if (Appearance.isDay != day) Appearance.isDay = day
    val pal = if (day) DayPalette else NightPalette
    MaterialTheme(colorScheme = if (day) dayScheme(pal) else nightScheme(pal), typography = Typography, shapes = HudShapes) {
        // Color de texto por defecto en toda la app (no todo está dentro de un Surface).
        CompositionLocalProvider(LocalContentColor provides pal.text, content = content)
    }
}
