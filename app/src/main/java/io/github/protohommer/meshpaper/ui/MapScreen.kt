package io.github.protohommer.meshpaper.ui

import io.github.protohommer.meshpaper.ui.theme.HudBg
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.unit.Dp
import io.github.protohommer.meshpaper.ui.theme.SigPoor
import java.util.Locale
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.osmdroid.views.overlay.Polygon
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigFixed
import io.github.protohommer.meshpaper.mesh.Range
import io.github.protohommer.meshpaper.mesh.Antenna
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import android.view.MotionEvent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import io.github.protohommer.meshpaper.mesh.Signal
import io.github.protohommer.meshpaper.mesh.Tone
import io.github.protohommer.meshpaper.mesh.Wpt
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigRelay
import io.github.protohommer.meshpaper.ui.theme.SigWeak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File
import io.github.protohommer.meshpaper.map.Dem
import io.github.protohommer.meshpaper.mesh.Profile
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pantalla principal: mapa con los nodos (color según grupo y señal), rastros y waypoints.
 * Anda sin señal con la caché de tiles y los `.mbtiles` importados.
 */
@Composable
fun MapScreen(vm: MeshViewModel, onMessage: (Int) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val nodes by vm.nodes.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val channels by vm.channels.collectAsState()
    val since by vm.groupSince.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val waypoints by vm.waypoints.collectAsState()
    val fix by vm.myFix.collectAsState()
    val hasGroup = remember(channels) { vm.hasPrivateGroup() }

    var showOthers by remember { mutableStateOf(true) }
    var showTracks by remember { mutableStateOf(true) }
    val mapPrefs = remember { context.getSharedPreferences("map", Context.MODE_PRIVATE) }
    var showLegend by remember { mutableStateOf(mapPrefs.getBoolean("legend", true)) }
    var selectedNode by remember { mutableStateOf<Int?>(null) }
    var stack by remember { mutableStateOf<List<Int>>(emptyList()) } // nodos encimados con el elegido
    var selectedWpt by remember { mutableStateOf<Int?>(null) }
    var newWptAt by remember { mutableStateOf<GeoPoint?>(null) }
    var showLayers by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(MapTiles.darkMap(context)) }
    val antennas by vm.antennas.collectAsState()
    val lora by vm.lora.collectAsState()
    val preset = lora?.modemPreset ?: ModemPreset.LONG_FAST
    var rangeNode by remember { mutableStateOf<Int?>(null) }   // nodo con el alcance dibujado
    var antennaFor by remember { mutableStateOf<Int?>(null) }  // nodo al que se le cargan los datos de antena
    var profileFor by remember { mutableStateOf<Int?>(null) }  // nodo con el perfil de terreno abierto

    val map = remember {
        MapTiles.init(context)
        MapView(context).apply {
            setTileProvider(MapTiles.newProvider(context))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            setBackgroundColor(android.graphics.Color.rgb(5, 9, 13))
            restoreCamera(context, this, fix?.let { GeoPoint(it.latitude, it.longitude) })
        }
    }
    /** Dónde está un nodo en el mapa (el propio, donde está el teléfono). */
    fun nodeCenter(num: Int): GeoPoint? {
        val f = fix
        if (num == me && f != null) return GeoPoint(f.latitude, f.longitude)
        val n = nodes[num] ?: return null
        return if (n.lat != null && n.lon != null) GeoPoint(n.lat, n.lon) else null
    }

    /**
     * Los nodos que en pantalla quedan encimados con [num] (misma posición o casi): el de arriba
     * tapa a los demás, así que la ficha los ofrece a todos. [num] va primero.
     */
    fun overlapping(num: Int): List<Int> {
        val at = nodeCenter(num) ?: return listOf(num)
        val pj = map.projection
        val p0 = pj.toPixels(at, null)
        val r = dpPx(context, 26f)
        val candidates = nodes.values
            .filter { it.lat != null && it.lon != null && (showOthers || isGroupMember(it, me, hasGroup, since)) }
            .map { it.num } + listOfNotNull(me.takeIf { fix != null })
        val near = candidates.distinct().filter { it != num }.filter { c ->
            val p = nodeCenter(c)?.let { pj.toPixels(it, null) } ?: return@filter false
            hypot((p.x - p0.x).toDouble(), (p.y - p0.y).toDouble()) <= r
        }.sortedByDescending { nodes[it]?.let { n -> isGroupMember(n, me, hasGroup, since) } == true }
        return listOf(num) + near
    }

    fun selectNode(num: Int) {
        selectedNode = num; selectedWpt = null; stack = overlapping(num)
    }

    /** Toque largo sobre un nodo: si tiene antena cargada, muestra el alcance; si no, pide los datos. */
    fun longPressNode(num: Int) {
        selectNode(num)
        if (antennas[num] != null) rangeNode = num else antennaFor = num
    }

    // Reloj para que los colores "perdido" se actualicen solos.
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() / 1000 } }

    val scaleBar = remember {
        ScaleBarOverlay(map).apply {
            setAlignBottom(true)
            setAlignRight(true)
            setScaleBarOffset(dpPx(context, 12f).toInt(), dpPx(context, 12f).toInt())
            textPaint.color = HudCyan.toArgb()
            textPaint.typeface = Typeface.MONOSPACE
            barPaint.color = HudCyan.toArgb()
            barPaint.strokeWidth = dpPx(context, 1.5f)
        }
    }

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

    fun isMember(n: MeshNode) = isGroupMember(n, me, hasGroup, since)
    val visibleNodes = nodes.values.filter { n ->
        n.lat != null && n.lon != null && (showOthers || isMember(n))
    }
    val activeWpts = waypoints.values.filter { it.isActive(now) }

    // clipToBounds: el MapView de osmdroid dibuja fuera de su lugar y taparía la barra de arriba.
    Box(modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize().clipToBounds(),
            update = { mv ->
                mv.overlayManager.tilesOverlay.setColorFilter(if (dark) darkFilter else null)
                mv.overlays.clear()
                mv.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        selectedNode = null; selectedWpt = null; rangeNode = null; stack = emptyList()
                        return false
                    }
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        if (p != null) newWptAt = p
                        return true
                    }
                }))
                mv.overlays.add(scaleBar)
                if (showTracks) for (n in visibleNodes + listOfNotNull(me?.let { nodes[it] })) {
                    val pts = tracks[n.num] ?: continue
                    if (pts.size < 2) continue
                    val tone = Signal.tone(n, me, isMember(n), now)
                    mv.overlays.add(Polyline(mv).apply {
                        setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                        outlinePaint.color = tone.color().toArgb()
                        outlinePaint.strokeWidth = dpPx(mv.context, 2.5f)
                        outlinePaint.alpha = 150
                        outlinePaint.pathEffect = if (tone == Tone.RELAY || tone == Tone.MUTE)
                            DashPathEffect(floatArrayOf(dpPx(mv.context, 6f), dpPx(mv.context, 4f)), 0f) else null
                        infoWindow = null
                        setOnClickListener { _, _, _ -> selectNode(n.num); true }
                    })
                }
                // Alcance estimado del nodo elegido con toque largo: ciudad (lleno) y campo abierto (punteado).
                rangeNode?.let { num ->
                    val at = nodeCenter(num) ?: return@let
                    val est = antennas[num]?.let { Range.estimate(it, preset) } ?: return@let
                    val blue = SigFixed.toArgb()
                    mv.overlays.add(Polygon(mv).apply {
                        points = Polygon.pointsAsCircle(at, est.openM)
                        fillPaint.color = android.graphics.Color.argb(22, 47, 125, 246)
                        outlinePaint.color = blue
                        outlinePaint.strokeWidth = dpPx(mv.context, 1.5f)
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dpPx(mv.context, 8f), dpPx(mv.context, 5f)), 0f)
                        infoWindow = null
                        setOnClickListener { _, _, _ -> false }
                    })
                    mv.overlays.add(Polygon(mv).apply {
                        points = Polygon.pointsAsCircle(at, est.urbanM)
                        fillPaint.color = android.graphics.Color.argb(45, 47, 125, 246)
                        outlinePaint.color = blue
                        outlinePaint.strokeWidth = dpPx(mv.context, 2f)
                        infoWindow = null
                        setOnClickListener { _, _, _ -> false }
                    })
                }
                for (w in activeWpts) {
                    mv.overlays.add(Marker(mv).apply {
                        position = GeoPoint(w.lat, w.lon)
                        icon = waypointIcon(mv.context, w.iconText)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> selectedWpt = w.id; selectedNode = null; true }
                    })
                }
                // Primero los ajenos, arriba los del grupo.
                val ordered = visibleNodes.sortedBy { if (isMember(it)) 1 else 0 }
                for (n in ordered) {
                    if (n.num == me && fix != null) continue // el propio se dibuja con el GPS del teléfono
                    val tone = Signal.tone(n, me, isMember(n), now)
                    mv.overlays.add(NodeMarker(mv, onLong = { longPressNode(n.num) }).apply {
                        position = GeoPoint(n.lat!!, n.lon!!)
                        val label = n.shortName.ifEmpty { n.id.takeLast(4) }
                        val link = Signal.quality(n, now).color().toArgb()
                        val lost = Signal.isLost(n, now)
                        icon = when {
                            // Ajeno: compacto, un solo anillo con la señal y el nombre en violeta.
                            Signal.isForeign(tone) -> nodeIcon(
                                mv.context, label, link, filled = false, lost = lost,
                                labelColor = tone.color().toArgb(), compact = true,
                            )
                            // Fijo del grupo: azul, con la señal en un anillo de afuera.
                            tone == Tone.FIXED -> nodeIcon(mv.context, label, tone.color().toArgb(), filled = true, lost = lost, ring = link)
                            else -> nodeIcon(mv.context, label, tone.color().toArgb(), filled = true, lost = lost)
                        }
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> selectNode(n.num); true }
                    })
                }
                fix?.let { f ->
                    // El teléfono y el nodo conectado están en el mismo lugar: un solo marcador
                    // con el nombre del nodo, para no confundirlo con otro nodo parecido.
                    val myLabel = me?.let { nodes[it] }?.let { it.shortName.ifEmpty { it.id.takeLast(4) } }
                    mv.overlays.add(NodeMarker(mv, onLong = { me?.let { longPressNode(it) } }).apply {
                        position = GeoPoint(f.latitude, f.longitude)
                        icon = meIcon(mv.context, myLabel)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> me?.let { selectNode(it) }; true }
                    })
                }
                mv.invalidate()
            },
        )

        // Atribución de OpenStreetMap (ODbL): obligatoria, visible y abajo a la derecha, sobre la escala.
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        Text(
            "© OpenStreetMap\ncontributors",
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 38.dp)
                .background(HudBg.copy(alpha = 0.6f))
                .clickable { uri.openUri("https://www.openstreetmap.org/copyright") }
                .padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = HudTextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // Marco tipo visor.
        Box(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, bottom = 6.dp, top = topInset + 2.dp).hudCorners(len = 22.dp))

        // Herramientas.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudIconButton("+", onClick = { map.controller.zoomIn() })
            HudIconButton("−", onClick = { map.controller.zoomOut() })
            HudIconButton("YO", enabled = fix != null, onClick = {
                fix?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude), max(map.zoomLevelDouble, 16.0), 600L) }
            })
            HudIconButton("TODO", enabled = visibleNodes.isNotEmpty(), onClick = { fitAll(map, visibleNodes, fix) })
            HudIconButton("RAS", active = showTracks, onClick = { showTracks = !showTracks })
            HudIconButton("REP", active = showOthers, onClick = { showOthers = !showOthers })
            HudIconButton("CAPA", onClick = { showLayers = true })
        }

        val bottom = Modifier.align(Alignment.BottomStart).padding(12.dp)
        when {
            selectedNode != null && nodes[selectedNode] != null -> {
                val n = nodes.getValue(selectedNode!!)
                NodeCard(
                    vm, n, me, isMember(n), fix, now, bottom, onMessage,
                    antenna = antennas[n.num], preset = preset, showingRange = rangeNode == n.num,
                    onAntenna = { antennaFor = n.num },
                    onRange = { rangeNode = if (rangeNode == n.num) null else n.num },
                    onClose = { selectedNode = null; rangeNode = null; stack = emptyList() },
                    onProfile = if (n.num != me && me?.let { nodeCenter(it) } != null && nodeCenter(n.num) != null) {
                        { profileFor = n.num }
                    } else null,
                    stack = stack.mapNotNull { nodes[it] }.takeIf { it.size > 1 }.orEmpty(),
                    onPick = { selectedNode = it },
                )
            }
            selectedWpt != null && waypoints[selectedWpt] != null ->
                WaypointCard(vm, waypoints.getValue(selectedWpt!!), nodes, fix, now, bottom, onClose = { selectedWpt = null })
            else -> Legend(
                bottom, expanded = showLegend,
                onToggle = { showLegend = !showLegend; mapPrefs.edit().putBoolean("legend", showLegend).apply() },
                members = nodes.values.count { isMember(it) && it.num != me },
                relays = nodes.values.count { !isMember(it) && !it.mute },
            )
        }
    }

    newWptAt?.let { at -> NewWaypointDialog(vm, at, onDone = { newWptAt = null }) }
    antennaFor?.let { num ->
        val n = nodes[num]
        // Para el nodo conectado sabemos la potencia real; 0 = la máxima, que en estas radios son 22 dBm.
        val tx = if (num == me) lora?.txPower?.takeIf { it in 1..22 } ?: 22 else 22
        AntennaDialog(
            name = n?.let { it.shortName.ifEmpty { it.id } } ?: "!%08x".format(num),
            current = antennas[num], defaultTx = tx,
            onSave = { a -> vm.setAntenna(num, a); rangeNode = if (a != null) num else null; antennaFor = null },
            onDismiss = { antennaFor = null },
        )
    }
    profileFor?.let { num ->
        val myNum = me
        val from = myNum?.let { nodeCenter(it) }
        val to = nodeCenter(num)
        if (myNum == null || from == null || to == null) { profileFor = null; return@let }
        val myTx = lora?.txPower?.takeIf { it in 1..30 } ?: 22
        val handheld = Antenna(Range.RX_GAIN_DBI, Range.RX_HEIGHT_M)
        fun label(k: Int) = nodes[k]?.let { it.shortName.ifEmpty { it.id.takeLast(4) } } ?: "!%08x".format(k)
        ProfileDialog(
            aName = label(myNum), a = Profile.End(from.latitude, from.longitude, antennas[myNum] ?: handheld.copy(txDbm = myTx)),
            aGuess = antennas[myNum] == null,
            bName = label(num), b = Profile.End(to.latitude, to.longitude, antennas[num] ?: handheld),
            bGuess = antennas[num] == null,
            freqMHz = Profile.frequencyMHz(lora), preset = preset,
            onDismiss = { profileFor = null },
        )
    }
    if (showLayers) {
        LayersDialog(map, dark, onDark = { dark = it; MapTiles.setDarkMap(context, it) }, onClose = { showLayers = false })
    }
}

