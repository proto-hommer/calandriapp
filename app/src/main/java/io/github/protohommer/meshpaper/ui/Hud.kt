package io.github.protohommer.meshpaper.ui

import io.github.protohommer.meshpaper.ui.theme.Appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.ExperimentalHazeApi
import io.github.protohommer.meshpaper.mesh.Tone
import io.github.protohommer.meshpaper.ui.theme.HudBg
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudLine
import io.github.protohommer.meshpaper.ui.theme.HudSurface
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigMe
import io.github.protohommer.meshpaper.ui.theme.SigFixed
import io.github.protohommer.meshpaper.ui.theme.SigMute
import io.github.protohommer.meshpaper.ui.theme.SigPoor
import io.github.protohommer.meshpaper.ui.theme.SigRelay
import io.github.protohommer.meshpaper.ui.theme.SigWeak

val HudButtonShape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
val HudPanelShape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)

fun Tone.color(): Color = when (this) {
    Tone.ME -> SigMe
    Tone.GOOD -> SigGood
    Tone.FAIR -> SigFair
    Tone.POOR -> SigPoor
    Tone.WEAK -> SigWeak
    Tone.RELAY -> SigRelay
    Tone.MUTE -> SigMute
    Tone.FIXED -> SigFixed
}

// ---------- Neón y vidrio ----------

/**
 * Lo que se ve detrás de los paneles (el mapa) para desenfocarlo. null = no hay nada que
 * desenfocar (pantallas con fondo liso): el vidrio queda como un tinte translúcido.
 */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Vidrio esmerilado oscuro: desenfoque fuerte y tinte del fondo a media opacidad. Sin grano y a
 * resolución completa: con grano y la entrada a 1/3 (para ahorrar al arrastrar) se veía pixelado,
 * y desde que el desenfoque se apaga mientras se mueve el mapa ese ahorro no hace falta.
 */
internal val GlassStyle: HazeStyle
    get() = HazeStyle(
        backgroundColor = HudBg,
        // De día el velo es más opaco: sobre un mapa claro y con sol, el texto oscuro necesita fondo.
        tint = HazeTint(HudBg.copy(alpha = if (Appearance.isDay) 0.58f else 0.42f)),
        blurRadius = 18.dp,
        noiseFactor = 0f,
        // Sin desenfoque (Android 11 o anterior, o el mapa moviéndose): un velo que se lea igual.
        fallbackTint = HazeTint(HudBg.copy(alpha = if (Appearance.isDay) 0.86f else 0.74f)),
    )

/**
 * Halo de luz alrededor de [shape], como un tubo de neón. Se dibuja por fuera (no recorta).
 * Android 9 o más (antes la GPU no desenfoca trazos): en los viejos no hace nada.
 */
fun Modifier.neonGlow(color: Color, shape: Shape, radius: Dp = 8.dp, width: Dp = 2.dp): Modifier =
    // De día no hay neón: sobre fondo claro el halo parece una mancha.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || Appearance.isDay) this else drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val paint = Paint().apply {
            style = PaintingStyle.Stroke
            strokeWidth = width.toPx()
            this.color = color
            asFrameworkPaint().maskFilter = BlurMaskFilter(radius.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        drawIntoCanvas { it.drawOutline(outline, paint) }
    }

/**
 * Panel de vidrio: desenfoca lo que hay detrás (Android 12+), lo tiñe de oscuro, le pone un
 * reflejo suave arriba y un borde de neón en [accent] con su halo.
 * [blur] = false para cosas chicas y numerosas (botones): solo tinte, que es más liviano.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glass(shape: Shape, accent: Color = HudCyan, blur: Boolean = true, glow: Boolean = true): Modifier {
    val haze = LocalHaze.current.takeIf { blur }
    return this
        .then(if (glow) Modifier.neonGlow(accent.copy(alpha = 0.55f), shape, 7.dp) else Modifier)
        .clip(shape)
        .then(
            if (haze != null) Modifier.hazeEffect(haze, GlassStyle).background(HudSurface.copy(alpha = 0.18f))
            // De día, más opaco: sobre el mapa claro los botones translúcidos se perdían.
            else Modifier.background(HudSurface.copy(alpha = if (Appearance.isDay) 0.86f else if (blur) 0.62f else 0.55f))
        )
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.07f),
                0.35f to Color.Transparent,
                1f to accent.copy(alpha = 0.06f),
            )
        )
        .border(
            1.dp,
            Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.25f), accent.copy(alpha = 0.75f))),
            shape,
        )
}

/** Texto con brillo de neón (sombra difusa del mismo color). */
fun TextStyle.neon(color: Color, radius: Float = 14f): TextStyle =
    copy(shadow = Shadow(color.copy(alpha = 0.85f), Offset.Zero, radius))

