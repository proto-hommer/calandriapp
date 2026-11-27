package io.github.protohommer.meshpaper.ui

import io.github.protohommer.meshpaper.ui.theme.HudBg
import io.github.protohommer.meshpaper.ui.theme.Appearance
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.unit.Dp
import io.github.protohommer.meshpaper.ui.theme.SigPoor
import java.util.Locale
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.GroundOverlay
import io.github.protohommer.meshpaper.mesh.Coverage
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.protohommer.meshpaper.ui.theme.SigMute
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
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
import io.github.protohommer.meshpaper.ui.theme.HudLine
import io.github.protohommer.meshpaper.ui.theme.HudText
import io.github.protohommer.meshpaper.ui.theme.SigMe
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigRelay
import io.github.protohommer.meshpaper.ui.theme.SigWeak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
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
import io.github.protohommer.meshpaper.map.Nav
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
fun MapScreen(vm: MeshViewModel, onMessage: (Int) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val nodes by vm.nodes.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val channels by vm.channels.collectAsState()
    val since by vm.groupSince.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val measurements by vm.measurements.collectAsState()
    val waypoints by vm.waypoints.collectAsState()
    val fix by vm.myFix.collectAsState()
    val hasGroup = remember(channels) { vm.hasPrivateGroup() }

    var showOthers by remember { mutableStateOf(true) }
    var showTracks by remember { mutableStateOf(false) } // los rastros, solo si se piden (RAS)
    var showMeasured by remember { mutableStateOf(false) } // lo medido de verdad (MED)
    val mapPrefs = remember { context.getSharedPreferences("map", Context.MODE_PRIVATE) }
    var showLegend by remember { mutableStateOf(mapPrefs.getBoolean("legend", true)) }
    // Fichas y escala de cobertura minimizadas (quedan así hasta que se vuelvan a abrir).
    var cardMin by remember { mutableStateOf(mapPrefs.getBoolean("card_min", false)) }
    var coverageMin by remember { mutableStateOf(mapPrefs.getBoolean("coverage_min", false)) }
    var selectedNode by remember { mutableStateOf<Int?>(null) }
    // Nodo pedido desde la lista ("Ver en el mapa"): se dibuja aunque esté oculto (ajeno sin oír
    // o REP apagado) mientras su ficha siga abierta.
    var pinned by remember { mutableStateOf<Int?>(null) }
    var stack by remember { mutableStateOf<List<Int>>(emptyList()) } // nodos encimados con el elegido
    var selectedWpt by remember { mutableStateOf<Int?>(null) }
    var newWptAt by remember { mutableStateOf<GeoPoint?>(null) }
    var showLayers by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(MapTiles.darkMap(context)) }
    val antennas by vm.antennas.collectAsState()
    val lora by vm.lora.collectAsState()
    val preset = lora?.modemPreset ?: ModemPreset.LONG_FAST
    val measureOverlay = remember(measurements, preset) { MeasureOverlay(context, measurements, preset) }
    var rangeNode by remember { mutableStateOf<Int?>(null) }   // nodo con el alcance dibujado
    var antennaFor by remember { mutableStateOf<Int?>(null) }  // nodo al que se le cargan los datos de antena
    var profileFor by remember { mutableStateOf<Int?>(null) }  // nodo con el perfil de terreno abierto
    val nav by Nav.state.collectAsState()
    LaunchedEffect(fix) { Nav.onFix(fix) }
    // Navegación guiada: el mapa sigue al teléfono, girado hacia donde va. Si uno arrastra el mapa
    // deja de seguir (como Google Maps) hasta tocar SEGUIR.
    val guided = nav?.guided == true
    var following by remember { mutableStateOf(true) }
    var heading by remember { mutableStateOf<Float?>(null) }
    var guidedZoomSet by remember { mutableStateOf(false) }
    // Si el destino es un nodo y manda otra posición, el destino se mueve con él.
    val navNode = nav?.target?.nodeNum?.let { nodes[it] }
    LaunchedEffect(navNode?.lat, navNode?.lon) {
        val la = navNode?.lat; val lo = navNode?.lon
        if (la != null && lo != null) Nav.onTargetMoved(la, lo)
    }

    // Antenas con "altura del GPS": altitud que manda el nodo − terreno del relieve.
    val gpsKeys = antennas.filterValues { it.gpsHeight }.keys
        .map { k -> nodes[k].let { Triple(k, it?.alt, it?.lat to it?.lon) } }
    var gpsHeights by remember { mutableStateOf<Map<Int, GpsHeight>>(emptyMap()) }
    LaunchedEffect(gpsKeys) {
        gpsHeights = withContext(Dispatchers.IO) {
            gpsKeys.associate { (k, alt, ll) ->
                val (la, lo) = ll
                k to gpsHeightOf(context, alt, la, lo)
            }
        }
    }

    /** La antena del nodo con la altura resuelta (la del GPS si está pedida y se puede). */
    fun effAntenna(num: Int): Antenna? = antennas[num]?.let { a ->
        val h = gpsHeights[num]?.aboveGround
        if (a.gpsHeight && h != null) a.copy(heightM = h) else a
    }

    // Cobertura según el relieve del nodo con el alcance abierto. Mientras se calcula por primera
    // vez, o si falta el relieve, queda el círculo de antes.
    //
    // Las claves van redondeadas: el GPS (del teléfono o el que manda el nodo) se mueve unos metros
    // en cada lectura, y recalcular por eso hacía parpadear el mapa. ~50 m es menos que dos píxeles
    // del relieve, y 1 m de altura de antena no cambia nada a la vista. Al recalcular (porque el nodo
    // se movió de verdad) la cobertura anterior queda hasta que esté la nueva.
    var coverage by remember { mutableStateOf<Coverage.Result?>(null) }
    var coverageFor by remember { mutableStateOf<Int?>(null) }
    var coverageMsg by remember { mutableStateOf<String?>(null) }
    val covNode = rangeNode
    val covAnt = covNode?.let { effAntenna(it) }?.let { it.copy(heightM = it.heightM.roundToInt().toDouble().coerceAtLeast(0.5)) }
    val covAt = covNode?.let { num ->
        val f = fix
        if (num == me && f != null) f.latitude to f.longitude
        else nodes[num]?.let { n -> if (n.lat != null && n.lon != null) n.lat to n.lon else null }
    }?.let { (la, lo) -> Math.round(la * 2000) / 2000.0 to Math.round(lo * 2000) / 2000.0 }
    val covFreq = Profile.frequencyMHz(lora)
    LaunchedEffect(covNode, covAnt, covAt, covFreq, preset) {
        val at = covAt; val a = covAnt
        if (covNode == null || at == null || a == null) {
            coverage = null; coverageFor = null; coverageMsg = null; return@LaunchedEffect
        }
        if (coverageFor != covNode) { coverage = null; coverageFor = null }
        if (coverage == null) coverageMsg = "Calculando la cobertura…"
        val net = MapTiles.useInternet(context)
        val out = withContext(Dispatchers.Default) {
            Coverage.compute(context, at.first, at.second, a, covFreq, preset, net) { done, total ->
                if (done < total && coverage == null) coverageMsg = "Relieve: $done de $total…"
            }
        }
        when (out) {
            is Coverage.Outcome.Done -> { coverage = out.result; coverageFor = covNode; coverageMsg = null }
            is Coverage.Outcome.MissingTerrain -> if (coverage == null) coverageMsg =
                if (net) "Faltan ${out.missing} de ${out.total} tiles de relieve y no bajaron: queda el círculo."
                else "Sin relieve de la zona: prendé \"Usar internet\" (CAPA) o guardalo en Mapas sin conexión."
            Coverage.Outcome.NoPreset -> coverageMsg = null
        }
    }

    val map = remember {
        MapTiles.init(context)
        MapView(context).apply {
            setTileProvider(MapTiles.newProvider(context))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            setBackgroundColor(HudBg.toArgb())
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

    /** Encuadra el teléfono, el destino y la ruta. */
    fun fitNav() {
        val s = Nav.state.value ?: return
        val pts = (s.route?.points.orEmpty().map { GeoPoint(it.first, it.second) } +
            listOfNotNull(GeoPoint(s.target.lat, s.target.lon), fix?.let { GeoPoint(it.latitude, it.longitude) }))
        if (pts.size < 2) { map.controller.animateTo(pts.first()); return }
        val box = BoundingBox.fromGeoPoints(pts)
        map.post { map.zoomToBoundingBox(box.increaseByScale(1.4f), true, dpPx(context, 40f).toInt()) }
    }
    var fittedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nav?.route) {
        val s = nav ?: return@LaunchedEffect
        val key = "${s.target.nodeNum}/${s.target.wptId}/${s.mode}"
        if (s.route != null && fittedFor != key) { fittedFor = key; fitNav() }
    }
    // "Guardar el camino": relieve (y mapa, si la fuente lo permite) de los cuadrantes de la ruta,
    // con la misma descarga que la cuadrícula de Ajustes → Mapas sin conexión, así quedan marcados.
    var saving by remember { mutableStateOf<String?>(null) }
    val saveScope = rememberCoroutineScope()
    fun saveCorridor() {
        val r = Nav.state.value?.route ?: return
        if (!MapTiles.useInternet(context)) { saving = "Prendé \"Usar internet\" (CAPA) para guardar."; return }
        val cells = io.github.protohommer.meshpaper.map.OfflineCells.along(r.points)
        val src = MapTiles.tileSource(context)
        val canMap = MapTiles.canBulkDownload(src)
        saveScope.launch {
            var fails = 0
            cells.forEachIndexed { i, c ->
                saving = "Guardando el camino: cuadrante ${i + 1} de ${cells.size}…"
                for ((x, y) in io.github.protohommer.meshpaper.map.OfflineCells.demTiles(c)) {
                    if (!withContext(Dispatchers.IO) { Dem.download(context, x, y) }) fails++
                    withContext(Dispatchers.IO) { io.github.protohommer.meshpaper.map.LandCover.download(context, x, y) }
                }
                if (canMap) {
                    val errors = downloadMap(map, c, 15) {}
                    if (errors == 0) io.github.protohommer.meshpaper.map.OfflineCells.markMap(context, c, src.name(), 15) else fails += errors
                }
            }
            saving = if (fails == 0) {
                "Camino guardado: ${cells.size} cuadrantes" + if (canMap) " (mapa y relieve)." else
                    " de relieve. Las calles se guardan al verlas con internet."
            } else "Guardado con $fails fallas: tocá de nuevo para reintentar."
        }
    }
    LaunchedEffect(nav?.target, nav?.route == null) { if (saving?.startsWith("Guardando") != true) saving = null }

    // GPS cada segundo y pantalla prendida mientras se navega guiado.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(guided) {
        vm.setGuidedLocation(guided)
        view.keepScreenOn = guided
        following = true
        guidedZoomSet = false
        if (!guided) {
            map.setMapCenterOffset(0, 0)
            map.mapOrientation = 0f
        }
        onDispose { vm.setGuidedLocation(false); view.keepScreenOn = false }
    }
    // Arrastrar el mapa con el dedo deja de seguir.
    DisposableEffect(map) {
        var downX = 0f
        var downY = 0f
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2
        // Con dos dedos (zoom) sigue siguiendo: solo arrastrar con uno lo suelta.
        var multi = false
        map.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; multi = false }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> multi = true
                android.view.MotionEvent.ACTION_MOVE ->
                    if (Nav.state.value?.guided == true && !multi && hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) following = false
            }
            false
        }
        onDispose { map.setOnTouchListener(null) }
    }
    // Flecha de la navegación guiada: un solo marcador que se mueve suave de un fix al siguiente
    // (como Google Maps), en vez de saltar y redibujarse con cada posición del GPS.
    val navMarker = remember {
        Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isFlat = true // gira con el mapa: rotation = -rumbo
            infoWindow = null
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    // Brújula: el rumbo cuando uno está quieto o va despacio (ahí el GPS no lo sabe).
    val compass = remember { io.github.protohommer.meshpaper.mesh.Compass(context) }
    DisposableEffect(guided) {
        if (guided) compass.start()
        onDispose { compass.stop() }
    }
    val fixNow by rememberUpdatedState(fix)
    val navNow by rememberUpdatedState(nav)
    val followingNow by rememberUpdatedState(following)
    LaunchedEffect(guided) {
        if (!guided) return@LaunchedEffect
        var from: GeoPoint? = null
        var to: GeoPoint? = null
        var shown: GeoPoint? = null
        var startAt = 0L
        var dur = 0L
        var lastTime = 0L
        var shownHeading = Float.NaN
        while (true) {
            val f = fixNow
            val now = android.os.SystemClock.elapsedRealtime()
            if (f != null && f.time != lastTime) {
                // Nuevo fix: se anima desde donde está la flecha hasta el nuevo punto, en lo que
                // tardó en llegar este fix (así la flecha no se frena entre uno y otro).
                if (lastTime == 0L) compass.setLocation(f)
                from = shown ?: GeoPoint(f.latitude, f.longitude)
                to = GeoPoint(f.latitude, f.longitude)
                dur = if (lastTime == 0L) 0L else (f.time - lastTime).coerceIn(300L, 6_000L)
                startAt = now
                lastTime = f.time
            }
            val a = from
            val b = to
            if (a != null && b != null) {
                val t = if (dur == 0L) 1.0 else ((now - startAt).toDouble() / dur).coerceIn(0.0, 1.0)
                shown = GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
                // Rumbo: en movimiento (más de ~9 km/h) el del GPS, que es el de la marcha;
                // si no, la brújula; sin brújula, el del tramo de ruta.
                val gps = f?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 2.5f }?.bearing
                val target = gps ?: compass.azimuth ?: navNow?.routeBearing ?: navNow?.bearing ?: 0f
                shownHeading = if (shownHeading.isNaN()) target else {
                    // Suavizado por el camino corto (de 350° a 10° son 20°, no 340°).
                    val d = ((target - shownHeading + 540f) % 360f) - 180f
                    (shownHeading + d * 0.15f + 360f) % 360f
                }
                if (heading == null || kotlin.math.abs((((shownHeading - heading!!) + 540f) % 360f) - 180f) > 4f) heading = shownHeading
                navMarker.position = shown
                navMarker.rotation = -shownHeading
                if (followingNow) {
                    if (!guidedZoomSet) {
                        guidedZoomSet = true
                        map.controller.setZoom(if (navNow?.mode == io.github.protohommer.meshpaper.map.Routing.Mode.CAR) 17.0 else 18.0)
                    }
                    // Uno queda más abajo del centro, para ver más de lo que viene, pero por encima del
                    // panel de abajo (Llega/Rumbo/Sube), que en la 2.0 ocupa casi el último cuarto.
                    map.setMapCenterOffset(0, (map.height * 0.12f).toInt())
                    map.controller.setCenter(shown)
                    map.mapOrientation = -shownHeading
                }
                map.invalidate()
            }
            delay(33) // ~30 cuadros por segundo
        }
    }

    fun startNav(t: Nav.Target) {
        fittedFor = null
        selectedNode = null; selectedWpt = null; rangeNode = null; stack = emptyList()
        Nav.start(context, t, fix)
        fitNav()
    }

    val focus by vm.focusNode.collectAsState()
    LaunchedEffect(focus) {
        val num = focus ?: return@LaunchedEffect
        vm.focusNode.value = null
        val at = nodeCenter(num) ?: return@LaunchedEffect
        pinned = num
        map.controller.setZoom(max(map.zoomLevelDouble, 15.0))
        map.controller.setCenter(at)
        selectNode(num)
    }

    /** Toque largo sobre un nodo: si tiene antena cargada, muestra el alcance; si no, pide los datos. */
    fun longPressNode(num: Int) {
        selectNode(num)
        if (antennas[num] != null) rangeNode = num else antennaFor = num
    }

    // Vidrio esmerilado solo con el mapa quieto: desenfocar cuadro a cuadro mientras se arrastra
    // es caro en celulares modestos (en un moto e22 bajaba de ~40 a ~13 cuadros por segundo).
    // Mientras se mueve, los paneles quedan con un tinte oscuro; al soltar, se esmerilan.
    val haze = LocalHaze.current
    val blurScope = rememberCoroutineScope()
    DisposableEffect(map, haze) {
        if (haze == null) return@DisposableEffect onDispose {}
        val idleBlur = dev.chrisbanes.haze.HazeDefaults.blurEnabled() // false en Android 11 o anterior
        var settle: kotlinx.coroutines.Job? = null
        fun moved() {
            if (!idleBlur) return
            haze.blurEnabled = false
            settle?.cancel()
            settle = blurScope.launch { delay(350); haze.blurEnabled = true }
        }
        val l = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean { moved(); return false }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean { moved(); return false }
        }
        map.addMapListener(l)
        onDispose { map.removeMapListener(l); settle?.cancel(); haze.blurEnabled = idleBlur }
    }

    // Reloj para que los colores "perdido" se actualicen solos.
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() / 1000 } }

    val scaleBar = remember {
        ScaleBarOverlay(map).apply {
            setAlignBottom(true)
            setAlignRight(true)
            setScaleBarOffset(dpPx(context, 12f).toInt(), dpPx(context, 12f + bottomInset.value).toInt())
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
    // Los ajenos que no se oyen hace más de 30 min no se dibujan (vuelven solos al oírlos de
    // nuevo). Los del grupo y el propio siempre están: de esos importa ver que se perdieron.
    val visibleNodes = nodes.values.filter { n ->
        n.lat != null && n.lon != null && (
            (n.num == pinned && pinned == selectedNode) ||
                ((showOthers || isMember(n)) && (n.num == me || isMember(n) || !Signal.isStale(n, now)))
            )
    }
    val activeWpts = waypoints.values.filter { it.isActive(now) }

    // clipToBounds: el MapView de osmdroid dibuja fuera de su lugar y taparía la barra de arriba.
    Box(modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { map },
            // Fuente del vidrio esmerilado: los paneles de arriba desenfocan lo que hay acá.
            modifier = Modifier.fillMaxSize().clipToBounds()
                .then(LocalHaze.current?.let { Modifier.hazeSource(it) } ?: Modifier),
            update = { mv ->
                scaleBar.setScaleBarOffset(dpPx(mv.context, 12f).toInt(), dpPx(mv.context, 12f + bottomInset.value).toInt())
                // De día, sobre el mapa claro, el cian fino no se lee: la escala va en negro.
                val scaleColor = if (Appearance.isDay) android.graphics.Color.BLACK else HudCyan.toArgb()
                scaleBar.textPaint.color = scaleColor; scaleBar.barPaint.color = scaleColor
                mv.setBackgroundColor(HudBg.toArgb())
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
                if (showMeasured) mv.overlays.add(measureOverlay)
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
                coverage?.takeIf { rangeNode != null }?.let { cv ->
                    mv.overlays.add(GroundOverlay().apply {
                        setImage(cv.bitmap)
                        setPosition(GeoPoint(cv.north, cv.west), GeoPoint(cv.south, cv.east))
                    })
                }
                rangeNode?.takeIf { coverage == null }?.let { num ->
                    val at = nodeCenter(num) ?: return@let
                    val est = effAntenna(num)?.let { Range.estimate(it, preset) } ?: return@let
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
                nav?.let { s ->
                    val here = fix?.let { GeoPoint(it.latitude, it.longitude) }
                    val dest = GeoPoint(s.target.lat, s.target.lon)
                    val route = s.route?.points?.map { GeoPoint(it.first, it.second) }
                    val c = RouteColor.toArgb()
                    if (route != null && route.size >= 2) {
                        // Halo ancho y translúcido + trazo fino: se lee sobre el mapa oscuro y el claro.
                        mv.overlays.add(Polyline(mv).apply {
                            setPoints(route)
                            outlinePaint.color = c; outlinePaint.alpha = 70
                            outlinePaint.strokeWidth = dpPx(mv.context, 11f)
                            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                            infoWindow = null; setOnClickListener { _, _, _ -> false }
                        })
                        mv.overlays.add(Polyline(mv).apply {
                            setPoints(route)
                            outlinePaint.color = c
                            outlinePaint.strokeWidth = dpPx(mv.context, 4f)
                            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                            infoWindow = null; setOnClickListener { _, _, _ -> false }
                        })
                        // Del final de la ruta al destino, si el nodo se movió o el punto está fuera del camino.
                        mv.overlays.add(Polyline(mv).apply {
                            setPoints(listOf(route.last(), dest))
                            outlinePaint.color = c
                            outlinePaint.strokeWidth = dpPx(mv.context, 2f)
                            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dpPx(mv.context, 4f), dpPx(mv.context, 4f)), 0f)
                            infoWindow = null; setOnClickListener { _, _, _ -> false }
                        })
                    } else if (here != null) {
                        // Sin ruta todavía (o sin datos de la zona): línea recta punteada.
                        mv.overlays.add(Polyline(mv).apply {
                            setPoints(listOf(here, dest))
                            outlinePaint.color = c
                            outlinePaint.strokeWidth = dpPx(mv.context, 3f)
                            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dpPx(mv.context, 10f), dpPx(mv.context, 6f)), 0f)
                            isGeodesic = true
                            infoWindow = null; setOnClickListener { _, _, _ -> false }
                        })
                    }
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
                        val lost = Signal.isLost(n, now)
                        val member = isMember(n)
                        val glyph = when {
                            lost -> Glyph.LOST
                            !member -> Glyph.foreignOf(n)
                            Signal.isBaseRole(n) -> Glyph.BASE
                            Signal.isFixedRole(n) -> Glyph.FIXED
                            else -> Glyph.MEMBER
                        }
                        val ni = chipIcon(
                            mv.context, label.uppercase(), glyph,
                            bars = if (member && !lost) signalBars(Signal.quality(n, now)) else null,
                            suffix = if (lost && member) agoText(now, n.lastHeard).removePrefix("hace ") else null,
                            repeats = !member && !n.mute,
                            tint = if (member && !lost) io.github.protohommer.meshpaper.ui.theme.memberColor(n.num).toArgb() else null,
                        )
                        icon = ni.first
                        setAnchor(ni.second, 0.5f)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> selectNode(n.num); true }
                    })
                }
                if (guided && fix != null) {
                    navMarker.icon = navArrowIcon(mv.context)
                    mv.overlays.add(navMarker)
                } else fix?.let { f ->
                    // El teléfono y el nodo conectado están en el mismo lugar: un solo marcador
                    // con el nombre del nodo, para no confundirlo con otro nodo parecido.
                    val myLabel = me?.let { nodes[it] }?.let { it.shortName.ifEmpty { it.id.takeLast(4) } }
                    mv.overlays.add(NodeMarker(mv, onLong = { me?.let { longPressNode(it) } }).apply {
                        position = GeoPoint(f.latitude, f.longitude)
                        val ni = chipIcon(mv.context, "VOS", Glyph.ME)
                        icon = ni.first
                        setAnchor(ni.second, 0.5f)
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
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 38.dp + bottomInset)
                .background(HudBg.copy(alpha = 0.45f))
                .clickable { uri.openUri("https://www.openstreetmap.org/copyright") }
                .padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = HudTextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // Marco tipo visor.
        Box(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, bottom = 6.dp + bottomInset, top = topInset + 2.dp).hudCorners(len = 22.dp))

        coverage?.takeIf { rangeNode != null && nav == null }?.let { cv ->
            CoverageLegend(
                cv, Modifier.align(Alignment.TopStart).padding(top = topInset).padding(12.dp), coverageMin,
                onToggle = { coverageMin = !coverageMin; mapPrefs.edit().putBoolean("coverage_min", coverageMin).apply() },
            )
        }

        if (guided) nav?.let { s ->
            TurnBanner(s, heading, Modifier.align(Alignment.TopStart).padding(top = topInset).padding(12.dp))
        } else nav?.let { s ->
            NavPanel(
                s, Modifier.align(Alignment.TopStart).padding(top = topInset).padding(12.dp),
                onCenter = { fitNav() },
                onSave = { saveCorridor() }.takeIf { s.route != null },
                saving = saving?.takeIf { s.route != null },
            )
        }

        // Herramientas.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = topInset, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!guided) HudIconButton("+", onClick = { map.controller.zoomIn() })
            if (!guided) HudIconButton("−", onClick = { map.controller.zoomOut() })
            if (!guided) HudIconButton("YO", enabled = fix != null, onClick = {
                fix?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude), max(map.zoomLevelDouble, 16.0), 600L) }
            })
            if (!guided) HudIconButton("TODO", enabled = visibleNodes.isNotEmpty(), onClick = { fitAll(map, visibleNodes, fix) })
            if (!guided) HudIconButton("RAS", active = showTracks, onClick = { showTracks = !showTracks })
            if (!guided && measurements.isNotEmpty()) HudIconButton("MED", active = showMeasured, onClick = { showMeasured = !showMeasured })
            if (!guided) HudIconButton("REP", active = showOthers, onClick = { showOthers = !showOthers })
            if (!guided) HudIconButton("CAPA", onClick = { showLayers = true })
        }

        val bottom = Modifier.align(Alignment.BottomStart).padding(bottom = bottomInset).padding(12.dp)
        when {
            selectedNode != null && nodes[selectedNode] != null -> {
                val n = nodes.getValue(selectedNode!!)
                NodeCard(
                    vm, n, me, isMember(n), fix, now, bottom, onMessage,
                    collapsed = cardMin, onToggle = { cardMin = !cardMin; mapPrefs.edit().putBoolean("card_min", cardMin).apply() },
                    antenna = effAntenna(n.num), preset = preset, showingRange = rangeNode == n.num,
                    coverage = coverage.takeIf { rangeNode == n.num }, coverageMsg = coverageMsg.takeIf { rangeNode == n.num },
                    fromGps = antennas[n.num]?.gpsHeight == true && gpsHeights[n.num]?.aboveGround != null,
                    onAntenna = { antennaFor = n.num },
                    onRange = { rangeNode = if (rangeNode == n.num) null else n.num },
                    onClose = { selectedNode = null; rangeNode = null; stack = emptyList() },
                    onProfile = if (n.num != me && me?.let { nodeCenter(it) } != null && nodeCenter(n.num) != null) {
                        { profileFor = n.num }
                    } else null,
                    stack = stack.mapNotNull { nodes[it] }.takeIf { it.size > 1 }.orEmpty(),
                    onPick = { selectedNode = it },
                    onGo = if (n.num != me && n.lat != null && n.lon != null) {
                        { startNav(Nav.Target(n.num, null, n.shortName.ifEmpty { n.longName.ifEmpty { n.id } }, n.lat, n.lon)) }
                    } else null,
                )
            }
            selectedWpt != null && waypoints[selectedWpt] != null ->
                WaypointCard(
                    vm, waypoints.getValue(selectedWpt!!), nodes, fix, now, bottom, onClose = { selectedWpt = null },
                    collapsed = cardMin, onToggle = { cardMin = !cardMin; mapPrefs.edit().putBoolean("card_min", cardMin).apply() },
                    onGo = { w -> startNav(Nav.Target(null, w.id, "${w.iconText} ${w.name}".trim(), w.lat, w.lon)) },
                )
            guided -> nav?.let { s ->
                GuidedBottom(
                    s, following, onRecenter = { following = true; guidedZoomSet = false },
                    onOverview = { Nav.setGuided(false); fitNav() }, modifier = bottom,
                )
            }
            // Navegando, la leyenda taparía la ruta.
            nav != null -> Unit
            else -> Legend(
                bottom, expanded = showLegend,
                onToggle = { showLegend = !showLegend; mapPrefs.edit().putBoolean("legend", showLegend).apply() },
                members = nodes.values.count { isMember(it) && it.num != me } + if (hasGroup && me != null) 1 else 0,
                relays = nodes.values.count { !isMember(it) && !it.mute },
                // Activos: oídos hace poco (1 h; routers y Base 13 h). El total incluye los de hace días.
                relaysActive = nodes.values.count { !isMember(it) && !it.mute && !Signal.isLost(it, now) },
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
            gps = remember(num, n?.alt, n?.lat, n?.lon) { n?.let { gpsHeightOf(context, it.alt, it.lat, it.lon, network = false) } },
            approxM = n?.positionErrorM,
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
            aName = label(myNum), a = Profile.End(from.latitude, from.longitude, effAntenna(myNum) ?: handheld.copy(txDbm = myTx)),
            aGuess = antennas[myNum] == null,
            bName = label(num), b = Profile.End(to.latitude, to.longitude, effAntenna(num) ?: handheld),
            bGuess = antennas[num] == null,
            approxM = listOfNotNull(nodes[myNum]?.positionErrorM.takeIf { fix == null }, nodes[num]?.positionErrorM).maxOrNull(),
            freqMHz = Profile.frequencyMHz(lora), preset = preset,
            onDismiss = { profileFor = null },
        )
    }
    if (showLayers) {
        LayersDialog(map, dark, onDark = { dark = it; MapTiles.setDarkMap(context, it) }, onClose = { showLayers = false })
    }
}

