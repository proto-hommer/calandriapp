package io.github.protohommer.meshpaper.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.R
import io.github.protohommer.meshpaper.ui.theme.BrandCopper
import io.github.protohommer.meshpaper.ui.theme.BrandGreen
import io.github.protohommer.meshpaper.ui.theme.BrandNavy
import io.github.protohommer.meshpaper.ui.theme.BrandText
import io.github.protohommer.meshpaper.ui.theme.NeonCopper
import io.github.protohommer.meshpaper.ui.theme.NeonGreen
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DURATION_MS = 2800

/** Progreso local de una fase [from, to] dentro de la línea de tiempo 0..1. */
private fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)
private fun ease(x: Float) = FastOutSlowInEasing.transform(x)

/**
 * Intro de la app: los nodos llegan sueltos desde afuera, se juntan, se unen con las conexiones
 * y forman la calandria; después aparece el nombre y abajo "powered by Meshtastic®".
 * Un toque la saltea.
 */
@Composable
fun BrandIntro(onDone: () -> Unit) {
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        time.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
        onDone()
    }
    val t = time.value
    val out = 1f - phase(t, 0.9f, 1f) // fundido final hacia la app

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = out }.background(BrandNavy)
            .background(Brush.radialGradient(listOf(NeonCopper.copy(alpha = 0.10f * phase(t, 0.3f, 0.7f)), Color.Transparent)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            val box = BrandGeometry.box
            Canvas(Modifier.fillMaxWidth(0.85f).aspectRatio(box[2] / box[3])) { drawMark(t, size.width / box[2]) }
            Spacer(Modifier.height(36.dp))
            val name = ease(phase(t, 0.55f, 0.75f))
            Box(
                Modifier.fillMaxWidth(0.9f).graphicsLayer {
                    alpha = name
                    translationY = (1f - name) * 24.dp.toPx()
                },
            ) {
                // Brillo de neón: la misma palabra en cobre, desenfocada, detrás.
                Image(
                    painterResource(R.drawable.brand_nombre), contentDescription = null,
                    modifier = Modifier.matchParentSize().blur(10.dp).graphicsLayer { alpha = 0.8f },
                    colorFilter = ColorFilter.tint(NeonCopper),
                )
                Image(painterResource(R.drawable.brand_nombre), contentDescription = "CalandriApp", modifier = Modifier.fillMaxWidth())
            }
        }
        Text(
            "powered by Meshtastic®",
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
                .graphicsLayer { alpha = ease(phase(t, 0.68f, 0.85f)) },
            style = MaterialTheme.typography.labelMedium,
            color = BrandText.copy(alpha = 0.75f),
        )
    }
}

/** Dibuja la marca en el instante [t]; [s] = píxeles por unidad de marca.svg. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMark(t: Float, s: Float) {
    val g = BrandGeometry
    val box = g.box
    val n = g.points.size / 2
    val cx = box[0] + box[2] / 2
    val cy = box[1] + box[3] / 2
    // Cada nodo arranca afuera, en un lugar fijo (ángulo áureo), y llega con un pequeño escalonado.
    val pos = Array(n) { i ->
        val tx = g.points[2 * i]
        val ty = g.points[2 * i + 1]
        val a = i * 2.39996f + 0.6f
        val r = 150f + (i % 4) * 22f
        val sx = cx + cos(a) * r
        val sy = cy + sin(a) * r
        val k = ease(phase(t, 0.02f + i * 0.012f, 0.42f + i * 0.012f))
        Offset((sx + (tx - sx) * k - box[0]) * s, (sy + (ty - sy) * k - box[1]) * s)
    }
    val nodesIn = phase(t, 0f, 0.15f)
    val lines = ease(phase(t, 0.28f, 0.58f))
    val fill = phase(t, 0.5f, 0.7f)

    if (fill > 0f) {
        val p = Path().apply {
            g.outline.forEachIndexed { k, i -> if (k == 0) moveTo(pos[i].x, pos[i].y) else lineTo(pos[i].x, pos[i].y) }
            close()
        }
        drawPath(p, NeonCopper.copy(alpha = g.FILL_ALPHA * fill))
    }
    if (lines > 0f) {
        val stroke = Stroke(g.STROKE * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Las conexiones crecen desde un extremo hasta unirse con el otro. Tres pasadas: dos de
        // halo (anchas y tenues) y el trazo, con el centro casi blanco como un tubo de neón.
        val passes = listOf(
            5.5f to NeonCopper.copy(alpha = 0.10f * lines),
            2.6f to NeonCopper.copy(alpha = 0.28f * lines),
            1f to NeonCopper.copy(alpha = lines),
            0.4f to Color(0xFFFFE2C4).copy(alpha = 0.9f * lines),
        )
        val o = g.outline
        for ((w, color) in passes) {
            fun seg(a: Int, b: Int) {
                val end = Offset(pos[a].x + (pos[b].x - pos[a].x) * lines, pos[a].y + (pos[b].y - pos[a].y) * lines)
                drawLine(color, pos[a], end, stroke.width * w, StrokeCap.Round)
            }
            for (k in o.indices) seg(o[k], o[(k + 1) % o.size])
            for (k in g.edges.indices step 2) seg(g.edges[k], g.edges[k + 1])
        }
    }
    for (i in 0 until n) {
        if (i == g.EYE) continue
        drawCircle(NeonCopper.copy(alpha = 0.18f * nodesIn), g.NODE_R * 2.6f * s, pos[i])
        drawCircle(NeonCopper.copy(alpha = nodesIn), g.NODE_R * s, pos[i])
        drawCircle(Color(0xFFFFE2C4).copy(alpha = nodesIn), g.NODE_R * 0.45f * s, pos[i])
    }
    // El ojo: llega como los demás y al final "se enciende" en verde.
    val eye = pos[g.EYE]
    val glow = ease(phase(t, 0.45f, 0.6f))
    val pop = 1f + 0.35f * sin(PI.toFloat() * glow)
    drawCircle(NeonCopper.copy(alpha = nodesIn * (1f - glow)), g.NODE_R * s, eye)
    if (glow > 0f) {
        // El ojo se enciende: halo verde grande y tenue, el anillo oscuro y el ojo con centro claro.
        drawCircle(NeonGreen.copy(alpha = 0.16f * glow), g.EYE_R * 3.2f * s * pop, eye)
        drawCircle(NeonGreen.copy(alpha = 0.30f * glow), g.EYE_R * 1.8f * s * pop, eye)
        drawCircle(BrandNavy.copy(alpha = glow), (g.EYE_R + g.EYE_RING / 2) * s * pop, eye)
        drawCircle(NeonGreen.copy(alpha = glow), (g.EYE_R - g.EYE_RING / 2) * s * pop, eye)
        drawCircle(Color(0xFFD8FFE8).copy(alpha = glow), (g.EYE_R - g.EYE_RING / 2) * 0.45f * s * pop, eye)
    }
}
