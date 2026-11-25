package io.github.protohommer.meshpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.MeshViewModel
import io.github.protohommer.meshpaper.CANNED_MAX_BYTES
import io.github.protohommer.meshpaper.mesh.Calandria
import io.github.protohommer.meshpaper.mesh.Hardware
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigWeak
import org.meshtastic.proto.MeshProtos.HardwareModel

/** Nombre de la placa: el que anunció el firmware Calandria, o el de la tabla oficial. */
fun boardName(info: Calandria.Info?, hw: HardwareModel?): String? =
    info?.model ?: hw?.takeIf { it != HardwareModel.UNSET }?.let { Hardware.name(it) }

/** "fw 2.8.3 · al día" / "hay una versión nueva", contra la última publicada en GitHub. */
@Composable
fun CalandriaFirmware(info: Calandria.Info, showLink: Boolean = true) {
    val latest by Calandria.latest.collectAsState()
    val upToDate = Calandria.upToDate(info, latest)
    // "Calandria 0.6.0 · Meshtastic 2.8.3"; los firmwares de antes de la 0.6.0 no mandan su versión.
    val fw = if (info.ver.isNotEmpty()) "Calandria ${info.ver} · Meshtastic ${info.base}" else "Firmware ${info.fw.ifEmpty { "?" }}"
    when (upToDate) {
        true -> HudLabel("$fw · al día", color = SigGood, maxLines = 2)
        false -> {
            HudLabel("$fw · hay una nueva: ${latest?.tag?.let { Calandria.shortTag(it) }}", color = SigFair, maxLines = 2)
            val uri = LocalUriHandler.current
            if (showLink) latest?.url?.takeIf { it.isNotEmpty() }?.let { url ->
                TextButton(onClick = { uri.openUri(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("VER LA VERSIÓN NUEVA", softWrap = false)
                }
            }
        }
        null -> HudLabel(fw, maxLines = 2)
    }
}

/** Ejemplos para un nodo sin mensajes rápidos. */
private val CANNED_EXAMPLES = listOf("OK", "Voy", "Llegué", "Esperame", "Estoy en el punto", "Necesito ayuda")

/**
 * Mensajes rápidos del nodo conectado (canned messages). En el e-paper salen en el menú y se
 * mandan con dos giros y un clic, sin escribir con el encoder. Se guardan en el nodo, sin reiniciar.
 */
@Composable
fun CannedPanel(vm: MeshViewModel) {
    val stored by vm.canned.collectAsState()
    LaunchedEffect(Unit) { if (vm.canned.value == null) vm.loadCanned() }
    val items = remember { mutableStateListOf<String>() }
    var loadedFrom by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Cuando llegan del nodo (o cambian al guardar), se cargan en el editor.
    LaunchedEffect(stored) {
        val s = stored ?: return@LaunchedEffect
        if (s != loadedFrom) { items.clear(); items.addAll(s); loadedFrom = s }
    }
    val used = items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("|").toByteArray(Charsets.UTF_8).size
    val over = used > CANNED_MAX_BYTES

    HudPanel(Modifier.fillMaxWidth(), title = "Mensajes rápidos") {
        if (stored == null) {
            // Un nodo sin el módulo (sin pantalla, o firmware que no lo trae) no contesta nunca.
            var tries by remember { mutableStateOf(0) }
            var noAnswer by remember { mutableStateOf(false) }
            LaunchedEffect(tries) { noAnswer = false; kotlinx.coroutines.delay(10_000); noAnswer = true }
            if (noAnswer) Text(
                "El nodo no contestó. Puede que su firmware no tenga mensajes rápidos (pasa en placas sin pantalla).",
                style = MaterialTheme.typography.bodySmall, color = SigFair,
            ) else HudLabel("Leyendo los del nodo…")
            TextButton(onClick = { vm.loadCanned(); tries++ }) { Text("REINTENTAR") }
            return@HudPanel
        }
        Text(
            "Se mandan desde el e-paper con el encoder (menú → Enviar), sin teclado. Se guardan en el nodo.",
            style = MaterialTheme.typography.bodySmall, color = HudTextDim,
        )
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v -> items[i] = v.replace("|", "/").replace("\n", " ").take(60); error = null },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { items.removeAt(i); error = null }) { Text("✕", color = SigWeak) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { items.add(""); error = null }) { Text("+ AGREGAR") }
            if (items.isEmpty()) TextButton(onClick = { items.addAll(CANNED_EXAMPLES) }) { Text("USAR EJEMPLOS") }
            Spacer(Modifier.weight(1f))
            HudLabel("$used/${CANNED_MAX_BYTES}", color = if (over) SigWeak else HudTextDim)
        }
        error?.let { Text(it, color = SigWeak, style = MaterialTheme.typography.bodySmall) }
        val dirty = items.map { it.trim() }.filter { it.isNotEmpty() } != stored
        HudButton(
            onClick = { error = vm.saveCanned(items.toList()) },
            Modifier.fillMaxWidth().padding(top = 4.dp),
            enabled = dirty && !over,
        ) { Text("GUARDAR EN EL NODO") }
    }
}