/** Mapa oscuro: invierte el brillo y lo lleva a azules (las rutas quedan marcadas por sus bordes). */
/** Mapa oscuro del tema: azulado en noche, gris neutro en nocturno (el color queda para lo que importa). */
internal val darkFilter: ColorMatrixColorFilter get() = if (Appearance.isNocturne) neutralDarkFilter else blueDarkFilter

/** Nocturno: invierte el brillo y lo deja en grises fríos, apagados. */
private val neutralDarkFilter = ColorMatrixColorFilter(ColorMatrix().apply {
    fun row(a: Float, c: Float) = floatArrayOf(-0.299f * a, -0.587f * a, -0.114f * a, 0f, 255f * a + c)
    // Más contraste (antes 0.40/0.43/0.48): el relieve, los bosques y las sierras se marcan más;
    // el corrimiento negativo deja el fondo (el blanco del mapa) bien negro.
    set(row(0.80f, -6f) + row(0.84f, -5f) + row(0.90f, -3f) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
})

private val blueDarkFilter = ColorMatrixColorFilter(ColorMatrix().apply {
    fun row(a: Float, c: Float) = floatArrayOf(-0.299f * a, -0.587f * a, -0.114f * a, 0f, 255f * a + c)
    set(row(0.33f, 6f) + row(0.55f, 12f) + row(0.78f, 20f) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
})

/** Escala de la cobertura: CMRmap de "al límite" a "fuerte", en dBm recibidos. */
@Composable
private fun CoverageLegend(cv: Coverage.Result, modifier: Modifier, collapsed: Boolean, onToggle: () -> Unit) {
    HudPanel(
        modifier.width(210.dp), title = "Cobertura",
        padding = androidx.compose.foundation.layout.PaddingValues(10.dp), collapsed = collapsed, onToggle = onToggle,
    ) {
        val stops = (0..8).map { androidx.compose.ui.graphics.Color(Coverage.color(it / 8f, 0xFF)) }
        Box(
            Modifier.fillMaxWidth().height(10.dp)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(stops)),
        )
        Row(Modifier.fillMaxWidth()) {
            val lo = cv.sensitivityDbm.roundToInt()
            HudLabel("$lo dBm", Modifier.weight(1f))
            HudLabel("${lo + Coverage.FULL_SCALE_DB.toInt()}+")
        }
        HudLabel("Al límite → fuerte")
        HudLabel("Hacia un nodo de mano")
        if (cv.usedCover) HudLabel("Con monte y ciudad (ESA WorldCover)", maxLines = 2)
    }
}

@Composable
private fun Legend(modifier: Modifier, expanded: Boolean, onToggle: () -> Unit, members: Int, relays: Int, relaysActive: Int) {
    HudPanel(
        modifier.width(220.dp), title = "Grupo $members · Rep $relaysActive/$relays", collapsed = !expanded, onToggle = onToggle,
        padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphRow(Glyph.MEMBER, "Del grupo")
            GlyphRow(Glyph.FIXED, "Fijo del grupo (router)")
            GlyphRow(Glyph.BASE, "Base del grupo")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NodeGlyph(Glyph.FOREIGN, size = 20.dp)
                NodeGlyph(Glyph.FOREIGN_FIXED, size = 20.dp)
                NodeGlyph(Glyph.FOREIGN_BASE, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                HudLabel("Ajenos (violeta: repiten)", color = HudText)
            }
            GlyphRow(Glyph.LOST, "Perdido")
            GlyphRow(Glyph.ME, "Vos")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SignalBars(3, false); HudLabel("directo", color = HudText)
                SignalBars(2, false); HudLabel("2-3", color = HudText)
                SignalBars(1, false); HudLabel("4-5", color = HudText)
            }
            HudLabel("Rep 4/35 = activos (última hora) / todos", maxLines = 3)
            HudLabel("Ajeno sin oír 30 min = oculto (router y base: 13 h)", maxLines = 3)
            HudLabel("Largo en el mapa = waypoint", maxLines = 2)
            HudLabel("Largo en un nodo = alcance", maxLines = 2)
        }
    }
}

