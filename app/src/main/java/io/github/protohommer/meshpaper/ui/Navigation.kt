package io.github.protohommer.meshpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.map.MapTiles
import io.github.protohommer.meshpaper.map.Nav
import io.github.protohommer.meshpaper.map.Routing
import io.github.protohommer.meshpaper.mesh.PhoneLocation
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.NeonCopper
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import kotlinx.coroutines.launch

/** Color de la ruta en el mapa y del panel de navegación. */
val RouteColor get() = NeonCopper

/** Panel de arriba mientras se navega: destino, lo que falta, medio y estado de la ruta. */
@Composable
fun NavPanel(
    s: Nav.State, modifier: Modifier = Modifier, onCenter: () -> Unit,
    /** "Guardar el camino": baja relieve (y mapa, si la fuente deja) de los cuadrantes de la ruta. */
    onSave: (() -> Unit)? = null, saving: String? = null,
) {
    val tight = PaddingValues(horizontal = 6.dp)
    HudPanel(modifier.width(300.dp), title = "Ir a", accent = RouteColor, padding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.target.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            TextButton(onClick = onCenter, contentPadding = tight) { Text("Ver") }
            TextButton(onClick = { Nav.stop() }, contentPadding = tight) { Text("✕") }
        }
        if (s.status == Nav.Status.Arrived) {
            HudValue("Llegaste", color = SigGood)
            HudLabel("a ${PhoneLocation.formatDistance((s.straightM ?: 0.0).toFloat())} del destino")
            return@HudPanel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val routed = s.route != null
            HudStat(if (routed) "Falta" else "En recta", s.remainingM?.let { PhoneLocation.formatDistance(it.toFloat()) } ?: "—", color = RouteColor)
            HudStat("Tiempo", s.remainingS?.let { "≈ " + Routing.formatTime(it) } ?: "—")
            HudStat("Rumbo", s.bearing?.let { "${PhoneLocation.cardinal(it)} ${it.toInt()}°" } ?: "—")
        }
        // Como "Iniciar" de Google Maps: el mapa sigue al teléfono, con el próximo giro arriba.
        HudButton(onClick = { Nav.setGuided(true) }, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("Iniciar navegación") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in Routing.Mode.entries) {
                FilterChip(selected = s.mode == m, onClick = { if (s.mode != m) Nav.setMode(m) }, label = { Text(m.label) })
            }
        }
        when (val st = s.status) {
            Nav.Status.Routing -> HudLabel("Calculando la ruta…", color = HudCyan)
            Nav.Status.Ok -> {
                HudLabel(
                    if (s.offRoute) "Fuera de la ruta: recalculando…"
                    else "Por calle/sendero · ${PhoneLocation.formatDistance((s.route?.distanceM ?: 0).toFloat())} en total" +
                        (s.route?.ascendM?.takeIf { it > 20 }?.let { " · sube $it m" } ?: ""),
                    color = if (s.offRoute) SigFair else HudTextDim, maxLines = 2,
                )
                if (saving != null) Text(saving, style = MaterialTheme.typography.bodySmall, color = HudTextDim)
                val busyOrDone = saving?.let { it.startsWith("Guardando") || it.startsWith("Camino guardado") } == true
                if (onSave != null && !busyOrDone) TextButton(onClick = onSave, contentPadding = tight) { Text("Guardar el camino") }
            }
            is Nav.Status.NeedData -> NeedData(st.tiles)
            is Nav.Status.Downloading -> {
                HudLabel("Bajando rutas de ${st.tile}…")
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is Nav.Status.Failed -> {
                Text(st.message + " · se muestra la línea recta.", style = MaterialTheme.typography.bodySmall, color = SigFair)
                TextButton(onClick = { Nav.retry() }, contentPadding = tight) { Text("Reintentar") }
            }
            Nav.Status.Arrived -> Unit
        }
    }
}