/** Mapa oscuro: invierte el brillo y lo lleva a azules (las rutas quedan marcadas por sus bordes). */
private val darkFilter = ColorMatrixColorFilter(ColorMatrix().apply {
    fun row(a: Float, c: Float) = floatArrayOf(-0.299f * a, -0.587f * a, -0.114f * a, 0f, 255f * a + c)
    set(row(0.33f, 6f) + row(0.55f, 12f) + row(0.78f, 20f) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
})

@Composable
private fun Legend(modifier: Modifier, expanded: Boolean, onToggle: () -> Unit, members: Int, relays: Int) {
    HudPanel(modifier.width(210.dp), padding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudLabel("Grupo $members · Rep $relays", Modifier.weight(1f), color = HudCyan)
            TextButton(onClick = onToggle, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (expanded) "–" else "+")
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LegendItem(SigGood, "Directo o 1 salto")
                LegendItem(SigFair, "2 a 3 saltos")
                LegendItem(SigPoor, "4 a 5 saltos")
                LegendItem(SigWeak, "6 o más / perdido")
                LegendItem(SigFixed, "Fijo del grupo")
                LegendItem(SigRelay, "Nombre violeta = ajeno")
            }
            Spacer(Modifier.padding(2.dp))
            HudLabel("Largo en el mapa = waypoint")
            HudLabel("Largo en un nodo = alcance")
        }
    }
}