@Composable
private fun GlyphRow(g: Glyph, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NodeGlyph(g, size = 20.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = HudText)
    }
}

/** Un dato de la ficha en su tarjetita: rótulo arriba, valor (y barras de señal) abajo. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, bars: Int? = null, warn: Boolean = false) {
    Column(
        modifier.background(HudBg, HudButtonShape).border(1.dp, HudLine, HudButtonShape).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        HudLabel(label)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            bars?.let { SignalBars(it, false); Spacer(Modifier.width(5.dp)) }
            // Mono: cada carácter ocupa lo mismo; "21,6 km" (7) no entraba a 15sp en un cuarto del ancho.
            val len = value.length + (if (bars != null) 2 else 0)
            val fs = when { len >= 9 -> 12.sp; len >= 7 -> 13.sp; else -> 15.sp }
            Text(value, style = io.github.protohommer.meshpaper.ui.theme.MonoValue.copy(fontWeight = FontWeight.Medium, fontSize = fs), color = if (warn) SigWeak else HudText, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun NodeCard(
    vm: MeshViewModel, n: MeshNode, me: Int?, member: Boolean, fix: Location?, now: Long, modifier: Modifier,
    onMessage: (Int) -> Unit,
    collapsed: Boolean, onToggle: () -> Unit,
    antenna: Antenna?, preset: ModemPreset, showingRange: Boolean,
    coverage: Coverage.Result? = null, coverageMsg: String? = null,
    onAntenna: () -> Unit, onRange: () -> Unit, onClose: () -> Unit,
    stack: List<MeshNode> = emptyList(), onPick: (Int) -> Unit = {},
    onProfile: (() -> Unit)? = null,
    fromGps: Boolean = false,
    onGo: (() -> Unit)? = null,
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
    var askForget by remember(n.num) { mutableStateOf(false) }
    val tone = Signal.tone(n, me, member, now)
    val kind = when (tone) {
        Tone.ME -> "Este nodo"
        Tone.RELAY -> "Repetidor ajeno"
        Tone.MUTE -> "Ajeno · no repite"
        Tone.FIXED -> (if (Signal.isBaseRole(n)) "Grupo · base" else "Grupo · fijo") + (if (Signal.isLost(n, now)) " · sin noticias" else "")
        else -> if (Signal.isLost(n, now)) "Grupo · sin noticias" else "Grupo"
    }
    // Minimizada queda la barra con el nombre, para ver el mapa (o la cobertura) de abajo.
    val title = if (collapsed) "$kind · ${n.shortName.ifEmpty { n.longName.ifEmpty { n.id } }}" else kind
    val lostNow = n.num != me && Signal.isLost(n, now)
    val accent = when {
        lostNow -> SigWeak
        n.num == me -> SigMe
        else -> HudCyan
    }
    HudPanel(
        modifier.fillMaxWidth(), title = title, accent = accent, collapsed = collapsed, onToggle = onToggle, onClose = onClose,
        padding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Column(Modifier.padding(end = 8.dp)) {
        if (stack.size > 1) {
            // Varios nodos en el mismo lugar: se elige cuál ver.
            HudLabel("${stack.size} nodos en este lugar")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (o in stack) {
                    FilterChip(
                        selected = o.num == n.num, onClick = { onPick(o.num) },
                        label = { Text(o.shortName.ifEmpty { o.id.takeLast(4) }, style = io.github.protohommer.meshpaper.ui.theme.MonoValue) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                n.shortName.ifEmpty { "?" }.uppercase(),
                style = io.github.protohommer.meshpaper.ui.theme.MonoValue.copy(fontSize = 24.sp, lineHeight = 28.sp),
                color = when {
                    lostNow -> SigWeak
                    n.num == me -> SigMe
                    member -> io.github.protohommer.meshpaper.ui.theme.memberColor(n.num)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                n.longName.ifEmpty { n.id }, style = MaterialTheme.typography.bodyLarge, color = HudTextDim, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (n.num != me) vm.duplicateOf(n)?.let { newer ->
            Text(
                "Hay otro \"${n.longName}\" oído ${agoText(now, newer.lastHeard)}: este parece un fantasma (Olvidar).",
                style = MaterialTheme.typography.bodySmall, color = SigWeak,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Lo necesario a la vista: oído, enlace y distancia (con hacia dónde). El resto, en "Más".
        val bearing = if (fix != null && n.num != me && n.lat != null && n.lon != null) {
            PhoneLocation.distanceAndBearing(fix.latitude, fix.longitude, n.lat, n.lon)
        } else null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Oído", if (n.num == me) "ahora" else agoText(now, n.lastHeard).removePrefix("hace "), Modifier.weight(1f), warn = lostNow)
            StatTile(
                // Directo: "dir."; si no, la cantidad de saltos (las barras dicen la calidad).
                "Enlace", when (val h = n.hopsAway) { null -> "—"; 0 -> "dir."; else -> "$h" },
                Modifier.weight(1f), bars = if (n.num != me && !lostNow) signalBars(Signal.quality(n, now)) else null,
            )
            StatTile(
                "Dist.", bearing?.let { (d, b) -> "${PhoneLocation.formatDistance(d)} ${PhoneLocation.cardinal(b)}" } ?: "—",
                Modifier.weight(1.3f),
            )
        }
        // Sin ficha en el nodo conectado: decirlo, y dejar pedírsela por radio.
        if (n.num != me && (n.noIdent || n.identCached)) {
            var asked by remember(n.num) { mutableStateOf<String?>(null) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    asked ?: if (n.identCached) "El nodo perdió su ficha: nombre y rol los recuerda la app."
                        else "Todavía no llegó su ficha: no se sabe el nombre ni el rol.",
                    style = MaterialTheme.typography.bodySmall, color = HudTextDim, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    asked = if (vm.requestNodeInfo(n.num)) "Pedida. Si la contesta, llega en un rato (depende de los saltos)."
                        else "No se pudo pedir ahora (sin conexión, o ya se pidió hace menos de un minuto)."
                }) { Text("Pedir datos", color = HudText, fontWeight = FontWeight.Normal, softWrap = false) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Acciones: mensaje (lleno), ir (naranja: la ruta) y "Más", que despliega el resto.
        var more by remember { mutableStateOf(false) } // se mantiene al pasar de un nodo a otro
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (n.num != me) HudButton(onClick = { onMessage(n.num) }, Modifier.weight(1.4f)) { Text("Mensaje", softWrap = false) }
            onGo?.let {
                androidx.compose.material3.OutlinedButton(
                    onClick = it, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = HudButtonShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, RouteColor),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = RouteColor),
                ) { Text("Ir", softWrap = false) }
            }
            HudOutlinedButton(onClick = { more = !more }, Modifier.weight(1f)) { Text(if (more) "Menos" else "Más", softWrap = false) }
        }
        val est = antenna?.let { Range.estimate(it, preset) }
        val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
        // El alcance prendido se tiene que poder apagar aunque "Más" esté cerrado.
        if (!more && showingRange) {
            TextButton(onClick = onRange, contentPadding = tight) {
                Text("Ocultar alcance", color = HudText, fontWeight = FontWeight.Normal, softWrap = false)
            }
        }
        if (more) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.HorizontalDivider(color = HudLine)
            Spacer(Modifier.height(8.dp))
            val extra = buildList {
                n.battery?.let { add(if (it > 100) "batería: USB" else "batería $it %") }
                if (n.hopsAway == 0) n.rssi?.let { add("RSSI $it dBm") }
                if (n.hopsAway == 0 && n.snr != 0f) add("SNR %.1f dB".format(n.snr))
                groundM?.let { add("terreno ${it.roundToInt()} m") }
                tracks[n.num]?.size?.takeIf { it > 1 }?.let { add("rastro $it pts") }
            }
            if (extra.isNotEmpty()) {
                Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp, fontSize = 12.sp), color = HudTextDim)
            }
            // El modelo en su propia línea: con RSSI, distancia y rastro no entraba y se cortaba.
            val calandria by vm.calandria.collectAsState()
            val cal = calandria[n.num]
            boardName(cal, n.hw)?.let { HudLabel(it, maxLines = 2) }
            cal?.let { CalandriaFirmware(it, showLink = false) }
            if (antenna != null && est != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Antena %s dBi a %s m".format(fmtNum(antenna.gainDbi), fmtNum(antenna.heightM)) + (if (fromGps) " (GPS)" else "") +
                        " · alcance ≈ ${PhoneLocation.formatDistance(est.urbanM.toFloat())} ciudad, " +
                        "${PhoneLocation.formatDistance(est.openM.toFloat())} campo",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp, fontSize = 12.sp), color = HudTextDim,
                )
                if (showingRange) Text(
                    when {
                        coverageMsg != null -> coverageMsg
                        coverage != null -> (if (coverage.usedCover) "Con relieve, monte y ciudad" else "Con relieve") +
                            ": señal en ${coverage.coveredPct}% del círculo de " +
                            "${(Coverage.RADIUS_M / 1000).toInt()} km. Sin pintar = sombra o muy lejos."
                        else -> "Hacia un nodo de mano (1,5 m). Sin relieve: un cerro en el medio lo corta."
                    },
                    style = MaterialTheme.typography.bodySmall, color = HudTextDim,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (antenna != null && est != null) {
                    TextButton(onClick = onRange, contentPadding = tight) {
                        Text(if (showingRange) "Ocultar alcance" else "Ver alcance", color = HudText, fontWeight = FontWeight.Normal, softWrap = false)
                    }
                }
                TextButton(onClick = onAntenna, contentPadding = tight) { Text("Antena", color = HudText, fontWeight = FontWeight.Normal, softWrap = false) }
                onProfile?.let { TextButton(onClick = it, contentPadding = tight) { Text("Perfil", color = HudText, fontWeight = FontWeight.Normal, softWrap = false) } }
            }
            if (n.num != me) {
                var asked by remember(n.num) { mutableStateOf<String?>(null) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { vm.setFavorite(n.num, !n.favorite) }, contentPadding = tight) {
                        // Favorito en el nodo conectado: en rol Base, prioriza lo que va y viene de sus favoritos.
                        Text(if (n.favorite) "★ Favorito" else "☆ Favorito", color = if (n.favorite) SigMe else HudText, fontWeight = FontWeight.Normal, softWrap = false)
                    }
                    // Refrescar nombre, rol y clave de cualquier nodo (gasta aire: como mucho 1 por minuto)
                    TextButton(onClick = {
                        asked = if (vm.requestNodeInfo(n.num)) "Datos pedidos: si contesta, se actualizan en un rato."
                            else "No se pudo pedir ahora (sin conexión, o se pidió hace menos de un minuto)."
                    }, contentPadding = tight) { Text("Pedir datos", color = HudText, fontWeight = FontWeight.Normal, softWrap = false) }
                    TextButton(onClick = { askForget = true }, contentPadding = tight) {
                        Text("Olvidar", color = HudTextDim, fontWeight = FontWeight.Normal, softWrap = false)
                    }
                }
                asked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HudTextDim) }
            }
        }
        }
    }
    if (askForget) {
        AlertDialog(
            onDismissRequest = { askForget = false },
            title = { Text("¿Olvidar ${n.shortName.ifEmpty { n.id }}?") },
            text = {
                Text(
                    "El nodo conectado lo borra de su lista y sale del mapa. Si en realidad sigue activo, " +
                        "vuelve a aparecer la próxima vez que transmita."
                )
            },
            confirmButton = {
                TextButton(onClick = { askForget = false; if (vm.forgetSome(listOf(n.num)) > 0) onClose() }) { Text("Olvidar") }
            },
            dismissButton = { TextButton(onClick = { askForget = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun WaypointCard(
    vm: MeshViewModel, w: Wpt, nodes: Map<Int, MeshNode>, fix: Location?, now: Long,
    modifier: Modifier, onClose: () -> Unit, onGo: (Wpt) -> Unit,
    collapsed: Boolean, onToggle: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val title = if (collapsed) "Waypoint · ${w.iconText} ${w.name}".trim() else "Waypoint"
    HudPanel(modifier.width(280.dp), title = title, collapsed = collapsed, onToggle = onToggle, onClose = onClose) {
        Text("${w.iconText}  ${w.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (w.description.isNotEmpty()) Text(w.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(3.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { confirm = true }) {
                Text(if (vm.canDeleteForAll(w)) "BORRAR" else "OCULTAR", color = MaterialTheme.colorScheme.error, softWrap = false)
            }
            TextButton(onClick = { onGo(w) }) { Text("Ir", color = RouteColor, softWrap = false) }
            TextButton(onClick = onClose) { Text("Cerrar", softWrap = false) }
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

/**
 * Altura de la antena sobre el suelo según el GPS: altitud que manda el nodo (sobre el nivel del
 * mar) menos el terreno del relieve en ese punto. [aboveGround] null si falta alguno de los dos.
 */