/** Los textos de color (acentos y señales) brillan; los grises y el blanco del texto, no. */
private fun glows(color: Color) = !Appearance.isDay && color.alpha > 0.5f && color != HudTextDim &&
    color.red + color.green + color.blue < 2.6f && maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) > 0.35f

/** Esquinas tipo visor sobre cualquier cosa. */
fun Modifier.hudCorners(color: Color = HudCyan.copy(alpha = 0.7f), len: Dp = 14.dp, stroke: Dp = 1.5.dp): Modifier =
    drawWithContent {
        drawContent()
        val l = len.toPx()
        val s = stroke.toPx()
        val w = size.width
        val h = size.height
        fun seg(a: Offset, b: Offset) = drawLine(color, a, b, s, StrokeCap.Square)
        seg(Offset(0f, 0f), Offset(l, 0f)); seg(Offset(0f, 0f), Offset(0f, l))
        seg(Offset(w, 0f), Offset(w - l, 0f)); seg(Offset(w, 0f), Offset(w, l))
        seg(Offset(0f, h), Offset(l, h)); seg(Offset(0f, h), Offset(0f, h - l))
        seg(Offset(w, h), Offset(w - l, h)); seg(Offset(w, h), Offset(w, h - l))
    }

/** Panel translúcido con borde fino y rótulo opcional. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = HudCyan,
    padding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .glass(HudPanelShape, accent)
            .padding(padding),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 12.dp).neonGlow(accent, HudButtonShape, 4.dp, 3.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                HudLabel(title, color = accent)
            }
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** Rótulo chico, monoespaciado y en mayúsculas. */
@Composable
fun HudLabel(text: String, modifier: Modifier = Modifier, color: Color = HudTextDim, maxLines: Int = 1) {
    val style = MaterialTheme.typography.labelSmall
    Text(
        text.uppercase(), modifier, style = if (glows(color)) style.neon(color, 10f) else style, color = color,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Dato destacado monoespaciado. */
@Composable
fun HudValue(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val style = MaterialTheme.typography.labelLarge
    Text(text, modifier, style = if (glows(color)) style.neon(color) else style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Par rótulo / valor en columna. */
@Composable
fun HudStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        HudLabel(label)
        HudValue(value, color = color)
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).neonGlow(color, CircleShape, 4.dp, size / 2).background(color, CircleShape))
}

/** Encabezado de pantalla: volver + título. */
@Composable
fun HudHeader(title: String, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹") }
        Text(
            title.uppercase(), Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
fun HudButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = HudButtonShape,
        modifier = if (enabled) modifier.neonGlow(HudCyan.copy(alpha = 0.6f), HudButtonShape, 6.dp) else modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = HudCyan.copy(alpha = 0.16f),
            contentColor = HudCyan,
            disabledContainerColor = HudLine.copy(alpha = 0.4f),
        ),
        border = BorderStroke(1.dp, if (enabled) HudCyan.copy(alpha = 0.8f) else HudLine),
        content = content,
    )
}

@Composable
fun HudOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = HudButtonShape,
        border = BorderStroke(1.dp, HudLine),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        content = content,
    )
}

/** Botón cuadrado chico para la columna de herramientas del mapa. */
@Composable
fun HudIconButton(label: String, onClick: () -> Unit, active: Boolean = false, enabled: Boolean = true) {
    val c = when {
        !enabled -> HudLine
        active -> HudCyan
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier.size(46.dp)
            .glass(HudButtonShape, accent = if (active) HudCyan else HudLine.copy(alpha = 1f), blur = false, glow = active)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val style = MaterialTheme.typography.labelMedium
        Text(label, style = if (active) style.neon(c) else style, color = c)
    }
}

/** Fila de leyenda: punto de color + texto. */
@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(color)
        HudLabel(text, color = MaterialTheme.colorScheme.onSurface)
    }
}
