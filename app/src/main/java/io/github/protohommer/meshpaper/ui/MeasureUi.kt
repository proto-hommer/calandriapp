package io.github.protohommer.meshpaper.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.protohommer.meshpaper.MeshViewModel
import io.github.protohommer.meshpaper.map.MapTiles
import io.github.protohommer.meshpaper.mesh.Coverage
import io.github.protohommer.meshpaper.mesh.Measurement
import io.github.protohommer.meshpaper.mesh.ModelCheck
import io.github.protohommer.meshpaper.mesh.Profile
import io.github.protohommer.meshpaper.mesh.Range
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Mediciones de señal: guardarlas o no, compararlas con el modelo, exportarlas y borrarlas.
 * Todo queda en el teléfono; exportar es a pedido (lleva posiciones).
 */
@Composable
fun MeasurementsPanel(vm: MeshViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by vm.measurements.collectAsState()
    val on by vm.recordMeasurements.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val antennas by vm.antennas.collectAsState()
    val lora by vm.lora.collectAsState()
    var report by remember { mutableStateOf<ModelCheck.Report?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var askClear by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    HudPanel(Modifier.fillMaxWidth(), title = "Mediciones de señal") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Guardar cada paquete que llega directo, con dónde estabas vos y el otro",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = on, onCheckedChange = { vm.setRecordMeasurements(it) })
        }
        HudLabel(
            if (list.isEmpty()) "Todavía ninguna"
            else "${list.size} mediciones de ${list.map { it.from }.distinct().size} nodos", maxLines = 2,
        )
        Text(
            "Quedan solo en este teléfono. Con ellas se ve en el mapa dónde hubo señal de verdad (MED) y " +
                "cuánto le erra el cálculo de cobertura en tu zona.",
            style = MaterialTheme.typography.bodySmall, color = HudTextDim,
        )
        Spacer(Modifier.height(8.dp))
        val busy = progress != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudOutlinedButton(
                onClick = {
                    val node = me ?: return@HudOutlinedButton
                    progress = 0 to 1; report = null; msg = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            ModelCheck.run(
                                ctx, node, list, antennas, Profile.frequencyMHz(lora), MapTiles.useInternet(ctx),
                            ) { a, b -> progress = a to b }
                        }
                        progress = null
                        if (r.samples.isEmpty()) msg = "No hay mediciones que sirvan para comparar todavía " +
                            "(hacen falta a más de 150 m y con la posición del otro al día, y el relieve de la zona)."
                        else report = r
                    }
                },
                enabled = list.isNotEmpty() && !busy && me != null, modifier = Modifier.weight(1.4f),
            ) { Text("COMPARAR", softWrap = false) }
            HudOutlinedButton(
                onClick = { exportCsv(ctx, vm.measurementsCsv())?.let { msg = it } },
                enabled = list.isNotEmpty() && !busy, modifier = Modifier.weight(1f),
            ) { Text("EXPORTAR", softWrap = false) }
        }
        progress?.let { (a, b) ->
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { if (b == 0) 0f else a.toFloat() / b }, modifier = Modifier.fillMaxWidth())
            HudLabel("Comparando $a de $b…")
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HudTextDim) }
        report?.let { ReportView(it) }
        if (list.isNotEmpty()) TextButton(onClick = { askClear = true }) {
            Text("Borrar las mediciones", color = MaterialTheme.colorScheme.error)
        }
    }
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("¿Borrar las ${list.size} mediciones?") },
        text = { Text("Se borran de este teléfono y no se pueden recuperar.") },
        confirmButton = { TextButton(onClick = { askClear = false; report = null; vm.clearMeasurements() }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun ReportView(r: ModelCheck.Report) {
    fun db(x: Double) = String.format(Locale.US, "%+.0f dB", x).replace('.', ',')
    Spacer(Modifier.height(8.dp))
    HudLabel("Con ${r.samples.size} mediciones", color = HudCyan)
    val bias = r.meanDb
    Text(
        when {
            abs(bias) < 3 -> "En promedio el modelo acierta (${db(bias)})."
            bias < 0 -> "El modelo promete de más: la señal real llegó ${db(-bias).removePrefix("+")} más débil, en promedio."
            else -> "El modelo es pesimista: la señal real llegó ${db(bias).removePrefix("+")} más fuerte, en promedio."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Dispersión: ±${r.stdDb.roundToInt()} dB" +
            (if (r.stdDb > 0) " (10 dB es casi el doble o la mitad de distancia)." else "."),
        style = MaterialTheme.typography.bodySmall, color = HudTextDim,
    )
    for (b in r.byDistance) HudLabel("${b.label}: ${db(b.meanDb)} (${b.n})")
    val wc = r.withCover; val base = r.baseOnCovered
    if (wc != null && base != null) {
        Spacer(Modifier.height(6.dp))
        HudLabel("Sumando monte y ciudad (${wc.n} mediciones con ese dato)", color = HudCyan, maxLines = 2)
        Text(
            "Solo relieve: ${db(base.meanDb)} ±${base.stdDb.roundToInt()} dB · con monte y ciudad: ${db(wc.meanDb)} ±${wc.stdDb.roundToInt()} dB. " +
                // Error total (sesgo y dispersión juntos): raíz del error cuadrático medio.
                run {
                    val rw = kotlin.math.sqrt(wc.meanDb * wc.meanDb + wc.stdDb * wc.stdDb)
                    val rb = kotlin.math.sqrt(base.meanDb * base.meanDb + base.stdDb * base.stdDb)
                    "Error total: ${rb.roundToInt()} dB sin, ${rw.roundToInt()} dB con. " + when {
                        rw < rb - 1 -> "Con monte y ciudad acierta mejor."
                        rw > rb + 1 -> "Con monte y ciudad acierta peor: hay que ajustar los valores."
                        else -> "Casi igual con y sin."
                    }
                },
            style = MaterialTheme.typography.bodySmall,
        )
    } else Text(
        "Monte y ciudad: no hay datos de cobertura del suelo en la zona de estas mediciones.",
        style = MaterialTheme.typography.bodySmall, color = HudTextDim,
    )
    if (r.skipped > 0) Text(
        "${r.skipped} no se usaron: muy cerca, con la posición del otro vieja, repetidas en el mismo lugar o sin relieve bajado.",
        style = MaterialTheme.typography.bodySmall, color = HudTextDim,
    )
    Text(
        "Las antenas sin cargar se toman como de mano (2 dBi a 1,5 m, 22 dBm): cargá las de los nodos fijos " +
            "para que la comparación valga.",
        style = MaterialTheme.typography.bodySmall, color = HudTextDim,
    )
}

/** Guarda el CSV en la caché y abre "compartir". Devuelve un error, o null si salió bien. */
private fun exportCsv(ctx: Context, csv: String): String? = runCatching {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, "mediciones.csv")
    f.writeText(csv)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", f)
    ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Exportar mediciones",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    null
}.getOrElse { "No se pudo exportar: ${it.message}" }

/**
 * Lo medido en el mapa: un punto donde estabas cuando oíste a alguien directo, del color de la
 * cobertura según cuánto sobró sobre la sensibilidad. Varios en el mismo lugar: queda el mejor.
 */
class MeasureOverlay(ctx: Context, list: List<Measurement>, preset: ModemPreset) : Overlay() {
    private val pts: List<Triple<GeoPoint, Int, Float>>
    private val r = 4.5f * ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * ctx.resources.displayMetrics.density; color = 0xCC000000.toInt()
    }
    private val tmp = android.graphics.Point()

    init {
        val sens = Range.sensitivityDbm(preset) ?: -130.0
        pts = list.groupBy { (it.rxLat * 4000).roundToInt() to (it.rxLon * 4000).roundToInt() } // ~25 m
            .values.map { g ->
                val best = g.maxBy { it.signalDbm }
                val m = ((best.signalDbm - sens) / Coverage.FULL_SCALE_DB).toFloat()
                Triple(GeoPoint(best.rxLat, best.rxLon), Coverage.color(m, 0xF0), m)
            }
            .sortedBy { it.third } // los mejores arriba
    }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = map.projection
        for ((p, color, m) in pts) {
            pj.toPixels(p, tmp)
            fill.color = if (m < 0f) 0xF0606060.toInt() else color
            c.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), r, fill)
            c.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), r, edge)
        }
    }
}