data class GpsHeight(val altM: Int?, val groundM: Double?) {
    /** GPS − relieve, tal cual. */
    val raw: Double? get() = if (altM == null || groundM == null) null else altM - groundM

    /**
     * Fuera de esto el número no es una antena: el GPS (o el relieve) erró. Antes se recortaba a
     * 0,5..500 m en silencio, y una antena "enterrada" o "a 300 m" arruinaba el alcance sin aviso.
     */
    val implausible: Boolean get() = raw?.let { it < -5.0 || it > MAX_PLAUSIBLE_M } == true

    /** La altura sobre el suelo, o null si falta algo o no es creíble (entonces vale la cargada a mano). */
    val aboveGround: Double? get() = raw?.takeIf { !implausible }?.coerceAtLeast(0.5)

    companion object { const val MAX_PLAUSIBLE_M = 150.0 }
}

/** Bloquea (lee el relieve): fuera del hilo principal, salvo con [network] = false y poco uso. */
fun gpsHeightOf(ctx: Context, alt: Int?, lat: Double?, lon: Double?, network: Boolean = MapTiles.useInternet(ctx)): GpsHeight =
    GpsHeight(alt, if (lat != null && lon != null) Dem.elevation(ctx, lat, lon, network) else null)

private fun gpsHint(g: GpsHeight?): String = when {
    g == null || g.altM == null -> "Este nodo no está mandando su altitud ahora. Con el GPS de la placa o del " +
        "celular (iPhone, o Android 14 o más nuevo) la manda."
    g.groundM == null -> "Manda su altitud (${g.altM} m), pero falta el relieve de la zona: bajalo en Ajustes → Mapas sin conexión."
    g.implausible -> "Ahora: ${g.altM} m (GPS) − ${g.groundM.roundToInt()} m (terreno) = ${g.raw!!.roundToInt()} m sobre el suelo, " +
        "y eso no es creíble para una antena (el GPS erra en altura, sobre todo bajo techo). Se usa la altura cargada a mano."
    else -> "Ahora: ${g.altM} m (GPS) − ${g.groundM.roundToInt()} m (terreno) = ${fmtNum(g.aboveGround!!)} m sobre el suelo. " +
        "El GPS erra ±10 m en altura: sirve para torres, cerros y edificios, no para medir un mástil corto."
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
private fun AntennaDialog(
    name: String, current: Antenna?, defaultTx: Int, gps: GpsHeight?, approxM: Double? = null,
    onSave: (Antenna?) -> Unit, onDismiss: () -> Unit,
) {
    var useGps by remember { mutableStateOf(current?.gpsHeight ?: false) }
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
                Hint(if (useGps) "Se usa cuando el nodo no manda su altitud" else "En la mano ≈ 1,5 · techo de casa ≈ 6–8")
                Row(
                    Modifier.fillMaxWidth().clickable { useGps = !useGps },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = useGps, onCheckedChange = { useGps = it })
                    Text("Tomar la altura del GPS del nodo", style = MaterialTheme.typography.bodyMedium)
                }
                Hint(gpsHint(gps))
                if (approxM != null && approxM > 100) Hint(
                    "Ojo: su posición llega redondeada (±${PhoneLocation.formatDistance(approxM.toFloat())}). " +
                        "En las sierras el terreno de ese punto puede no ser el de donde está."
                )
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
            TextButton(onClick = { onSave(Antenna(g!!, h!!, c!!, p!!, gpsHeight = useGps)) }, enabled = ok) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Borrar") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
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
        title = { Text("Nuevo waypoint") },
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
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EXPIRES.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = expire == i, onClick = { expire = i }, label = { Text(label, softWrap = false) })
                    }
                }
                HudLabel("Sale por el canal del grupo · solo vos lo borrás", maxLines = 2)
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