@Composable
private fun NeedData(tiles: List<String>) {
    val ctx = LocalContext.current
    var size by remember(tiles) { mutableStateOf<Long?>(null) }
    LaunchedEffect(tiles) {
        if (MapTiles.useInternet(ctx)) size = tiles.mapNotNull { Routing.remoteSize(it) }.takeIf { it.isNotEmpty() }?.sum()
    }
    Text(
        "Faltan los datos de rutas de esta zona (${tiles.joinToString()}" + (size?.let { " · ${MapTiles.formatSize(it)}" } ?: "") +
            "). Se bajan una vez y después andan sin señal. Mientras, la línea recta.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { Nav.download(tiles) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Bajar rutas") }
}

/** Ajustes → Rutas sin conexión: qué zonas hay, bajar la de donde estás, borrar. */
@Composable
fun RoutesPanel(here: Pair<Double, Double>?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stored by remember { mutableStateOf<List<Routing.Stored>>(Routing.stored(ctx)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    val hereTile = here?.let { Routing.tileName(it.first, it.second) }
    HudPanel(modifier.fillMaxWidth(), title = "Rutas sin conexión") {
        Text(
            "Para \"Ir a\" un nodo o waypoint por calles y senderos. Cada zona cubre un cuadro de 5° " +
                "(Córdoba y alrededores, unos 11 MB) y se baja una sola vez. Datos de OpenStreetMap vía BRouter.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        if (stored.isEmpty()) HudLabel("Ninguna zona guardada")
        for (z in stored) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudLabel("${z.tile} · ${MapTiles.formatSize(z.bytes)}" + if (z.tile == hereTile) " · acá" else "", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { Routing.delete(ctx, z.tile); stored = Routing.stored(ctx) }, enabled = busy == null) {
                    Text("Borrar", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        busy?.let {
            HudLabel("Bajando $it…")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SigFair) }
        if (hereTile != null && stored.none { it.tile == hereTile }) {
            HudButton(onClick = {
                busy = hereTile; progress = 0f; error = null
                scope.launch {
                    try {
                        Routing.download(ctx, hereTile) { p -> scope.launch { progress = p } }
                    } catch (e: Exception) {
                        error = "No se pudo bajar: ${e.message ?: e.javaClass.simpleName}"
                    }
                    busy = null; stored = Routing.stored(ctx)
                }
            }, enabled = busy == null) { Text("BAJAR ZONA DE ACÁ ($hereTile)") }
        } else if (hereTile == null) {
            HudLabel("Sin ubicación del teléfono: \"Ir a\" ofrece bajar la zona cuando haga falta.", maxLines = 2)
        }
    }
}

/** Qué hacer en un giro de BRouter (VoiceHint.cmd): texto y ángulo de la flecha (0 = derecho, + = derecha). */
private fun turnInfo(t: Routing.Turn?): Pair<String, Float> = when (t?.cmd) {
    2 -> "Girá a la izquierda" to -90f
    3 -> "Doblá levemente a la izquierda" to -45f
    4 -> "Girá cerrado a la izquierda" to -135f
    5 -> "Girá a la derecha" to 90f
    6 -> "Doblá levemente a la derecha" to 45f
    7 -> "Girá cerrado a la derecha" to 135f
    8 -> "Mantenete a la izquierda" to -20f
    9 -> "Mantenete a la derecha" to 20f
    10, 11, 15 -> "Pegá la vuelta en U" to 180f
    13, 14 -> (if (t.exit > 0) "En la rotonda, tomá la ${t.exit}ª salida" else "Entrá en la rotonda") to 0f
    16 -> "Seguí en línea recta" to 0f
    17 -> "Salí por la izquierda" to -45f
    18 -> "Salí por la derecha" to 45f
    100 -> "Llegás al destino" to 0f
    else -> "Seguí por el camino" to 0f
}

/** Flecha de giro dibujada (las fuentes del teléfono no traen todas las flechas). */
@Composable
private fun TurnArrow(angle: Float, roundabout: Boolean, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(56.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        if (roundabout) drawCircle(color, radius = w * 0.2f, center = androidx.compose.ui.geometry.Offset(w / 2, h * 0.62f), style = stroke)
        // Tallo desde abajo hasta el centro, y desde ahí el brazo hacia donde hay que ir.
        val c = androidx.compose.ui.geometry.Offset(w / 2, h * 0.55f)
        val rad = Math.toRadians(angle.toDouble())
        val len = w * 0.36f
        val tip = androidx.compose.ui.geometry.Offset(c.x + (len * kotlin.math.sin(rad)).toFloat(), c.y - (len * kotlin.math.cos(rad)).toFloat())
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, h * 0.95f); lineTo(c.x, c.y); lineTo(tip.x, tip.y)
        }
        drawPath(path, color, style = stroke)
        // Punta.
        val head = w * 0.17f
        for (side in listOf(-1, 1)) {
            val a = rad + Math.PI + side * Math.PI / 4
            drawLine(
                color, tip,
                androidx.compose.ui.geometry.Offset(tip.x + (head * kotlin.math.sin(a)).toFloat(), tip.y - (head * kotlin.math.cos(a)).toFloat()),
                strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/** Distancia al giro redondeada como en los navegadores: 50 m, 100 m, 1,2 km. */
private fun turnDistance(m: Double): String = when {
    m < 20 -> "Ahora"
    m < 100 -> "${(m / 10).toInt() * 10} m"
    m < 1000 -> "${(m / 50).toInt() * 50} m"
    else -> PhoneLocation.formatDistance(m.toFloat())
}

/** Navegación guiada: cartel de arriba con el próximo giro. [heading]: hacia dónde mira el mapa. */
@Composable
fun TurnBanner(s: Nav.State, heading: Float?, modifier: Modifier = Modifier) {
    HudPanel(modifier.fillMaxWidth(), accent = RouteColor, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                s.status == Nav.Status.Arrived -> {
                    TurnArrow(0f, roundabout = false, color = SigGood)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Llegaste", style = MaterialTheme.typography.headlineSmall, color = SigGood, fontWeight = FontWeight.SemiBold)
                        HudLabel(s.target.name)
                    }
                }
                s.route == null -> {
                    // Sin ruta (sin datos de la zona o calculando): flecha al destino en línea recta.
                    TurnArrow((s.bearing ?: 0f) - (heading ?: 0f), roundabout = false, color = RouteColor)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            s.straightM?.let { PhoneLocation.formatDistance(it.toFloat()) } ?: "—",
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        )
                        HudLabel("En línea recta al ${s.bearing?.let { PhoneLocation.cardinal(it) } ?: "—"} · ${s.target.name}", maxLines = 2)
                    }
                }
                else -> {
                    val t = s.nextTurn
                    val (text, angle) = if (t == null) "Seguí hasta ${s.target.name}" to 0f else turnInfo(t)
                    TurnArrow(angle, roundabout = t?.cmd == 13 || t?.cmd == 14, color = RouteColor)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            (if (t == null) s.remainingM else s.nextTurnM)?.let { turnDistance(it) } ?: "—",
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        )
                        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        if (s.offRoute) HudLabel("Fuera de la ruta: recalculando…", color = SigFair)
                    }
                }
            }
        }
    }
}

