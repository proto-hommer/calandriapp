package io.github.protohommer.meshpaper.ui

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.mesh.Tone
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
            .background(HudSurface.copy(alpha = 0.92f), HudPanelShape)
            .border(1.dp, HudLine, HudPanelShape)
            .padding(padding),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 12.dp).background(accent))
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
    Text(
        text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Dato destacado monoespaciado. */
@Composable
fun HudValue(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Box(Modifier.size(size).background(color, CircleShape))
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
        onClick = onClick, modifier = modifier, enabled = enabled, shape = HudButtonShape,
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
            .background(HudSurface.copy(alpha = 0.9f), HudButtonShape)
            .border(1.dp, if (active) HudCyan else HudLine, HudButtonShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c)
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