@Composable
private fun IconChip(cp: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(String(Character.toChars(cp))) })
}

// ---------- Capas / mapas offline ----------

@Composable
private fun LayersDialog(map: MapView, dark: Boolean, onDark: (Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(MapTiles.archives(context)) }
    var online by remember { mutableStateOf(MapTiles.useInternet(context)) }
    var url by remember { mutableStateOf(MapTiles.customUrl(context)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
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


    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Capas y mapas offline") },
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
                Spacer(Modifier.padding(2.dp))
                if (files.isEmpty()) HudLabel("Ninguno todavía")
                for (f in files) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name} (${MapTiles.formatSize(f.length())})", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { MapTiles.delete(f); reload() }) { Text("Quitar", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudOutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Importar .mbtiles") }
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
                ) { Text("Usar este servidor") }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                HudLabel("Bajar zonas para usar sin señal", color = HudCyan)
                Text(
                    "Está en Ajustes → Mapas sin conexión: elegís los cuadrantes en una cuadrícula y se baja " +
                        "el mapa (si la fuente lo permite) y el relieve, todo junto. Los datos para \"Ir a\" " +
                        "están en Ajustes → Rutas sin conexión.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Listo") } },
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
    // Abajo están la leyenda/ficha y a la derecha las herramientas: el encuadre se agranda hacia
    // esos lados para que ningún nodo quede tapado (antes se centraba y los de abajo quedaban detrás).
    val b = BoundingBox.fromGeoPointsSafe(pts)
    val lat = maxOf(b.latitudeSpan, 0.002)
    val lon = maxOf(b.longitudeSpanWithDateLine, 0.002)
    val box = BoundingBox(
        (b.latNorth + lat * 0.30).coerceAtMost(85.0), b.lonEast + lon * 0.30,
        (b.latSouth - lat * 0.75).coerceAtLeast(-85.0), b.lonWest - lon * 0.08,
    )
    mv.zoomToBoundingBox(box, true, 48)
}

