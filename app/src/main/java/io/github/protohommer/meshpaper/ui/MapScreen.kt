package io.github.protohommer.meshpaper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.protohommer.meshpaper.MeshNode
import io.github.protohommer.meshpaper.MeshViewModel
import io.github.protohommer.meshpaper.isGroupMember
import io.github.protohommer.meshpaper.map.MapTiles
import io.github.protohommer.meshpaper.mesh.PhoneLocation
import io.github.protohommer.meshpaper.mesh.Wpt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Mapa con los nodos del grupo, su rastro y los waypoints. Funciona sin señal con los tiles que
 * ya se miraron (caché) y con los `.mbtiles` importados.
 */
@Composable
fun MapScreen(vm: MeshViewModel, onBack: () -> Unit, onMessage: (Int) -> Unit) {
    val context = LocalContext.current
    val nodes by vm.nodes.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val channels by vm.channels.collectAsState()
    val since by vm.groupSince.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val waypoints by vm.waypoints.collectAsState()
    val fix by vm.myFix.collectAsState()
    val hasGroup = remember(channels) { vm.hasPrivateGroup() }
    val colors = MaterialTheme.colorScheme

    var showOthers by remember { mutableStateOf(!hasGroup) }
    var selectedNode by remember { mutableStateOf<Int?>(null) }
    var selectedWpt by remember { mutableStateOf<Int?>(null) }
    var newWptAt by remember { mutableStateOf<GeoPoint?>(null) }
    var showOffline by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(true) }

    val map = remember {
        MapTiles.init(context)
        MapView(context).apply {
            setTileProvider(MapTiles.newProvider(context))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            restoreCamera(context, this, fix?.let { GeoPoint(it.latitude, it.longitude) })
        }
    }

    // Ciclo de vida del MapView (hilos de tiles) y guardar la cámara al salir.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> { map.onPause(); saveCamera(context, map) }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            saveCamera(context, map)
            map.onPause()
            map.onDetach()
        }
    }

    val visibleNodes = nodes.values.filter { n ->
        n.lat != null && n.lon != null &&
            (showOthers || isGroupMember(n, me, hasGroup, since))
    }
    val now = System.currentTimeMillis() / 1000
    val activeWpts = waypoints.values.filter { it.isActive(now) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Volver") }
            FilterChip(selected = showOthers, onClick = { showOthers = !showOthers }, label = { Text("Repetidores") })
            FilterChip(selected = showTracks, onClick = { showTracks = !showTracks }, label = { Text("Rastros") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showOffline = true }) { Text("Offline") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { map },
                modifier = Modifier.fillMaxSize(),
                update = { mv ->
                    mv.overlays.clear()
                    mv.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            selectedNode = null; selectedWpt = null
                            return false
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null) newWptAt = p
                            return true
                        }
                    }))
                    if (showTracks) for (n in visibleNodes + listOfNotNull(me?.let { nodes[it] })) {
                        val pts = tracks[n.num] ?: continue
                        if (pts.size < 2) continue
                        mv.overlays.add(Polyline(mv).apply {
                            setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                            outlinePaint.color = trackColor(n.num, me, colors.primary.toArgb())
                            outlinePaint.strokeWidth = 6f
                            outlinePaint.alpha = 170
                            infoWindow = null
                            setOnClickListener { _, _, _ -> selectedNode = n.num; true }
                        })
                    }
                    for (w in activeWpts) {
                        mv.overlays.add(Marker(mv).apply {
                            position = GeoPoint(w.lat, w.lon)
                            icon = emojiIcon(mv.context, w.iconText)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            infoWindow = null
                            setOnMarkerClickListener { _, _ -> selectedWpt = w.id; selectedNode = null; true }
                        })
                    }
                    for (n in visibleNodes) {
                        if (n.num == me && fix != null) continue // el propio se dibuja con el GPS
                        val member = isGroupMember(n, me, hasGroup, since)
                        mv.overlays.add(Marker(mv).apply {
                            position = GeoPoint(n.lat!!, n.lon!!)
                            icon = nodeIcon(
                                mv.context, n.shortName.ifEmpty { n.id.takeLast(4) },
                                when {
                                    n.num == me -> AColor.rgb(25, 118, 210)
                                    member -> colors.primary.toArgb()
                                    else -> AColor.GRAY
                                },
                                stale = now - n.lastHeard > STALE_S,
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            infoWindow = null
                            setOnMarkerClickListener { _, _ -> selectedNode = n.num; selectedWpt = null; true }
                        })
                    }
                    fix?.let { f ->
                        mv.overlays.add(Marker(mv).apply {
                            position = GeoPoint(f.latitude, f.longitude)
                            icon = meIcon(mv.context)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            infoWindow = null
                            setOnMarkerClickListener { _, _ -> me?.let { selectedNode = it }; true }
                        })
                    }
                    mv.invalidate()
                },
            )
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
                if (visibleNodes.isNotEmpty()) {
                    OutlinedButton(onClick = { fitAll(map, visibleNodes, fix) }) { Text("Ver todos") }
                    Spacer(Modifier.padding(2.dp))
                }
                Button(onClick = {
                    fix?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude), max(map.zoomLevelDouble, 16.0), 600L) }
                }, enabled = fix != null) { Text("Centrar en mí") }
            }
            if (selectedNode == null && selectedWpt == null) {
                Text(
                    "Mantené apretado el mapa para marcar un waypoint.",
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface.copy(alpha = 0.7f),
                )
            }
            selectedNode?.let { num -> nodes[num] }?.let { n ->
                NodeCard(vm, n, me, fix, Modifier.align(Alignment.BottomStart), onMessage,
                    onClose = { selectedNode = null })
            }
            selectedWpt?.let { waypoints[it] }?.let { w ->
                WaypointCard(vm, w, nodes, fix, Modifier.align(Alignment.BottomStart), onClose = { selectedWpt = null })
            }
        }
    }

    newWptAt?.let { at ->
        NewWaypointDialog(vm, at, onDone = { newWptAt = null })
    }
    if (showOffline) {
        OfflineDialog(map, onClose = { showOffline = false })
    }
}

