package io.github.protohommer.meshpaper.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Siempre oscuro: es una interfaz tipo HUD y ahorra batería en pantallas OLED. */
private val HudColors = darkColorScheme(
    primary = HudCyan,
    onPrimary = Color(0xFF00171C),
    primaryContainer = HudCyanDim,
    onPrimaryContainer = Color(0xFFA6F4FF),
    secondary = Color(0xFF7FA3B8),
    onSecondary = Color(0xFF071118),
    secondaryContainer = Color(0xFF16252F),
    onSecondaryContainer = HudText,
    tertiary = SigRelay,
    onTertiary = Color(0xFF1A0733),
    background = HudBg,
    onBackground = HudText,
    surface = HudSurface,
    onSurface = HudText,
    surfaceVariant = HudSurfaceHi,
    onSurfaceVariant = HudTextDim,
    surfaceContainerLowest = HudBg,
    surfaceContainerLow = HudSurface,
    surfaceContainer = HudSurface,
    surfaceContainerHigh = HudSurfaceHi,
    surfaceContainerHighest = Color(0xFF16232E),
    outline = HudLine,
    outlineVariant = Color(0xFF16252F),
    error = SigWeak,
    onError = Color(0xFF2A0006),
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
    MaterialTheme(colorScheme = HudColors, typography = Typography, shapes = HudShapes) {
        // Texto claro por defecto en toda la app (no todo está dentro de un Surface).
        CompositionLocalProvider(LocalContentColor provides HudText, content = content)
    }
}