// ---------- Íconos ----------

private val iconCache = HashMap<String, BitmapDrawable>()

private fun dpPx(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

/**
 * Nodo: borde de [color] con el nombre corto adentro (en [labelColor]). [ring] = borde exterior
 * con la señal (fijos del grupo). [compact] = más chico y sin halo (ajenos). [diamond] = rombo
 * en vez de círculo (instalación fija), y el borde de la señal copia la forma.
 * Perdido = borde punteado y apagado.
 */
private fun nodeIcon(
    ctx: Context, label: String, color: Int, filled: Boolean, lost: Boolean,
    ring: Int? = null, labelColor: Int = color, compact: Boolean = false, diamond: Boolean = false,
    /** Nocturno: barras de señal abajo a la derecha (0 a 3); null = sin barras. */
    bars: Int? = null,
): BitmapDrawable =
    iconCache.getOrPut("n:$label:$color:$filled:$lost:$ring:$labelColor:$compact:$diamond:$bars:${Appearance.isDay}:${Appearance.isNocturne}") {
        // El rombo es un poco más grande: a la altura del texto es más angosto que el círculo.
        val base = if (ring != null) 46f else if (compact) 32f else 40f
        val inner = dpPx(ctx, if (diamond) base + 6f else base)
        // margen para el halo de neón, o para las barras de señal (nocturno)
        val glowPad = dpPx(ctx, if (bars != null) 9f else 5f)
        val size = (inner + 2 * glowPad).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = size / 2f       // centro
        val r = inner / 2f      // radio de la figura, sin el halo
        val ringR = r - dpPx(ctx, if (ring != null) 8f else if (compact) 2f else 5f)
        val alpha = if (lost) 130 else 255
        fun shape(radius: Float, paint: Paint) {
            if (!diamond) { c.drawCircle(m, m, radius, paint); return }
            val path = android.graphics.Path().apply {
                moveTo(m, m - radius); lineTo(m + radius, m); lineTo(m, m + radius); lineTo(m - radius, m); close()
            }
            c.drawPath(path, paint)
        }
        /** Halo de neón: el mismo borde, más ancho y desenfocado (bitmap en software: anda en todos). */
        fun glow(radius: Float, col: Int) {
            if (lost || !Appearance.neon) return
            shape(radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = col; this.alpha = 210
                style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 4f)
                strokeJoin = Paint.Join.ROUND
                maskFilter = android.graphics.BlurMaskFilter(dpPx(ctx, 4f), android.graphics.BlurMaskFilter.Blur.NORMAL)
            })
        }
        if (ring != null) {
            // borde de señal por fuera, con un borde oscuro para que se separe del principal
            glow(r - dpPx(ctx, 2.5f), ring)
            shape(r - dpPx(ctx, 2.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = markerBg()
                style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 4.5f)
                strokeJoin = Paint.Join.ROUND
            })
            shape(r - dpPx(ctx, 2.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = ring; this.alpha = alpha
                style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 2.5f)
                strokeJoin = Paint.Join.ROUND
            })
        } else {
            glow(ringR, color)
        }
        // fondo oscuro, apenas translúcido
        shape(ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = markerBg() })
        if (filled) shape(ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = if (lost) 25 else 60 })
        // borde
        shape(ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; this.alpha = alpha
            style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 2.5f)
            strokeJoin = Paint.Join.ROUND
            if (lost) pathEffect = DashPathEffect(floatArrayOf(dpPx(ctx, 4f), dpPx(ctx, 3f)), 0f)
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = labelColor; this.alpha = alpha
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = dpPx(ctx, if (label.length <= 2) 12f else if (compact) 8.5f else 9.5f)
            if (!lost && Appearance.neon) setShadowLayer(dpPx(ctx, 3f), 0f, 0f, labelColor)
        }
        c.drawText(label.take(4), m, m - (text.descent() + text.ascent()) / 2, text)
        if (bars != null && !lost) {
            // Tres barritas abajo a la derecha, sobre un fondo oscuro para que se lean sobre el mapa.
            val bw = dpPx(ctx, 2.6f)
            val gap = dpPx(ctx, 1.6f)
            val bh = dpPx(ctx, 9f)
            val x0 = size - dpPx(ctx, 2f) - 3 * bw - 2 * gap
            val y1 = size - dpPx(ctx, 2f)
            c.drawRoundRect(
                x0 - dpPx(ctx, 2f), y1 - bh - dpPx(ctx, 2f), size - dpPx(ctx, 0.5f), y1 + dpPx(ctx, 1.5f),
                dpPx(ctx, 2f), dpPx(ctx, 2f), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = markerBg() },
            )
            for (i in 0 until 3) {
                val h = bh * (0.45f + 0.275f * i)
                val x = x0 + i * (bw + gap)
                c.drawRect(x, y1 - h, x + bw, y1, Paint().apply {
                    this.color = if (i < bars) HudText.toArgb() else HudLine.toArgb()
                })
            }
        }
        BitmapDrawable(ctx.resources, bmp)
    }