@Composable
private fun NodeCard(
    vm: MeshViewModel, n: MeshNode, me: Int?, member: Boolean, fix: Location?, now: Long, modifier: Modifier,
    onMessage: (Int) -> Unit,
    antenna: Antenna?, preset: ModemPreset, showingRange: Boolean,
    onAntenna: () -> Unit, onRange: () -> Unit, onClose: () -> Unit,
    stack: List<MeshNode> = emptyList(), onPick: (Int) -> Unit = {},
    onProfile: (() -> Unit)? = null,
) {
    // Altura del terreno donde está el nodo, si ya está el relieve de la zona (sin bajar nada).
    val ctx = LocalContext.current
    var groundM by remember(n.num, n.lat, n.lon) { mutableStateOf<Double?>(null) }
    LaunchedEffect(n.num, n.lat, n.lon) {
        val la = n.lat; val lo = n.lon
        groundM = if (la == null || lo == null) null
        else withContext(Dispatchers.IO) { Dem.elevation(ctx, la, lo, network = false) }
    }
    val tracks by vm.tracks.collectAsState()
    val tone = Signal.tone(n, me, member, now)
    val kind = when (tone) {
        Tone.ME -> "Este nodo"
        Tone.RELAY -> "Repetidor ajeno"
        Tone.MUTE -> "Ajeno · no repite"
        Tone.FIXED -> if (Signal.isLost(n, now)) "Grupo · fijo · sin noticias" else "Grupo · fijo"
        else -> if (Signal.isLost(n, now)) "Grupo · sin noticias" else "Grupo"
    }
    HudPanel(modifier.width(280.dp), title = kind, accent = tone.color()) {
        if (stack.size > 1) {
            // Varios nodos en el mismo lugar: se elige cuál ver.
            HudLabel("${stack.size} nodos en este lugar")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (o in stack) {
                    val c = Signal.tone(o, me, isGroupMember(o, me, vm.hasPrivateGroup(), vm.groupSince.value), now).color()
                    FilterChip(
                        selected = o.num == n.num, onClick = { onPick(o.num) },
                        label = { Text(o.shortName.ifEmpty { o.id.takeLast(4) }, color = c, fontWeight = FontWeight.SemiBold) },
                    )
                }
            }
            Spacer(Modifier.padding(2.dp))
        }
        Text(
            "${n.shortName.ifEmpty { "?" }}  ${n.longName.ifEmpty { n.id }}",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.padding(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (n.num != me) HudStat("Oído", agoText(now, n.lastHeard).removePrefix("hace "))
            HudStat("Enlace", Signal.describe(n)?.substringBefore(" · ") ?: "—", color = Signal.linkTone(tone, n, now).color())
            n.battery?.let { HudStat("Bat", if (it > 100) "USB" else "$it%") }
        }
        val extra = buildList {
            if (n.hopsAway == 0) n.rssi?.let { add("RSSI $it dBm") }
            if (fix != null && n.num != me && n.lat != null && n.lon != null) {
                val (d, b) = PhoneLocation.distanceAndBearing(fix.latitude, fix.longitude, n.lat, n.lon)
                add("${PhoneLocation.formatDistance(d)} al ${PhoneLocation.cardinal(b)}")
            }
            tracks[n.num]?.size?.takeIf { it > 1 }?.let { add("rastro $it pts") }
            groundM?.let { add("terreno ${it.roundToInt()} m") }
            n.hw?.takeIf { it != org.meshtastic.proto.MeshProtos.HardwareModel.UNSET }?.let { add(io.github.protohommer.meshpaper.mesh.Hardware.name(it)) }
        }
        if (extra.isNotEmpty()) {
            Spacer(Modifier.padding(2.dp))
            HudLabel(extra.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        }
        val est = antenna?.let { Range.estimate(it, preset) }
        if (antenna != null && est != null) {
            Spacer(Modifier.padding(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudLabel(
                    "Antena %s dBi a %s m".format(fmtNum(antenna.gainDbi), fmtNum(antenna.heightM)),
                    Modifier.weight(1f), color = SigFixed,
                )
                TextButton(onClick = onRange) { Text(if (showingRange) "OCULTAR" else "VER ALCANCE") }
            }
            Text(
                "Alcance ≈ ${PhoneLocation.formatDistance(est.urbanM.toFloat())} en ciudad · " +
                    "${PhoneLocation.formatDistance(est.openM.toFloat())} a campo abierto",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Hacia un nodo de mano (1,5 m). Sin relieve: un cerro en el medio lo corta.",
                style = MaterialTheme.typography.bodySmall, color = HudTextDim,
            )
        }
        val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n.num != me) TextButton(onClick = { onMessage(n.num) }, contentPadding = tight) { Text("MENSAJE") }
            onProfile?.let { TextButton(onClick = it, contentPadding = tight) { Text("PERFIL") } }
            TextButton(onClick = onAntenna, contentPadding = tight) { Text("ANTENA") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose, contentPadding = tight) { Text("✕") }
        }
    }
}

