package io.github.protohommer.meshpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
val RouteColor = NeonCopper

/** Panel de arriba mientras se navega: destino, lo que falta, medio y estado de la ruta. */
@Composable
fun NavPanel(s: Nav.State, modifier: Modifier = Modifier, onCenter: () -> Unit) {
    val tight = PaddingValues(horizontal = 6.dp)
    HudPanel(modifier.width(300.dp), title = "Ir a", accent = RouteColor, padding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.target.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            TextButton(onClick = onCenter, contentPadding = tight) { Text("VER") }
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in Routing.Mode.entries) {
                FilterChip(selected = s.mode == m, onClick = { if (s.mode != m) Nav.setMode(m) }, label = { Text(m.label) })
            }
        }
        when (val st = s.status) {
            Nav.Status.Routing -> HudLabel("Calculando la ruta…", color = HudCyan)
            Nav.Status.Ok -> HudLabel(
                if (s.offRoute) "Fuera de la ruta: recalculando…"
                else "Por calle/sendero · ${PhoneLocation.formatDistance((s.route?.distanceM ?: 0).toFloat())} en total" +
                    (s.route?.ascendM?.takeIf { it > 20 }?.let { " · sube $it m" } ?: ""),
                color = if (s.offRoute) SigFair else HudTextDim, maxLines = 2,
            )
            is Nav.Status.NeedData -> NeedData(st.tiles)
            is Nav.Status.Downloading -> {
                HudLabel("Bajando rutas de ${st.tile}…")
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is Nav.Status.Failed -> {
                Text(st.message + " · se muestra la línea recta.", style = MaterialTheme.typography.bodySmall, color = SigFair)
                TextButton(onClick = { Nav.retry() }, contentPadding = tight) { Text("REINTENTAR") }
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
    TextButton(onClick = { Nav.download(tiles) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("BAJAR RUTAS") }
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
                    Text("BORRAR", color = MaterialTheme.colorScheme.error)
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
