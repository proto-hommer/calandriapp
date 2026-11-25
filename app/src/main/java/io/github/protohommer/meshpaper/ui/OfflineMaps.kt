package io.github.protohommer.meshpaper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.protohommer.meshpaper.map.Dem
import io.github.protohommer.meshpaper.map.MapTiles
import io.github.protohommer.meshpaper.map.OfflineCells
import io.github.protohommer.meshpaper.map.OfflineCells.Cell
import io.github.protohommer.meshpaper.map.OfflineCells.Status
import io.github.protohommer.meshpaper.ui.theme.HudBg
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudSurface
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigPoor
import io.github.protohommer.meshpaper.ui.theme.SigWeak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/** Panel de Ajustes que abre la pantalla de mapas sin conexión. */
@Composable
fun OfflineMapsPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf(Dem.stored(ctx)) }
    HudPanel(modifier.fillMaxWidth(), title = "Mapas sin conexión") {
        Text(
            "Elegí en una cuadrícula qué zonas bajar para usar sin señal: el mapa (si la fuente lo " +
                "permite) y el relieve para los perfiles, todo junto.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        HudLabel("Relieve guardado: ${stored.tiles} tiles · ${MapTiles.formatSize(stored.bytes)}")
        Spacer(Modifier.height(6.dp))
        HudButton(onClick = { open = true }) { Text("Abrir cuadrícula") }
    }
    if (open) OfflineMapsScreen(onClose = { open = false; stored = Dem.stored(ctx) })
}

private sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val index: Int, val total: Int, val label: String, val done: Int, val of: Int) : DownloadState
    data class Finished(val text: String) : DownloadState
}

private val DETAIL = listOf(13 to "Ruta", 14 to "Pueblo", 15 to "Calles", 16 to "Detalle")