@Composable
private fun WaypointCard(
    vm: MeshViewModel, w: Wpt, nodes: Map<Int, MeshNode>, fix: Location?, now: Long,
    modifier: Modifier, onClose: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    HudPanel(modifier.width(280.dp), title = "Waypoint") {
        Text("${w.iconText}  ${w.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (w.description.isNotEmpty()) Text(w.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val by = nodes[w.from]?.let { it.shortName.ifEmpty { it.id } } ?: if (w.from != 0) "!%08x".format(w.from) else "—"
            HudStat("De", by)
            HudStat("Vence", if (w.expire == 0L) "nunca" else "en " + agoText(w.expire, now).removePrefix("hace "))
            if (fix != null) {
                val (d, b) = PhoneLocation.distanceAndBearing(fix.latitude, fix.longitude, w.lat, w.lon)
                HudStat("Dist", "${PhoneLocation.formatDistance(d)} ${PhoneLocation.cardinal(b)}")
            }
        }
        Spacer(Modifier.padding(2.dp))
        HudLabel("%.5f, %.5f".format(w.lat, w.lon))
        Row {
            TextButton(onClick = { confirm = true }) {
                Text(if (vm.canDeleteForAll(w)) "BORRAR" else "OCULTAR", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("CERRAR") }
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
            confirmButton = { TextButton(onClick = { vm.deleteWaypoint(w); confirm = false; onClose() }) { Text("SÍ") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("NO") } },
        )
    }
}

/** Marcador de nodo que además avisa del toque largo (osmdroid solo lo usa para arrastrar). */
private class NodeMarker(mv: MapView, private val onLong: () -> Unit) : Marker(mv) {
    override fun onLongPress(event: MotionEvent, mapView: MapView): Boolean {
        if (hitTest(event, mapView)) { onLong(); return true }
        return super.onLongPress(event, mapView)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = HudTextDim)
}

private fun fmtNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(Locale.forLanguageTag("es-AR"), v)

/** Datos de la antena de un nodo, para estimar su alcance. Se guardan solo en este teléfono. */
@Composable
private fun AntennaDialog(name: String, current: Antenna?, defaultTx: Int, onSave: (Antenna?) -> Unit, onDismiss: () -> Unit) {
    fun t(v: Double?) = v?.let(::fmtNum) ?: ""
    var gain by remember { mutableStateOf(t(current?.gainDbi ?: 2.0)) }
    var height by remember { mutableStateOf(t(current?.heightM)) }
    var cable by remember { mutableStateOf(t(current?.cableDb ?: 0.0)) }
    var tx by remember { mutableStateOf((current?.txDbm ?: defaultTx).toString()) }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    val g = num(gain)?.takeIf { it in -5.0..20.0 }
    val h = num(height)?.takeIf { it in 0.0..500.0 }
    val c = num(cable)?.takeIf { it in 0.0..20.0 }
    val p = tx.toIntOrNull()?.takeIf { it in 0..30 }
    val ok = g != null && h != null && c != null && p != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ANTENA DE ${name.uppercase()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Meshtastic no manda estos datos: cargalos vos para estimar el alcance.",
                    style = MaterialTheme.typography.bodySmall, color = HudTextDim,
                )
                OutlinedTextField(
                    value = gain, onValueChange = { gain = it }, label = { Text("Ganancia (dBi)") },
                    singleLine = true, isError = g == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Hint("De fábrica ≈ 2 · látigo 3–5 · colineal 6–8")
                OutlinedTextField(
                    value = height, onValueChange = { height = it }, label = { Text("Altura sobre el suelo (m)") },
                    singleLine = true, isError = h == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Hint("En la mano ≈ 1,5 · techo de casa ≈ 6–8")
                OutlinedTextField(
                    value = cable, onValueChange = { cable = it }, label = { Text("Pérdida del cable (dB)") },
                    singleLine = true, isError = c == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Hint("Directa a la placa = 0 · RG58 ≈ 0,5 por metro")
                OutlinedTextField(
                    value = tx, onValueChange = { tx = it.filter(Char::isDigit).take(2) }, label = { Text("Potencia (dBm)") },
                    singleLine = true, isError = p == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Antenna(g!!, h!!, c!!, p!!)) }, enabled = ok) { Text("GUARDAR") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("BORRAR") }
                TextButton(onClick = onDismiss) { Text("CANCELAR") }
            }
        },
    )
}

