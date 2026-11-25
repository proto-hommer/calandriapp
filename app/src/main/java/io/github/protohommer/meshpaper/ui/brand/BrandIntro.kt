package io.github.protohommer.meshpaper.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.protohommer.meshpaper.R
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DURATION_MS = 4600

/** Escala de animación fija en 1: ignora "duración de animaciones" del sistema. */
private object RealTime : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** Progreso local de una fase [from, to] dentro de la línea de tiempo 0..1. */
private fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

private const val SCAN_FROM = 0.02f
private const val SCAN_TO = 0.36f

/** Instante (0..1) en que la línea de escaneo llega a la altura relativa [y]. */
private fun scanTimeAt(y: Float): Float {
    var lo = 0f
    var hi = 1f
    repeat(18) {
        val mid = (lo + hi) / 2
        if (ease(mid) < y) lo = mid else hi = mid
    }
    return SCAN_FROM + (SCAN_TO - SCAN_FROM) * (lo + hi) / 2
}

private fun ease(x: Float) = FastOutSlowInEasing.transform(x)

// Paleta de la intro (la del fondo "Topografía"): siempre oscura, aunque la app esté en modo día.
// En el tema nocturno: sin neón, la malla en gris, los nodos en naranja y el ojo en cian ("vos").
// 2.0: el estilo nuevo es el único (la intro es siempre oscura, aunque la app esté de día).
private const val nocturne = true
private val BgTop get() = if (nocturne) Color(0xFF07090C) else Color(0xFF060A10)
private val BgBottom get() = if (nocturne) Color(0xFF0A0D11) else Color(0xFF090E16)
private val Contour get() = if (nocturne) Color(0xFF1C232C) else Color(0xFF1C3140)
private val Scan get() = if (nocturne) Color(0xFFD5DAE0) else Color(0xFF3FD6E6)
private val Copper get() = if (nocturne) Color(0xFF8A939D) else Color(0xFFC98245)
private val CopperDim get() = if (nocturne) Color(0xFF3A4450) else Color(0xFF784E2C)
private val NodeOn get() = if (nocturne) Color(0xFFFF7A2F) else Copper
private val Eye get() = if (nocturne) Color(0xFF5CE1E6) else Color(0xFF3CC878)
private val Link get() = if (nocturne) Color(0xFF2A323C) else Color(0xFF2E6E7C)
private val Name get() = if (nocturne) Color(0xFFF1F3F5) else Color(0xFFC8D6DE)
private val Sub get() = if (nocturne) Color(0xFF8A939D) else Color(0xFF5A7887)

private val Barlow = FontFamily(Font(R.font.barlow_regular))

/** Cerros del relieve (x, y relativos a la pantalla, fase de la deformación) y cantidad de curvas. */
private val PEAKS = listOf(
    floatArrayOf(0.26f, 0.25f, 0.4f, 14f),
    floatArrayOf(0.82f, 0.68f, 1.7f, 12f),
    floatArrayOf(0.17f, 0.90f, 2.9f, 8f),
)

/** Dónde aparece cada nodo antes de juntarse (relativo a la pantalla, repartidos por el "mapa"). */
private val STARTS = floatArrayOf(
    0.88f, 0.14f, 0.55f, 0.08f, 0.30f, 0.16f, 0.12f, 0.34f, 0.10f, 0.62f, 0.22f, 0.80f, 0.45f, 0.93f,
    0.70f, 0.86f, 0.92f, 0.74f, 0.86f, 0.44f, 0.66f, 0.27f, 0.38f, 0.58f, 0.60f, 0.55f, 0.40f, 0.33f,
)

/** Cuándo aparece cada nodo: cuando el escaneo pasa por su lugar en el mapa. */
private val APPEAR_AT = FloatArray(STARTS.size / 2) { i -> scanTimeAt(STARTS[2 * i + 1]) }

/**
 * Intro de la app: primero se "escanea" el relieve (curvas de nivel, como un mapa holográfico),
 * después aparecen los nodos repartidos por el terreno, cada uno con su pulso de radio, y viajan
 * al centro enlazándose hasta formar la calandria; el ojo se enciende, abajo el nombre y al pie
 * "powered by Meshtastic®". Un toque la saltea.
 */
