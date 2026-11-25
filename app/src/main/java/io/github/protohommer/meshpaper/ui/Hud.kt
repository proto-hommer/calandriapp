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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.protohommer.meshpaper.ui.theme.HudText
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigMe
import io.github.protohommer.meshpaper.ui.theme.SigFixed
import io.github.protohommer.meshpaper.ui.theme.SigMute
import io.github.protohommer.meshpaper.ui.theme.SigPoor
import io.github.protohommer.meshpaper.ui.theme.SigRelay
import io.github.protohommer.meshpaper.ui.theme.SigWeak

val HudButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
val HudPanelShape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)

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
        // De día, vidrio claro: velo blanco translúcido sobre el mapa desenfocado.
        tint = HazeTint(if (Appearance.isDay) HudSurface.copy(alpha = 0.52f) else HudBg.copy(alpha = 0.42f)),
        blurRadius = 18.dp,
        noiseFactor = 0f,
        // Sin desenfoque (Android 11 o anterior, o el mapa moviéndose): un velo que se lea igual.
        fallbackTint = HazeTint(if (Appearance.isDay) HudSurface.copy(alpha = 0.78f) else HudBg.copy(alpha = 0.74f)),
    )

/**
 * Halo de luz alrededor de [shape], como un tubo de neón. Se dibuja por fuera (no recorta).
 * Android 9 o más (antes la GPU no desenfoca trazos): en los viejos no hace nada.
 */
fun Modifier.neonGlow(color: Color, shape: Shape, radius: Dp = 8.dp, width: Dp = 2.dp): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) this else drawBehind {
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
/**
 * Panel de la 2.0: plano, del color de la superficie, con un borde fino y quieto (la línea, o el
 * color de [accent] apenas marcado si no es el acento de la interfaz). Sin vidrio ni neón: se lee
 * igual sobre el mapa y al sol. [blur] y [glow] quedan por compatibilidad y no hacen nada.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.glass(shape: Shape, accent: Color = HudCyan, blur: Boolean = true, glow: Boolean = true): Modifier =
    this
        .clip(shape)
        .background(HudSurface)
        .border(1.dp, if (accent == HudCyan || accent == HudLine) HudLine else accent.copy(alpha = 0.7f), shape)

/** Texto con brillo de neón (sombra difusa del mismo color). */
fun TextStyle.neon(color: Color, radius: Float = 14f): TextStyle =
    copy(shadow = Shadow(color.copy(alpha = 0.85f), Offset.Zero, radius))

/** Los textos de color (acentos y señales) brillan; los grises y el blanco del texto, no. */
private fun glows(color: Color) = !Appearance.isDay && Appearance.neon && color.alpha > 0.5f && color != HudTextDim &&
    color.red + color.green + color.blue < 2.6f && maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) > 0.35f

/** Esquinas tipo visor sobre cualquier cosa. */
fun Modifier.hudCorners(color: Color = if (Appearance.neon) HudCyan.copy(alpha = 0.7f) else HudTextDim.copy(alpha = 0.45f), len: Dp = 14.dp, stroke: Dp = 1.5.dp): Modifier =
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
    padding: PaddingValues = PaddingValues(16.dp),
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .glass(HudPanelShape, accent)
            .padding(padding),
    ) {
        if (title != null) {
            // Con [onToggle], la barra del título minimiza o abre el panel (y muestra "–" / "+").
            Row(
                if (onToggle != null) Modifier.fillMaxWidth().clickable(onClick = onToggle) else Modifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val titleColor = if (accent == HudCyan) HudTextDim else accent
                HudLabel(title, if (onToggle != null) Modifier.weight(1f) else Modifier, color = titleColor)
                if (onToggle != null) {
                    val tight = PaddingValues(horizontal = 10.dp)
                    TextButton(onClick = onToggle, contentPadding = tight, modifier = Modifier.height(36.dp)) {
                        Chevron(up = collapsed, color = HudText)
                    }
                    if (collapsed && onClose != null) {
                        TextButton(onClick = onClose, contentPadding = tight, modifier = Modifier.height(36.dp)) { Text("✕", color = HudText) }
                    }
                }
            }
            if (collapsed) return@Column
            Spacer(Modifier.height(if (onToggle != null) 4.dp else 10.dp))
        }
        content()
    }
}