@Composable
private fun OfflineMapsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = remember { MapTiles.darkMap(ctx) }
    val source = remember { MapTiles.tileSource(ctx) }
    val canMap = remember { MapTiles.canBulkDownload(source) }
    val mapSource = if (canMap) source.name() else null
    var maxZoom by remember { mutableIntStateOf(15) }
    var selected by remember { mutableStateOf<List<Cell>>(emptyList()) }
    var busyCells by remember { mutableStateOf<Set<Cell>>(emptySet()) }
    var job by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var running by remember { mutableStateOf<Job?>(null) }
    var refresh by remember { mutableIntStateOf(0) }  // para recalcular los estados
    var labels by remember { mutableStateOf<Map<Cell, String>>(emptyMap()) }

    val map = remember {
        MapTiles.init(ctx)
        MapView(ctx).apply {
            setTileProvider(MapTiles.newProvider(ctx))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 6.0
            maxZoomLevel = 14.0
            setBackgroundColor(HudBg.toArgb())
            if (dark) overlayManager.tilesOverlay.setColorFilter(darkFilter)
            // Donde estaba el mapa principal. El centro se pone después del primer layout:
            // antes, osmdroid no sabe el tamaño de la vista y lo corre.
            val p = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)
            val center = if (p.contains("lat")) GeoPoint(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble())
            else GeoPoint(-31.42, -64.18)
            controller.setZoom(10.0)
            controller.setCenter(center)
            addOnFirstLayoutListener { _, _, _, _, _ -> controller.setZoom(10.0); controller.setCenter(center) }
        }
    }
    val grid = remember {
        GridOverlay(
            ctx,
            status = { c -> OfflineCells.status(ctx, c, mapSource, maxZoom) },
            isSelected = { it in selected },
            isBusy = { it in busyCells },
            onTap = { c -> if (running == null) selected = if (c in selected) selected - c else selected + c },
            onLabels = { labels = it },
        )
    }
    grid.mapSource = mapSource; grid.maxZoom = maxZoom; grid.version = refresh
    DisposableEffect(Unit) {
        map.overlays.add(grid)
        map.onResume()
        onDispose { running?.cancel(); map.onPause(); map.onDetach() }
    }

    fun start() {
        val cells = selected
        if (cells.isEmpty()) return
        busyCells = cells.toSet()
        running = scope.launch {
            var fails = 0
            cells.forEachIndexed { i, c ->
                val label = labels[c] ?: c.key
                val dem = OfflineCells.demTiles(c)
                val mapTotal = if (canMap) OfflineCells.mapTileCount(maxZoom) else 0
                val total = dem.size + mapTotal
                job = DownloadState.Running(i + 1, cells.size, label, 0, total)
                dem.forEachIndexed { k, (x, y) ->
                    if (!withContext(Dispatchers.IO) { Dem.download(ctx, x, y) }) fails++
                    job = DownloadState.Running(i + 1, cells.size, label, k + 1, total)
                }
                if (canMap) {
                    val errors = downloadMap(map, c, maxZoom) { done ->
                        job = DownloadState.Running(i + 1, cells.size, label, dem.size + done, total)
                    }
                    if (errors == 0) OfflineCells.markMap(ctx, c, source.name(), maxZoom) else fails += errors
                }
                busyCells = busyCells - c
                refresh++
                map.invalidate()
            }
            selected = emptyList()
            job = DownloadState.Finished(if (fails == 0) "Listo: " + if (cells.size == 1) "1 cuadrante guardado." else "${cells.size} cuadrantes guardados." else "Terminó con $fails " + (if (fails == 1) "tile fallido" else "tiles fallidos") + ": volvé a bajar los que quedaron en naranja.")
            running = null
            refresh++
            map.invalidate()
        }
    }

    Dialog(
        onDismissRequest = { if (running == null) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(HudBg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Mapas sin conexión", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = HudCyan)
                TextButton(onClick = { running?.cancel(); running = null; busyCells = emptySet(); onClose() }) {
                    Text(if (running != null) "CANCELAR" else "CERRAR")
                }
            }
            // clipToBounds: el MapView de osmdroid dibuja fuera de su lugar y taparía la barra de arriba.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                AndroidView(factory = { map }, modifier = Modifier.fillMaxSize().clipToBounds(), update = { it.invalidate() })
            }
            Column(Modifier.fillMaxWidth().background(HudSurface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendItem(SigGood, "Bajado")
                    LegendItem(SigFair, "Bajando")
                    LegendItem(SigPoor, "A medias")
                    LegendItem(SigWeak, "Sin bajar")
                }
                if (canMap) {
                    HudLabel("Detalle del mapa (hasta zoom $maxZoom)")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((z, name) in DETAIL) {
                            FilterChip(selected = maxZoom == z, onClick = { if (running == null) { maxZoom = z; refresh++; map.invalidate() } }, label = { Text("$name $z") })
                        }
                    }
                } else {
                    Text(
                        "Con OpenStreetMap se baja solo el relieve: sus reglas no permiten bajar el mapa. " +
                            "Para tener el mapa sin señal, poné un servidor propio o importá un .mbtiles (en el mapa, CAPA).",
                        style = MaterialTheme.typography.bodySmall, color = HudTextDim,
                    )
                }
                val online = MapTiles.useInternet(ctx)
                when (val j = job) {
                    is DownloadState.Running -> {
                        Text("Bajando ${j.label} (${j.index} de ${j.total})", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { if (j.of > 0) j.done / j.of.toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is DownloadState.Finished -> Text(j.text, style = MaterialTheme.typography.bodySmall)
                    DownloadState.Idle -> Text(
                        if (selected.isEmpty()) "Tocá los cuadrantes que quieras bajar."
                        else {
                            val per = 4 * 40_000L + if (canMap) OfflineCells.mapTileCount(maxZoom) * 15_000L else 0L
                            "${selected.size} elegidos: " + selected.joinToString(", ") { labels[it] ?: it.key } +
                                " · ≈${MapTiles.formatSize(per * selected.size)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    HudButton(onClick = { job = DownloadState.Idle; start() }, enabled = online && selected.isNotEmpty() && running == null) {
                        Text("Descargar")
                    }
                    if (selected.isNotEmpty() && running == null) TextButton(onClick = { selected = emptyList() }) { Text("Limpiar") }
                }
                if (!online) Text("Prendé \"Usar internet\" (en el mapa, CAPA) para descargar.", style = MaterialTheme.typography.bodySmall, color = SigWeak)
                Text(Dem.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = HudTextDim)
            }
        }
    }
}

/** Baja el mapa de un cuadrante con el CacheManager de osmdroid. Devuelve los tiles fallidos. */
internal suspend fun downloadMap(map: MapView, c: Cell, maxZoom: Int, progress: (Int) -> Unit): Int =
    suspendCancellableCoroutine { cont ->
        val cm = CacheManager(map)
        val box = BoundingBox(c.north, c.east, c.south, c.west)
        cm.downloadAreaAsyncNoUI(map.context, box, OfflineCells.MAP_MIN_ZOOM, maxZoom, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() { if (cont.isActive) cont.resume(0) }
            override fun onTaskFailed(errors: Int) { if (cont.isActive) cont.resume(errors) }
            override fun updateProgress(p: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) = progress(p)
            override fun downloadStarted() = Unit
            override fun setPossibleTilesInArea(total: Int) = Unit
        })
        cont.invokeOnCancellation { cm.cancelAllJobs() }
    }