/** Navegación guiada: abajo, lo que falta y la hora de llegada, con volver a centrar, resumen y salir. */
@Composable
fun GuidedBottom(s: Nav.State, following: Boolean, onRecenter: () -> Unit, onOverview: () -> Unit, modifier: Modifier = Modifier) {
    val tight = PaddingValues(horizontal = 8.dp)
    HudPanel(modifier.fillMaxWidth(), accent = RouteColor, padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val secs = s.remainingS
                Text(
                    secs?.let { Routing.formatTime(it) } ?: "—",
                    style = MaterialTheme.typography.titleLarge, color = RouteColor, fontWeight = FontWeight.SemiBold,
                )
                val eta = secs?.let {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(System.currentTimeMillis() + it * 1000L))
                }
                HudLabel(
                    listOfNotNull(s.remainingM?.let { PhoneLocation.formatDistance(it.toFloat()) }, eta?.let { "llega $it" })
                        .joinToString(" · "),
                )
            }
            if (!following) TextButton(onClick = onRecenter, contentPadding = tight) { Text("Seguir", color = HudCyan, softWrap = false) }
            TextButton(onClick = onOverview, contentPadding = tight) { Text("Resumen", softWrap = false) }
            TextButton(onClick = { Nav.stop() }, contentPadding = tight) { Text("Salir", color = MaterialTheme.colorScheme.error, softWrap = false) }
        }
    }
}
