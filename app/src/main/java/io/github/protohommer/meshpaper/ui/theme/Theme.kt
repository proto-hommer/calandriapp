package io.github.protohommer.meshpaper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * Esquemas de Material armados desde la paleta: el "primary" (botones, interruptores, selección) es
 * el blanco del texto de noche y el casi negro de día; nada de cian salvo "vos".
 */
private fun scheme(p: Palette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.cyan,
        onPrimary = p.bg,
        primaryContainer = p.surfaceHi,
        onPrimaryContainer = p.text,
        secondary = p.textDim,
        onSecondary = p.bg,
        secondaryContainer = p.cyanDim,
        onSecondaryContainer = p.text,
        tertiary = p.relay,
        onTertiary = p.bg,
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
        surfaceContainerHighest = p.surfaceHi,
        surfaceBright = p.surfaceHi,
        surfaceDim = p.bg,
        outline = p.control,
        outlineVariant = p.line,
        error = p.weak,
        onError = p.bg,
        inverseSurface = p.text,
        inverseOnSurface = p.bg,
    )
}

/** Esquinas redondeadas y tranquilas (antes, cortadas en diagonal). */
private val HudShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
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
    MaterialTheme(colorScheme = scheme(pal, dark = !day), typography = Typography, shapes = HudShapes) {
        // Color de texto por defecto en toda la app (no todo está dentro de un Surface).
        CompositionLocalProvider(LocalContentColor provides pal.text, content = content)
    }
}