/**
 * Dibuja la cuadrícula de cuadrantes sobre el mapa, con el color de su estado y la etiqueta
 * "columna-fila" relativa a lo que se ve (1, 2, 3… de izquierda a derecha; A, B, C… de arriba abajo).
 */
private class GridOverlay(
    ctx: Context,
    private val status: (Cell) -> Status,
    private val isSelected: (Cell) -> Boolean,
    private val isBusy: (Cell) -> Boolean,
    private val onTap: (Cell) -> Unit,
    private val onLabels: (Map<Cell, String>) -> Unit,
) : Overlay() {
    var mapSource: String? = null
    var maxZoom = 15
    var version = 0
        set(v) { if (v != field) cache.clear(); field = v }
    private var lastSource: String? = null
    private var lastZoom = 0
    private val cache = HashMap<Cell, Status>()
    private val dp = ctx.resources.displayMetrics.density
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = 0x99FFFFFF.toInt() }
    private val sel = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f * dp }
    private val text = Paint().apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12f * dp; typeface = Typeface.MONOSPACE
        isAntiAlias = true; setShadowLayer(3f * dp, 0f, 0f, 0xFF000000.toInt()); textAlign = Paint.Align.CENTER
    }
    private var lastLabels: Map<Cell, String> = emptyMap()

    private fun visible(mv: MapView): List<List<Cell>>? {
        val b = mv.boundingBox
        val a = OfflineCells.at(b.latNorth, b.lonWest)
        val z = OfflineCells.at(b.latSouth, b.lonEast)
        val cols = z.x - a.x + 1
        val rows = z.y - a.y + 1
        if (cols <= 0 || rows <= 0 || cols * rows > 400) return null
        return List(rows) { r -> List(cols) { col -> Cell(a.x + col, a.y + r) } }
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        sel.color = HudCyan.toArgb()
        if (shadow) return
        if (mapSource != lastSource || maxZoom != lastZoom) { cache.clear(); lastSource = mapSource; lastZoom = maxZoom }
        val rows = visible(mv)
        if (rows == null) {
            c.drawText("Acercate para ver la cuadrícula", mv.width / 2f, mv.height / 2f, text)
            return
        }
        val pj = mv.projection
        val p1 = Point(); val p2 = Point()
        val labels = HashMap<Cell, String>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { col, cell ->
                pj.toPixels(GeoPoint(cell.north, cell.west), p1)
                pj.toPixels(GeoPoint(cell.south, cell.east), p2)
                val l = min(p1.x, p2.x).toFloat(); val rr = max(p1.x, p2.x).toFloat()
                val t = min(p1.y, p2.y).toFloat(); val bt = max(p1.y, p2.y).toFloat()
                val st = cache.getOrPut(cell) { status(cell) }
                fill.color = when {
                    isBusy(cell) -> SigFair
                    st == Status.DONE -> SigGood
                    st == Status.PARTIAL -> SigPoor
                    else -> SigWeak
                }.toArgb()
                fill.alpha = if (isSelected(cell)) 110 else 60
                c.drawRect(l, t, rr, bt, fill)
                c.drawRect(l, t, rr, bt, line)
                if (isSelected(cell)) c.drawRect(l + sel.strokeWidth / 2, t + sel.strokeWidth / 2, rr - sel.strokeWidth / 2, bt - sel.strokeWidth / 2, sel)
                val name = OfflineCells.label(col, r)
                labels[cell] = name
                if (rr - l > 40 * dp) c.drawText(name, (l + rr) / 2, (t + bt) / 2 + text.textSize / 3, text)
            }
        }
        if (labels != lastLabels) { lastLabels = labels; mv.post { onLabels(labels) } }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mv: MapView): Boolean {
        val g = mv.projection.fromPixels(e.x.toInt(), e.y.toInt())
        onTap(OfflineCells.at(g.latitude, g.longitude))
        mv.invalidate()
        return true
    }
}