/**
 * El teléfono (y el nodo conectado, que está con él): punto cian con retícula y, abajo, el nombre
 * corto del nodo. El bitmap es simétrico para que el anclaje al centro caiga justo en el punto.
 */
/** Fondo de los íconos del mapa: casi negro de noche; de día, vidrio claro translúcido. */
private fun markerBg(): Int =
    if (Appearance.isDay) android.graphics.Color.argb(215, 248, 250, 252) else android.graphics.Color.argb(200, 5, 9, 13)

private fun meIcon(ctx: Context, label: String?): BitmapDrawable = iconCache.getOrPut("me:$label:${Appearance.isDay}:${Appearance.isNocturne}") {
    val size = dpPx(ctx, 34f).toInt()
    val tag = if (label != null) dpPx(ctx, 16f).toInt() else 0
    val w = max(size, dpPx(ctx, 64f).toInt())
    val h = size + 2 * tag + dpPx(ctx, 8f).toInt() // margen para el halo
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = h / 2f
    val r = size / 2f
    val cyan = SigMe.toArgb()
    c.drawCircle(cx, cy, r - dpPx(ctx, 1f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; alpha = 40 })
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; strokeWidth = dpPx(ctx, 1.5f); setShadowLayer(dpPx(ctx, 3f), 0f, 0f, cyan) }
    val g = dpPx(ctx, 7f)
    c.drawLine(cx, cy - r, cx, cy - r + g, line); c.drawLine(cx, cy + r - g, cx, cy + r, line)
    c.drawLine(cx - r, cy, cx - r + g, cy, line); c.drawLine(cx + r - g, cy, cx + r, cy, line)
    c.drawCircle(cx, cy, dpPx(ctx, 6f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; setShadowLayer(dpPx(ctx, 5f), 0f, 0f, cyan) })
    c.drawCircle(cx, cy, dpPx(ctx, 4.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan })
    if (label != null) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = dpPx(ctx, 10f)
            setShadowLayer(dpPx(ctx, 3f), 0f, 0f, cyan)
        }
        val t = label.take(6).uppercase()
        val tw = text.measureText(t) + dpPx(ctx, 8f)
        val top = cy + r + dpPx(ctx, 1f)
        c.drawRect(cx - tw / 2, top, cx + tw / 2, top + dpPx(ctx, 14f),
            Paint().apply { color = markerBg() })
        c.drawText(t, cx, top + dpPx(ctx, 7f) - (text.descent() + text.ascent()) / 2, text)
    }
    BitmapDrawable(ctx.resources, bmp)
}