@Composable
fun BrandIntro(onDone: () -> Unit) {
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // A velocidad normal aunque el celular tenga las animaciones apagadas (si no, Compose
        // la saltea entera). Igual se puede saltear con un toque.
        withContext(RealTime) { time.animateTo(1f, tween(DURATION_MS, easing = LinearEasing)) }
        onDone()
    }
    val t = time.value
    val out = 1f - phase(t, 0.95f, 1f) // fundido final hacia la app

    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = out }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
    ) {
        val markCenterY = 0.44f
        Canvas(Modifier.fillMaxSize()) { drawIntro(t, markCenterY) }

        // Nombre y bajada, debajo de la marca.
        val name = ease(phase(t, 0.74f, 0.88f))
        Column(
            Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.66f)
                .graphicsLayer { alpha = name; translationY = (1f - name) * 16.dp.toPx() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "CALANDRIAPP",
                style = TextStyle(fontFamily = Barlow, fontSize = 30.sp, letterSpacing = 0.42.em, color = Name, textAlign = TextAlign.Center),
            )
            Text(
                "MESH · LORA · OFFLINE",
                Modifier.padding(top = 10.dp),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.3.em, color = Sub),
            )
        }
        Text(
            "powered by Meshtastic®",
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
                .graphicsLayer { alpha = ease(phase(t, 0.84f, 0.94f)) },
            style = TextStyle(fontFamily = Barlow, fontSize = 12.sp, letterSpacing = 0.08.em, color = Name.copy(alpha = 0.7f)),
        )
    }
}