private val ICONS = listOf(0x1F4CD, 0x26FA, 0x1F697, 0x1F6B0, 0x26A0, 0x1F3C1, 0x1F6A9, 0x2B50)
private val EXPIRES = listOf("Nunca" to 0L, "1 h" to 3600L, "1 día" to 86_400L, "1 sem" to 604_800L)

@Composable
private fun NewWaypointDialog(vm: MeshViewModel, at: GeoPoint, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var icon by remember { mutableIntStateOf(Wpt.DEFAULT_ICON) }
    var expire by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("NUEVO WAYPOINT") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudLabel("%.5f, %.5f".format(at.latitude, at.longitude))
                OutlinedTextField(
                    value = name, singleLine = true, label = { Text("Nombre") },
                    onValueChange = { name = Wpt.fitBytes(it, Wpt.MAX_NAME) },
                )
                OutlinedTextField(
                    value = desc, label = { Text("Descripción (opcional)") },
                    onValueChange = { desc = Wpt.fitBytes(it, Wpt.MAX_DESCRIPTION) },
                )
                HudLabel("Ícono")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (cp in ICONS.take(4)) IconChip(cp, icon == cp) { icon = cp }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (cp in ICONS.drop(4)) IconChip(cp, icon == cp) { icon = cp }
                }
                HudLabel("Vence")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EXPIRES.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = expire == i, onClick = { expire = i }, label = { Text(label) })
                    }
                }
                HudLabel("Sale por el canal del grupo · solo vos lo borrás")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val secs = EXPIRES[expire].second
                val exp = if (secs == 0L) 0L else System.currentTimeMillis() / 1000 + secs
                error = vm.sendWaypoint(at.latitude, at.longitude, name, desc, icon, exp)
                if (error == null) onDone()
            }) { Text("MANDAR") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("CANCELAR") } },
    )
}