private const val STALE_S = 2 * 3600L

@Composable
private fun NodeCard(
    vm: MeshViewModel, n: MeshNode, me: Int?, fix: android.location.Location?, modifier: Modifier,
    onMessage: (Int) -> Unit, onClose: () -> Unit,
) {
    val tracks by vm.tracks.collectAsState()
    val now = System.currentTimeMillis() / 1000
    Surface(modifier.padding(12.dp).width(260.dp), tonalElevation = 6.dp, shadowElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Text("${n.shortName.ifEmpty { "?" }}  ${n.longName.ifEmpty { n.id }}", fontWeight = FontWeight.Bold)
            val parts = buildList {
                if (n.num == me) add("este nodo") else add(agoText(now, n.lastHeard))
                n.hopsAway?.let { add(if (it == 0) "directo" else "$it saltos") }
                n.battery?.let { add(if (it > 100) "enchufado" else "bat $it%") }
                if (fix != null && n.num != me && n.lat != null && n.lon != null) {
                    val (d, b) = PhoneLocation.distanceAndBearing(fix.latitude, fix.longitude, n.lat, n.lon)
                    add("a ${PhoneLocation.formatDistance(d)} ${PhoneLocation.cardinal(b)}")
                }
                tracks[n.num]?.size?.takeIf { it > 1 }?.let { add("rastro: $it puntos") }
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Row {
                if (n.num != me) TextButton(onClick = { onMessage(n.num) }) { Text("Mensaje") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
        }
    }
}

@Composable
private fun WaypointCard(
    vm: MeshViewModel, w: Wpt, nodes: Map<Int, MeshNode>, fix: android.location.Location?,
    modifier: Modifier, onClose: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000
    Surface(modifier.padding(12.dp).width(280.dp), tonalElevation = 6.dp, shadowElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Text("${w.iconText}  ${w.name}", fontWeight = FontWeight.Bold)
            if (w.description.isNotEmpty()) Text(w.description, style = MaterialTheme.typography.bodyMedium)
            val parts = buildList {
                val by = nodes[w.from]?.let { it.shortName.ifEmpty { it.id } } ?: if (w.from != 0) "!%08x".format(w.from) else null
                by?.let { add("de $it") }
                add(if (w.expire == 0L) "no vence" else "vence ${agoText(w.expire, now).removePrefix("hace ").let { "en $it" }}")
                if (fix != null) {
                    val (d, b) = PhoneLocation.distanceAndBearing(fix.latitude, fix.longitude, w.lat, w.lon)
                    add("a ${PhoneLocation.formatDistance(d)} ${PhoneLocation.cardinal(b)}")
                }
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text("%.5f, %.5f".format(w.lat, w.lon), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { confirm = true }) {
                    Text(if (vm.canDeleteForAll(w)) "Borrar" else "Ocultar", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
        }
    }
    if (confirm) {
        val forAll = vm.canDeleteForAll(w)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (forAll) "¿Borrar el waypoint?" else "¿Ocultar el waypoint?") },
            text = {
                Text(
                    if (forAll) "Se borra en todos los nodos que lo reciban."
                    else "Lo creó otro nodo: solo se saca de este teléfono."
                )
            },
            confirmButton = { TextButton(onClick = { vm.deleteWaypoint(w); confirm = false; onClose() }) { Text("Sí") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("No") } },
        )
    }
}

private val ICONS = listOf(0x1F4CD, 0x26FA, 0x1F697, 0x1F6B0, 0x26A0, 0x1F3C1, 0x1F6A9, 0x2B50)
private val EXPIRES = listOf("No vence" to 0L, "1 hora" to 3600L, "1 día" to 86_400L, "1 semana" to 604_800L)

@Composable
private fun NewWaypointDialog(vm: MeshViewModel, at: GeoPoint, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var icon by remember { mutableIntStateOf(Wpt.DEFAULT_ICON) }
    var expire by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Nuevo waypoint") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("%.5f, %.5f".format(at.latitude, at.longitude), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = name, singleLine = true, label = { Text("Nombre") },
                    onValueChange = { name = Wpt.fitBytes(it, Wpt.MAX_NAME) },
                )
                OutlinedTextField(
                    value = desc, label = { Text("Descripción (opcional)") },
                    onValueChange = { desc = Wpt.fitBytes(it, Wpt.MAX_DESCRIPTION) },
                )
                Spacer(Modifier.padding(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (cp in ICONS) {
                        val t = String(Character.toChars(cp))
                        FilterChip(selected = icon == cp, onClick = { icon = cp }, label = { Text(t) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EXPIRES.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = expire == i, onClick = { expire = i }, label = { Text(label) })
                    }
                }
                Text(
                    "Sale por el canal del grupo. Solo vos lo podés borrar.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val secs = EXPIRES[expire].second
                val exp = if (secs == 0L) 0L else System.currentTimeMillis() / 1000 + secs
                error = vm.sendWaypoint(at.latitude, at.longitude, name, desc, icon, exp)
                if (error == null) onDone()
            }) { Text("Mandar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } },
    )
}

// ---------- Mapas offline ----------

private sealed interface DlState {
    data object Idle : DlState
    data class Running(val done: Int, val total: Int) : DlState
    data class Finished(val text: String) : DlState
}

@Composable
private fun OfflineDialog(map: MapView, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(MapTiles.archives(context)) }
    var online by remember { mutableStateOf(MapTiles.useInternet(context)) }
    var url by remember { mutableStateOf(MapTiles.customUrl(context)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var dl by remember { mutableStateOf<DlState>(DlState.Idle) }
    var cacheBytes by remember { mutableStateOf(cacheSize(context)) }

    fun reload() {
        map.setTileProvider(MapTiles.newProvider(context))
        files = MapTiles.archives(context)
        cacheBytes = cacheSize(context)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; msg = null
        scope.launch {
            val err = withContext(Dispatchers.IO) { MapTiles.import(context, uri) }
            busy = false
            msg = err?.let { "No se pudo importar: $it" } ?: "Mapa importado."
            reload()
        }
    }

    val zoom = map.zoomLevelDouble.toInt().coerceIn(2, 19)
    var maxZoom by remember { mutableIntStateOf(min(zoom + 3, 17)) }
    val source = map.tileProvider.tileSource
    val canBulk = MapTiles.canBulkDownload(source)
    val box: BoundingBox = map.boundingBox
    val tileCount = remember(box, zoom, maxZoom) {
        runCatching { CacheManager(map).possibleTilesInArea(box, zoom, max(zoom, maxZoom)) }.getOrDefault(0)
    }

    AlertDialog(
        onDismissRequest = { if (dl !is DlState.Running) onClose() },
        title = { Text("Mapas offline") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Usar internet")
                        Text(
                            if (online) "Baja lo que mirás y lo guarda" else "Solo caché y archivos importados",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = online, onCheckedChange = {
                        online = it
                        MapTiles.setUseInternet(context, it)
                        map.setUseDataConnection(it)
                    })
                }
                Text("Caché: ${MapTiles.formatSize(cacheBytes)}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Archivos .mbtiles", fontWeight = FontWeight.Bold)
                Text(
                    "Armalos en la PC (QGIS, MOBAC, mbutil…) y pasalos al teléfono.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (files.isEmpty()) Text("Ninguno todavía.", style = MaterialTheme.typography.bodySmall)
                for (f in files) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name} (${MapTiles.formatSize(f.length())})", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { MapTiles.delete(f); reload() }) {
                            Text("Quitar", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Importar .mbtiles") }
                    if (busy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.padding(4.dp)) }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Servidor de tiles", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = url, singleLine = true,
                    onValueChange = { url = it },
                    label = { Text("Vacío = OpenStreetMap") },
                    placeholder = { Text("https://…/{z}/{x}/{y}.png") },
                )
                val urlOk = url.isBlank() || MapTiles.isValidTemplate(url)
                if (!urlOk) Text("Tiene que llevar {z}, {x} y {y}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = { MapTiles.setCustomUrl(context, url); reload() },
                    enabled = urlOk && url.trim() != MapTiles.customUrl(context),
                ) { Text("Usar este servidor") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Descargar la zona visible", fontWeight = FontWeight.Bold)
                if (!canBulk) {
                    Text(
                        "OpenStreetMap no permite bajar zonas enteras. Con OSM se guarda lo que vas " +
                            "mirando (recorré la zona antes de salir), o importá un .mbtiles. " +
                            "Para descargar zonas, poné un servidor propio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Zoom $zoom a $maxZoom", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (z in (zoom..min(zoom + 5, 19))) {
                            FilterChip(selected = maxZoom == z, onClick = { maxZoom = z }, label = { Text("$z") })
                        }
                    }
                    val tooMany = tileCount > MapTiles.MAX_DOWNLOAD_TILES
                    Text(
                        "$tileCount tiles" + if (tooMany) " — demasiados (máx. ${MapTiles.MAX_DOWNLOAD_TILES}), achicá la zona o el zoom" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tooMany) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    when (val d = dl) {
                        is DlState.Running -> {
                            LinearProgressIndicator(
                                progress = { if (d.total > 0) d.done / d.total.toFloat() else 0f },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                            Text("${d.done} / ${d.total}", style = MaterialTheme.typography.bodySmall)
                        }
                        is DlState.Finished -> Text(d.text, style = MaterialTheme.typography.bodySmall)
                        DlState.Idle -> Unit
                    }
                    Button(
                        onClick = {
                            val mgr = CacheManager(map)
                            dl = DlState.Running(0, tileCount)
                            mgr.downloadAreaAsyncNoUI(context, box, zoom, maxZoom, object : CacheManager.CacheManagerCallback {
                                override fun onTaskComplete() { dl = DlState.Finished("Zona guardada."); cacheBytes = cacheSize(context) }
                                override fun onTaskFailed(errors: Int) { dl = DlState.Finished("Terminó con $errors tiles fallidos."); cacheBytes = cacheSize(context) }
                                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                    dl = DlState.Running(progress, (dl as? DlState.Running)?.total ?: tileCount)
                                }
                                override fun downloadStarted() = Unit
                                override fun setPossibleTilesInArea(total: Int) { dl = DlState.Running(0, total) }
                            })
                        },
                        enabled = online && !tooMany && tileCount > 0 && dl !is DlState.Running,
                    ) { Text("Descargar") }
                    if (!online) Text("Prendé \"Usar internet\" para descargar.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, enabled = dl !is DlState.Running) { Text("Listo") }
        },
    )
}

private fun cacheSize(ctx: Context): Long =
    File(MapTiles.baseDir(ctx), "tiles").walkTopDown().filter { it.isFile }.sumOf { it.length() }

// ---------- Cámara ----------

private fun restoreCamera(ctx: Context, mv: MapView, fallback: GeoPoint?) {
    val p = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)
    if (p.contains("lat")) {
        mv.controller.setZoom(p.getFloat("zoom", 15f).toDouble())
        mv.controller.setCenter(GeoPoint(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble()))
    } else if (fallback != null) {
        mv.controller.setZoom(15.0)
        mv.controller.setCenter(fallback)
    } else {
        // Sin nada: Argentina.
        mv.controller.setZoom(4.0)
        mv.controller.setCenter(GeoPoint(-38.4, -63.6))
    }
}

private fun saveCamera(ctx: Context, mv: MapView) {
    val c = mv.mapCenter
    ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit()
        .putFloat("lat", c.latitude.toFloat())
        .putFloat("lon", c.longitude.toFloat())
        .putFloat("zoom", mv.zoomLevelDouble.toFloat())
        .apply()
}

private fun fitAll(mv: MapView, nodes: List<MeshNode>, fix: android.location.Location?) {
    val pts = nodes.mapNotNull { n -> if (n.lat != null && n.lon != null) GeoPoint(n.lat, n.lon) else null } +
        listOfNotNull(fix?.let { GeoPoint(it.latitude, it.longitude) })
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        mv.controller.animateTo(pts[0], max(mv.zoomLevelDouble, 15.0), 600L)
        return
    }
    val bb = BoundingBox.fromGeoPointsSafe(pts)
    mv.zoomToBoundingBox(bb.increaseByScale(1.3f), true, 64)
}

// ---------- Íconos ----------

private val iconCache = HashMap<String, BitmapDrawable>()

private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

/** Círculo con el nombre corto del nodo. */
private fun nodeIcon(ctx: Context, label: String, color: Int, stale: Boolean): BitmapDrawable =
    iconCache.getOrPut("n:$label:$color:$stale") {
        val size = dp(ctx, 34f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = if (stale) 120 else 255 }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AColor.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(ctx, 2.5f) }
        c.drawCircle(r, r, r - dp(ctx, 2f), fill)
        c.drawCircle(r, r, r - dp(ctx, 2f), ring)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = AColor.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = dp(ctx, if (label.length <= 2) 14f else 10.5f)
        }
        c.drawText(label.take(4), r, r - (text.descent() + text.ascent()) / 2, text)
        BitmapDrawable(ctx.resources, bmp)
    }