/** Flechita para abrir (arriba) o minimizar (abajo) un panel. */
@Composable
fun Chevron(up: Boolean, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val y0 = if (up) h * 0.68f else h * 0.32f
        val y1 = if (up) h * 0.32f else h * 0.68f
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.15f, y0); lineTo(w / 2, y1); lineTo(w * 0.85f, y0) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
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
    Text(text, modifier, style = io.github.protohommer.meshpaper.ui.theme.MonoValue, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Box(
        Modifier.size(size)
            .then(if (Appearance.neon) Modifier.neonGlow(color, CircleShape, 4.dp, size / 2) else Modifier)
            .background(color, CircleShape)
    )
}

/** Barras de señal por saltos (tema nocturno): 3 = directo o 1 salto, 2 = 2-3, 1 = 4-5, 0 = 6 o más. */
fun signalBars(t: io.github.protohommer.meshpaper.mesh.Tone): Int = when (t) {
    io.github.protohommer.meshpaper.mesh.Tone.GOOD -> 3
    io.github.protohommer.meshpaper.mesh.Tone.FAIR -> 2
    io.github.protohommer.meshpaper.mesh.Tone.POOR -> 1
    else -> 0
}

/**
 * La señal como barras, en lugar del color del punto: se lee igual al sol y para quien no distingue
 * colores. Perdido: un anillo cortado en naranja.
 */
@Composable
fun SignalBars(bars: Int, lost: Boolean, modifier: Modifier = Modifier) {
    val on = HudText
    val off = HudLine
    val warn = SigWeak
    androidx.compose.foundation.Canvas(modifier.size(width = 16.dp, height = 12.dp)) {
        if (lost) {
            drawCircle(
                warn, radius = size.height / 2 - 1.dp.toPx(), center = Offset(size.width / 2, size.height / 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    1.6.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                ),
            )
            return@Canvas
        }
        val w = 3.5.dp.toPx()
        val gap = 2.5.dp.toPx()
        for (i in 0 until 3) {
            val h = size.height * (0.45f + 0.275f * i)
            drawRect(if (i < bars) on else off, topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h))
        }
    }
}

/** Encabezado de pantalla: volver + título. */
@Composable
fun HudHeader(title: String, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) {
            Chevron(up = false, color = HudText, modifier = Modifier.graphicsLayerRotate(90f))
        }
        Text(
            title, Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp),
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = HudText,
            contentColor = HudBg,
            disabledContainerColor = HudLine,
            disabledContentColor = HudTextDim,
        ),
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
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = HudButtonShape,
        border = BorderStroke(1.dp, io.github.protohommer.meshpaper.ui.theme.HudControl),
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
        Modifier.size(48.dp)
            .clip(HudButtonShape)
            .background(if (active) HudText else HudSurface)
            .border(1.dp, if (active) HudText else HudLine, HudButtonShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = when {
            !enabled -> HudLine
            active -> HudBg
            else -> HudText
        }
        val me = io.github.protohommer.meshpaper.ui.theme.SigMe
        when (label) {
            "+", "−" -> androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                val s = 1.9.dp.toPx()
                drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), s, StrokeCap.Round)
                if (label == "+") drawLine(ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), s, StrokeCap.Round)
            }
            "YO" -> androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                val col = if (enabled) me else HudLine
                val s = 1.8.dp.toPx()
                val m = size.width / 2
                drawCircle(col, size.width * 0.27f, style = androidx.compose.ui.graphics.drawscope.Stroke(s))
                drawCircle(col, size.width * 0.09f)
                drawLine(col, Offset(m, 0f), Offset(m, size.height * 0.16f), s)
                drawLine(col, Offset(m, size.height * 0.84f), Offset(m, size.height), s)
                drawLine(col, Offset(0f, m), Offset(size.width * 0.16f, m), s)
                drawLine(col, Offset(size.width * 0.84f, m), Offset(size.width, m), s)
            }
            "CAPA" -> androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                val st = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)
                val w = size.width
                val h = size.height
                val top = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, h * 0.14f); lineTo(w * 0.92f, h * 0.36f); lineTo(w / 2, h * 0.58f); lineTo(w * 0.08f, h * 0.36f); close() }
                val low = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.08f, h * 0.55f); lineTo(w / 2, h * 0.78f); lineTo(w * 0.92f, h * 0.55f) }
                drawPath(top, ink, style = st)
                drawPath(low, ink, style = st)
            }
            else -> Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
        }
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

/** Rota el contenido (la flecha de "volver" es el chevron girado). */
fun Modifier.graphicsLayerRotate(degrees: Float): Modifier = this.graphicsLayer { rotationZ = degrees }