@Composable
private fun IconChip(cp: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(String(Character.toChars(cp))) })
}

// ---------- Capas / mapas offline ----------

private sealed interface DlState {
    data object Idle : DlState
    data class Running(val done: Int, val total: Int) : DlState
    data class Finished(val text: String) : DlState
}

@Composable
private fun LayersDialog(map: MapView, dark: Boolean, onDark: (Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(MapTiles.archives(context)) }
    var online by remember { mutableStateOf(MapTiles.useInternet(context)) }
    var url by remember { mutableStateOf(MapTiles.customUrl(context)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var dl by remember { mutableStateOf<DlState>(DlState.Idle) }
    var cacheBytes by remember { mutableLongStateOf(cacheSize(context)) }

    fun reload() {
        map.setTileProvider(MapTiles.newProvider(context))
        map.overlayManager.tilesOverlay.setColorFilter(if (dark) darkFilter else null)
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
    val canBulk = MapTiles.canBulkDownload(map.tileProvider.tileSource)
    val box: BoundingBox = map.boundingBox
    val tileCount = remember(zoom, maxZoom) {
        runCatching { CacheManager(map).possibleTilesInArea(box, zoom, max(zoom, maxZoom)) }.getOrDefault(0)
    }

    AlertDialog(
        onDismissRequest = { if (dl !is DlState.Running) onClose() },
        title = { Text("CAPAS Y MAPAS OFFLINE") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow("Mapa oscuro", if (dark) "Estilo HUD" else "Colores normales (mejor a pleno sol)", dark, onDark)
                SwitchRow(
                    "Usar internet",
                    if (online) "Baja lo que mirás y lo guarda" else "Solo caché y archivos importados",
                    online,
                ) {
                    online = it
                    MapTiles.setUseInternet(context, it)
                    map.setUseDataConnection(it)
                }
                HudLabel("Caché: ${MapTiles.formatSize(cacheBytes)}")
                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                HudLabel("Archivos .mbtiles", color = HudCyan)
                Text("Armalos en la PC (QGIS, MOBAC, mbutil…) y pasalos al teléfono.", style = MaterialTheme.typography.bodySmall)
                if (files.isEmpty()) HudLabel("Ninguno todavía")
                for (f in files) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name} (${MapTiles.formatSize(f.length())})", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { MapTiles.delete(f); reload() }) { Text("QUITAR", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudOutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("IMPORTAR .MBTILES") }
                    if (busy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.padding(4.dp)) }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                HudLabel("Servidor de tiles", color = HudCyan)
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
                ) { Text("USAR ESTE SERVIDOR") }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                DemSection(box, online)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                HudLabel("Descargar la zona visible", color = HudCyan)
                if (!canBulk) {
                    Text(
                        "OpenStreetMap no permite bajar zonas ni usar sus mapas sin conexión. Para el " +
                            "campo, importá un .mbtiles (de una zona descargada con otra herramienta) " +
                            "o poné un servidor propio, que sí permite descargar zonas.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    HudLabel("Zoom $zoom a $maxZoom")
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
                            HudLabel("${d.done} / ${d.total}")
                        }
                        is DlState.Finished -> Text(d.text, style = MaterialTheme.typography.bodySmall)
                        DlState.Idle -> Unit
                    }
                    HudButton(
                        onClick = {
                            dl = DlState.Running(0, tileCount)
                            CacheManager(map).downloadAreaAsyncNoUI(context, box, zoom, maxZoom, object : CacheManager.CacheManagerCallback {
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
                    ) { Text("DESCARGAR") }
                    if (!online) Text("Prendé \"Usar internet\" para descargar.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = dl !is DlState.Running) { Text("LISTO") } },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Relieve (DEM) para los perfiles: bajar el de la zona visible para usarlo sin señal. */
@Composable
private fun DemSection(box: BoundingBox, online: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stored by remember { mutableStateOf(Dem.stored(context)) }
    val tiles = remember(box) { Dem.tilesFor(box.latNorth, box.latSouth, box.lonWest, box.lonEast) }
    val missing = remember(tiles, stored) { tiles.count { !Dem.has(context, it.first, it.second) } }
    var dl by remember { mutableStateOf<DlState>(DlState.Idle) }
    HudLabel("Relieve (para el perfil entre nodos)", color = HudCyan)
    Text(
        "Alturas del terreno del SRTM, ≈30 m. Con internet se bajan solas al pedir un perfil; " +
            "para el campo, bajá la zona antes de salir.",
        style = MaterialTheme.typography.bodySmall,
    )
    HudLabel("Guardado: ${stored.tiles} tiles · ${MapTiles.formatSize(stored.bytes)}")
    val tooMany = tiles.size > Dem.MAX_DOWNLOAD_TILES
    Text(
        when {
            tooMany -> "La zona visible es muy grande (${tiles.size} tiles, máx. ${Dem.MAX_DOWNLOAD_TILES}): acercate."
            missing == 0 -> "La zona visible ya está guardada."
            else -> "Zona visible: faltan $missing de ${tiles.size} tiles (≈${MapTiles.formatSize(missing * 40_000L)})."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (tooMany) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    when (val d = dl) {
        is DlState.Running -> {
            LinearProgressIndicator(
                progress = { if (d.total > 0) d.done / d.total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            HudLabel("${d.done} / ${d.total}")
        }
        is DlState.Finished -> Text(d.text, style = MaterialTheme.typography.bodySmall)
        DlState.Idle -> Unit
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HudButton(
            onClick = {
                val todo = tiles.filter { !Dem.has(context, it.first, it.second) }
                dl = DlState.Running(0, todo.size)
                scope.launch {
                    var fails = 0
                    todo.forEachIndexed { i, (x, y) ->
                        if (!withContext(Dispatchers.IO) { Dem.download(context, x, y) }) fails++
                        dl = DlState.Running(i + 1, todo.size)
                    }
                    stored = withContext(Dispatchers.IO) { Dem.stored(context) }
                    dl = DlState.Finished(if (fails == 0) "Relieve guardado." else "Terminó con $fails tiles fallidos.")
                }
            },
            enabled = online && !tooMany && missing > 0 && dl !is DlState.Running,
        ) { Text("BAJAR RELIEVE") }
        if (stored.tiles > 0) TextButton(
            onClick = { Dem.clear(context); stored = Dem.stored(context) },
            enabled = dl !is DlState.Running,
        ) { Text("BORRAR", color = MaterialTheme.colorScheme.error) }
    }
    if (!online) Text("Prendé \"Usar internet\" para bajarlo.", style = MaterialTheme.typography.bodySmall)
    Text(Dem.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = HudTextDim)
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
        // Sin nada todavía: Argentina.
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

private fun fitAll(mv: MapView, nodes: List<MeshNode>, fix: Location?) {
    val pts = nodes.mapNotNull { n -> if (n.lat != null && n.lon != null) GeoPoint(n.lat, n.lon) else null } +
        listOfNotNull(fix?.let { GeoPoint(it.latitude, it.longitude) })
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        mv.controller.animateTo(pts[0], max(mv.zoomLevelDouble, 15.0), 600L)
        return
    }
    // Margen amplio: abajo están la leyenda/ficha y a la derecha las herramientas.
    mv.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.7f), true, 96)
}

// ---------- Íconos ----------

private val iconCache = HashMap<String, BitmapDrawable>()

private fun dpPx(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

/**
 * Marcador de nodo estilo HUD: anillo de color con el nombre corto adentro.
 * Los del grupo llevan un relleno tenue del color; los ajenos, anillo solo.
 * Perdido = anillo punteado y apagado.
 */
/**
 * Nodo: anillo de [color] con el nombre corto adentro (en [labelColor]). [ring] = anillo exterior
 * con la señal (fijos del grupo). [compact] = más chico y sin halo (ajenos).
 */
private fun nodeIcon(
    ctx: Context, label: String, color: Int, filled: Boolean, lost: Boolean,
    ring: Int? = null, labelColor: Int = color, compact: Boolean = false,
): BitmapDrawable =
    iconCache.getOrPut("n:$label:$color:$filled:$lost:$ring:$labelColor:$compact") {
        val size = dpPx(ctx, if (ring != null) 46f else if (compact) 32f else 40f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val ringR = r - dpPx(ctx, if (ring != null) 8f else if (compact) 2f else 5f)
        val alpha = if (lost) 130 else 255
        if (ring != null) {
            // anillo de señal por fuera, con un borde oscuro para que se separe del principal
            c.drawCircle(r, r, r - dpPx(ctx, 2.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.argb(200, 5, 9, 13)
                style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 4.5f)
            })
            c.drawCircle(r, r, r - dpPx(ctx, 2.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = ring; this.alpha = alpha
                style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 2.5f)
            })
        } else if (!compact) {
            // halo
            c.drawCircle(r, r, r - dpPx(ctx, 1f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; this.alpha = if (lost) 20 else 45
            })
        }
        // fondo oscuro
        c.drawCircle(r, r, ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.argb(225, 5, 9, 13) })
        if (filled) c.drawCircle(r, r, ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = if (lost) 25 else 55 })
        // anillo
        c.drawCircle(r, r, ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; this.alpha = alpha
            style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 2.5f)
            if (lost) pathEffect = DashPathEffect(floatArrayOf(dpPx(ctx, 4f), dpPx(ctx, 3f)), 0f)
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = labelColor; this.alpha = alpha
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = dpPx(ctx, if (label.length <= 2) 12f else if (compact) 8.5f else 9.5f)
        }
        c.drawText(label.take(4), r, r - (text.descent() + text.ascent()) / 2, text)
        BitmapDrawable(ctx.resources, bmp)
    }

/**
 * El teléfono (y el nodo conectado, que está con él): punto cian con retícula y, abajo, el nombre
 * corto del nodo. El bitmap es simétrico para que el anclaje al centro caiga justo en el punto.
 */
private fun meIcon(ctx: Context, label: String?): BitmapDrawable = iconCache.getOrPut("me:$label") {
    val size = dpPx(ctx, 34f).toInt()
    val tag = if (label != null) dpPx(ctx, 16f).toInt() else 0
    val w = max(size, dpPx(ctx, 64f).toInt())
    val h = size + 2 * tag
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = h / 2f
    val r = size / 2f
    val cyan = HudCyan.toArgb()
    c.drawCircle(cx, cy, r - dpPx(ctx, 1f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; alpha = 40 })
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; strokeWidth = dpPx(ctx, 1.5f) }
    val g = dpPx(ctx, 7f)
    c.drawLine(cx, cy - r, cx, cy - r + g, line); c.drawLine(cx, cy + r - g, cx, cy + r, line)
    c.drawLine(cx - r, cy, cx - r + g, cy, line); c.drawLine(cx + r - g, cy, cx + r, cy, line)
    c.drawCircle(cx, cy, dpPx(ctx, 6f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(cx, cy, dpPx(ctx, 4.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan })
    if (label != null) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = dpPx(ctx, 10f)
        }
        val t = label.take(6).uppercase()
        val tw = text.measureText(t) + dpPx(ctx, 8f)
        val top = cy + r + dpPx(ctx, 1f)
        c.drawRect(cx - tw / 2, top, cx + tw / 2, top + dpPx(ctx, 14f),
            Paint().apply { color = android.graphics.Color.argb(220, 5, 9, 13) })
        c.drawText(t, cx, top + dpPx(ctx, 7f) - (text.descent() + text.ascent()) / 2, text)
    }
    BitmapDrawable(ctx.resources, bmp)
}

/** Emoji del waypoint sobre una base chica. */
private fun waypointIcon(ctx: Context, emoji: String): BitmapDrawable = iconCache.getOrPut("w:$emoji") {
    val w = dpPx(ctx, 34f).toInt()
    val h = dpPx(ctx, 42f).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cyan = HudCyan.toArgb()
    val cx = w / 2f
    c.drawLine(cx, h - dpPx(ctx, 10f), cx, h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; strokeWidth = dpPx(ctx, 2f) })
    c.drawCircle(cx, h - dpPx(ctx, 1.5f), dpPx(ctx, 2f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan })
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dpPx(ctx, 24f); textAlign = Paint.Align.CENTER }
    c.drawText(emoji, cx, h - dpPx(ctx, 12f) - p.descent(), p)
    BitmapDrawable(ctx.resources, bmp)
}

fun agoText(now: Long, t: Long): String {
    if (t == 0L) return "nunca oído"
    val s = (now - t).coerceAtLeast(0)
    return when {
        s < 60 -> "hace ${s}s"
        s < 3600 -> "hace ${s / 60} min"
        s < 86400 -> "hace ${s / 3600} h"
        else -> "hace ${s / 86400} d"
    }
}