/** Punto azul del teléfono. */
private fun meIcon(ctx: Context): BitmapDrawable = iconCache.getOrPut("me") {
    val size = dp(ctx, 22f).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE })
    c.drawCircle(r, r, r - dp(ctx, 3f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(25, 118, 210) })
    BitmapDrawable(ctx.resources, bmp)
}

/** Emoji del waypoint sobre un pin chico. */
private fun emojiIcon(ctx: Context, emoji: String): BitmapDrawable = iconCache.getOrPut("w:$emoji") {
    val size = dp(ctx, 36f).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(ctx, 26f); textAlign = Paint.Align.CENTER }
    c.drawText(emoji, size / 2f, size - p.descent(), p)
    BitmapDrawable(ctx.resources, bmp)
}

/** Un color por nodo para distinguir rastros; el propio, azul. */
private fun trackColor(num: Int, me: Int?, primary: Int): Int {
    if (num == me) return AColor.rgb(25, 118, 210)
    val palette = intArrayOf(primary, AColor.rgb(216, 67, 21), AColor.rgb(46, 125, 50),
        AColor.rgb(123, 31, 162), AColor.rgb(0, 131, 143), AColor.rgb(249, 168, 37))
    return palette[(num.toLong() and 0xffffffffL).rem(palette.size).toInt()]
}

private fun agoText(now: Long, t: Long): String {
    if (t == 0L) return "nunca oído"
    val s = (now - t).coerceAtLeast(0)
    return when {
        s < 60 -> "hace ${s}s"
        s < 3600 -> "hace ${s / 60} min"
        s < 86400 -> "hace ${s / 3600} h"
        else -> "hace ${s / 86400} d"
    }
}