/** Navegación guiada: flecha del teléfono, apuntando hacia arriba (el mapa gira hacia donde uno va). */
private fun navArrowIcon(ctx: Context): BitmapDrawable = iconCache.getOrPut("nav:${Appearance.isDay}:${Appearance.isNocturne}") {
    val s = dpPx(ctx, 44f).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cyan = SigMe.toArgb()
    val cx = s / 2f
    val path = android.graphics.Path().apply {
        moveTo(cx, s * 0.12f)
        lineTo(s * 0.82f, s * 0.86f)
        lineTo(cx, s * 0.68f)
        lineTo(s * 0.18f, s * 0.86f)
        close()
    }
    c.drawCircle(cx, s / 2f, s * 0.46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; alpha = 45 })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; setShadowLayer(dpPx(ctx, 4f), 0f, 0f, cyan) })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 2f); color = android.graphics.Color.WHITE
        strokeJoin = Paint.Join.ROUND
    })
    BitmapDrawable(ctx.resources, bmp)
}

/**
 * Ícono de un nodo en el mapa (2.0): la forma (círculo del grupo, rombo fijo, rombo gris ajeno,
 * anillo cortado perdido, retícula vos) y al costado una etiqueta con el nombre y las barras de
 * señal. Devuelve el bitmap y dónde cae el punto del nodo (fracción del ancho), para el anclaje.
 */
private fun chipIcon(
    ctx: Context, label: String, glyph: Glyph, bars: Int? = null, suffix: String? = null, repeats: Boolean = false,
    /** Color de identidad del nodo (los del grupo): la forma y el nombre van de ese color. */
    tint: Int? = null,
): Pair<BitmapDrawable, Float> {
    val key = "c:$label:$glyph:$bars:$suffix:$repeats:$tint:${Appearance.isDay}"
    val shapeW = dpPx(ctx, 30f)
    val gap = dpPx(ctx, 4f)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = dpPx(ctx, if (glyph.foreign) 10.5f else 12f)
    }
    val shown = if (suffix != null) "$label · $suffix" else label.take(6)
    val padX = dpPx(ctx, 7f)
    val barsW = if (bars != null) dpPx(ctx, 6f) + dpPx(ctx, 15f) else 0f
    val chipW = padX * 2 + text.measureText(shown) + barsW
    val chipH = dpPx(ctx, if (glyph.foreign) 20f else 24f)
    val w = (shapeW + gap + chipW + dpPx(ctx, 1f)).toInt()
    val h = maxOf(shapeW, chipH).toInt() + 2
    val u = (shapeW / 2) / w
    val cached = iconCache[key]
    if (cached != null) return cached to u
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = shapeW / 2
    val cy = h / 2f
    val ink = tint ?: HudText.toArgb()
    val barInk = HudText.toArgb()
    val warn = SigWeak.toArgb()
    val me = SigMe.toArgb()
    val bg = markerBg()
    val sw = dpPx(ctx, 2.2f)
    fun stroke(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = sw; strokeJoin = Paint.Join.ROUND }
    fun fill(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
    fun diamond(r: Float) = android.graphics.Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
    fun hex(r: Float) = android.graphics.Path().apply {
        for (i in 0..5) {
            val a = Math.toRadians(60.0 * i - 90.0)
            val x = cx + r * kotlin.math.cos(a).toFloat(); val y = cy + r * kotlin.math.sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    when (glyph) {
        Glyph.MEMBER -> { c.drawCircle(cx, cy, dpPx(ctx, 9f), fill(bg)); c.drawCircle(cx, cy, dpPx(ctx, 9f), stroke(ink)) }
        Glyph.FIXED -> { c.drawPath(diamond(dpPx(ctx, 11f)), fill(bg)); c.drawPath(diamond(dpPx(ctx, 11f)), stroke(ink)) }
        Glyph.BASE -> { val hex = hex(dpPx(ctx, 10.5f)); c.drawPath(hex, fill(bg)); c.drawPath(hex, stroke(ink)) }
        // Ajenos: rellenos, violeta si repite (aporta cobertura), gris si no; la forma dice su rol.
        Glyph.FOREIGN, Glyph.FOREIGN_FIXED, Glyph.FOREIGN_BASE -> {
            val col = if (repeats) io.github.protohommer.meshpaper.ui.theme.SigRelay.toArgb() else SigMute.toArgb()
            val halo = dpPx(ctx, 1f)
            when (glyph) {
                Glyph.FOREIGN -> { c.drawCircle(cx, cy, dpPx(ctx, 5.5f) + halo, fill(bg)); c.drawCircle(cx, cy, dpPx(ctx, 5.5f), fill(col)) }
                Glyph.FOREIGN_FIXED -> { c.drawPath(diamond(dpPx(ctx, 7.5f) + halo), fill(bg)); c.drawPath(diamond(dpPx(ctx, 7.5f)), fill(col)) }
                else -> { c.drawPath(hex(dpPx(ctx, 7f) + halo), fill(bg)); c.drawPath(hex(dpPx(ctx, 7f)), fill(col)) }
            }
        }
        Glyph.LOST -> c.drawCircle(cx, cy, dpPx(ctx, 9f), stroke(warn).apply { pathEffect = DashPathEffect(floatArrayOf(dpPx(ctx, 4f), dpPx(ctx, 3f)), 0f) })
        Glyph.ME -> {
            c.drawCircle(cx, cy, dpPx(ctx, 12f), fill(me).apply { alpha = 36 })
            c.drawCircle(cx, cy, dpPx(ctx, 4f), fill(me))
            val a = dpPx(ctx, 7f); val b = dpPx(ctx, 14.5f)
            val p = stroke(me)
            c.drawLine(cx, cy - b, cx, cy - a, p); c.drawLine(cx, cy + a, cx, cy + b, p)
            c.drawLine(cx - b, cy, cx - a, cy, p); c.drawLine(cx + a, cy, cx + b, cy, p)
        }
    }
    // La etiqueta: fondo de superficie, borde fino (naranja si está perdido, cian si sos vos).
    val x0 = shapeW + gap
    val rect = android.graphics.RectF(x0, cy - chipH / 2, x0 + chipW, cy + chipH / 2)
    val rr = dpPx(ctx, 7f)
    c.drawRoundRect(rect, rr, rr, fill(bg))
    val border = when (glyph) { Glyph.LOST -> warn; Glyph.ME -> me; else -> HudLine.toArgb() }
    c.drawRoundRect(rect, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; style = Paint.Style.STROKE; strokeWidth = dpPx(ctx, 1f) })
    text.color = when (glyph) {
        Glyph.LOST -> warn
        Glyph.ME -> me
        Glyph.FOREIGN, Glyph.FOREIGN_FIXED, Glyph.FOREIGN_BASE -> if (repeats) SigRelay.toArgb() else HudTextDim.toArgb()
        else -> ink
    }
    c.drawText(shown, x0 + padX, cy - (text.descent() + text.ascent()) / 2, text)
    if (bars != null) {
        val bw = dpPx(ctx, 3.4f); val bgap = dpPx(ctx, 2.2f); val bh = dpPx(ctx, 11f)
        val bx = x0 + chipW - padX - (3 * bw + 2 * bgap)
        val by = cy + bh / 2
        for (k in 0 until 3) {
            val hh = bh * (0.45f + 0.275f * k)
            c.drawRect(bx + k * (bw + bgap), by - hh, bx + k * (bw + bgap) + bw, by, fill(if (k < bars) barInk else HudLine.toArgb()))
        }
    }
    val d = BitmapDrawable(ctx.resources, bmp)
    iconCache[key] = d
    return d to u
}

/** Emoji del waypoint sobre una base chica. */
private fun waypointIcon(ctx: Context, emoji: String): BitmapDrawable = iconCache.getOrPut("w:$emoji:${Appearance.isDay}") {
    val w = dpPx(ctx, 34f).toInt()
    val h = dpPx(ctx, 42f).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cyan = HudCyan.toArgb()
    val cx = w / 2f
    c.drawLine(cx, h - dpPx(ctx, 10f), cx, h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; strokeWidth = dpPx(ctx, 2f); setShadowLayer(dpPx(ctx, 3f), 0f, 0f, cyan) })
    c.drawCircle(cx, h - dpPx(ctx, 1.5f), dpPx(ctx, 2f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan })
    // Blanco: si el ícono no tiene versión emoji en este celular, al menos se ve sobre el mapa oscuro.
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpPx(ctx, 24f); textAlign = Paint.Align.CENTER; color = android.graphics.Color.WHITE
        setShadowLayer(dpPx(ctx, 2f), 0f, 0f, android.graphics.Color.BLACK)
    }
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