private fun DrawScope.drawIntro(t: Float, markCenterY: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    if (nocturne) {
        // Grilla fina de 40 dp como textura, como un instrumento de precisión.
        val step = 40.dp.toPx()
        val grid = Color(0xFF10151B)
        var x = (w % step) / 2
        while (x < w) { drawLine(grid, Offset(x, 0f), Offset(x, h), 1f); x += step }
        var y = (h % step) / 2
        while (y < h) { drawLine(grid, Offset(0f, y), Offset(w, y), 1f); y += step }
    }

    // ---------- 1 · El relieve: curvas de nivel que aparecen detrás de una línea de escaneo ----------
    val scan = ease(phase(t, SCAN_FROM, SCAN_TO))
    val scanY = h * scan
    val contours = Stroke(0.7.dp.toPx())
    clipRect(bottom = scanY) {
        for (pk in PEAKS) drawContours(pk, w, h, Contour, contours)
    }
    if (scan in 0.001f..0.999f) {
        // La franja recién escaneada brilla un poco más, como un mapa holográfico que se dibuja.
        val band = 70.dp.toPx()
        clipRect(top = scanY - band, bottom = scanY) {
            for (pk in PEAKS) drawContours(pk, w, h, Scan.copy(alpha = 0.35f), contours)
        }
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Scan.copy(alpha = 0.10f)), startY = scanY - band, endY = scanY),
            topLeft = Offset(0f, scanY - band), size = Size(w, band),
        )
        drawLine(Scan.copy(alpha = 0.18f), Offset(0f, scanY), Offset(w, scanY), 6.dp.toPx())
        drawLine(Scan.copy(alpha = 0.85f), Offset(0f, scanY), Offset(w, scanY), 1.5.dp.toPx())
    }

    // ---------- 2 · Los nodos: aparecen en el terreno, laten y viajan al centro ----------
    val g = BrandGeometry
    val box = g.box
    val n = g.points.size / 2
    val s = w * 0.62f / box[2]
    val ox = w / 2 - (box[0] + box[2] / 2) * s
    val oy = h * markCenterY - (box[1] + box[3] / 2) * s
    // Cada nodo aparece cuando el escaneo pasa por su lugar en el mapa.
    val appearOf = FloatArray(n) { i -> phase(t, APPEAR_AT[i], APPEAR_AT[i] + 0.03f) }
    val pos = Array(n) { i ->
        val tx = ox + g.points[2 * i] * s
        val ty = oy + g.points[2 * i + 1] * s
        val sx = STARTS[2 * i] * w
        val sy = STARTS[2 * i + 1] * h
        val k = ease(phase(t, 0.42f + i * 0.008f, 0.66f + i * 0.008f))
        Offset(sx + (tx - sx) * k, sy + (ty - sy) * k)
    }
    val travel = ease(phase(t, 0.42f, 0.66f))

    // Pulso de radio de cada nodo al aparecer (dos anillos que se abren y se apagan).
    if (travel < 1f) {
        for (i in 0 until n) {
            for (ring in 0..1) {
                val p = phase(t, APPEAR_AT[i] + ring * 0.05f, APPEAR_AT[i] + 0.16f + ring * 0.05f)
                if (p <= 0f || p >= 1f) continue
                drawCircle(Scan.copy(alpha = 0.45f * (1f - p)), 6.dp.toPx() + 30.dp.toPx() * p, pos[i], style = Stroke(1.dp.toPx()))
            }
        }
    }

    // Mientras viajan, la malla ya se enlaza: líneas tenues entre los nodos que van a quedar unidos.
    // Cada enlace se ve cuando ya aparecieron sus dos nodos.
    val linkFade = 0.5f * (1f - ease(phase(t, 0.66f, 0.78f)))
    if (linkFade > 0f) {
        fun link(a: Int, b: Int) {
            val al = linkFade * minOf(appearOf[a], appearOf[b])
            if (al > 0f) drawLine(Link.copy(alpha = al), pos[a], pos[b], 1.dp.toPx())
        }
        val o = g.outline
        for (k in o.indices) link(o[k], o[(k + 1) % o.size])
        for (k in g.edges.indices step 2) link(g.edges[k], g.edges[k + 1])
    }

    // ---------- 3 · La calandria: el trazo de cobre crece por las conexiones ----------
    val lines = ease(phase(t, 0.62f, 0.80f))
    val fill = phase(t, 0.72f, 0.86f)
    if (fill > 0f) {
        val p = Path().apply {
            g.outline.forEachIndexed { k, i -> if (k == 0) moveTo(pos[i].x, pos[i].y) else lineTo(pos[i].x, pos[i].y) }
            close()
        }
        drawPath(p, Copper.copy(alpha = (if (nocturne) 0.04f else 0.07f) * fill))
    }
    if (lines > 0f) {
        val stroke = g.STROKE * s * 0.55f
        fun seg(a: Int, b: Int, color: Color, width: Float) {
            val end = Offset(pos[a].x + (pos[b].x - pos[a].x) * lines, pos[a].y + (pos[b].y - pos[a].y) * lines)
            drawLine(color, pos[a], end, width, StrokeCap.Round)
        }
        for (k in g.edges.indices step 2) seg(g.edges[k], g.edges[k + 1], CopperDim.copy(alpha = lines), stroke * 0.7f)
        val o = g.outline
        for (k in 0 until o.size - 1) {
            if (!nocturne) seg(o[k], o[k + 1], Copper.copy(alpha = 0.12f * lines), stroke * 3.5f) // halo suave
            seg(o[k], o[k + 1], Copper.copy(alpha = lines), stroke)
        }
    }
    // Los nodos (cian mientras viajan, cobre al llegar).
    for (i in 0 until n) {
        if (i == g.EYE && phase(t, 0.72f, 0.84f) > 0f) continue
        val arrived = phase(t, 0.62f + i * 0.008f, 0.70f + i * 0.008f)
        val c = lerp(Scan, NodeOn, arrived)
        if (!nocturne) drawCircle(c.copy(alpha = 0.18f * appearOf[i]), g.NODE_R * s * 1.6f, pos[i])
        drawCircle(c.copy(alpha = appearOf[i]), g.NODE_R * s * 0.62f, pos[i])
    }
    // El ojo: llega como los demás y al final se enciende en verde.
    val eye = pos[g.EYE]
    val glow = ease(phase(t, 0.72f, 0.84f))
    if (glow > 0f) {
        val pop = 1f + 0.25f * sin(PI.toFloat() * glow)
        if (!nocturne) drawCircle(Eye.copy(alpha = 0.14f * glow), g.EYE_R * s * 1.8f * pop, eye)
        drawCircle(Eye.copy(alpha = glow), g.EYE_R * s * 0.62f * pop, eye, style = Stroke(g.EYE_RING * s * 0.5f))
        drawCircle(Eye.copy(alpha = glow), g.EYE_R * s * 0.28f * pop, eye)
    }
}

/** Curvas de nivel de un cerro: anillos deformados (como en el fondo de pantalla "Topografía"). */
private fun DrawScope.drawContours(pk: FloatArray, w: Float, h: Float, color: Color, stroke: Stroke) {
    val px = pk[0] * w
    val py = pk[1] * h
    val ph = pk[2]
    val count = pk[3].toInt()
    val step = w * 0.0486f
    for (k in 1..count) {
        val r = step * k
        val path = Path()
        var a = 0
        while (a <= 360) {
            val rad = Math.toRadians(a.toDouble()).toFloat()
            val rr = r * (1f + 0.10f * sin(3 * rad + ph + k * 0.3f) + 0.05f * sin(5 * rad + ph * 2))
            val x = px + rr * cos(rad)
            val y = py + rr * sin(rad) * 1.15f
            if (a == 0) path.moveTo(x, y) else path.lineTo(x, y)
            a += 4
        }
        drawPath(path, color, style = stroke)
    }
}

private fun lerp(a: Color, b: Color, f: Float) = Color(
    a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, a.alpha + (b.alpha - a.alpha) * f,
)
