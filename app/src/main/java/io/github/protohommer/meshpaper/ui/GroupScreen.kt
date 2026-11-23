package io.github.protohommer.meshpaper.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.protohommer.meshpaper.MeshViewModel
import io.github.protohommer.meshpaper.mesh.Group
import org.meshtastic.proto.AppOnlyProtos.ChannelSet

private enum class Mode { Menu, Create, Join, Review, Rename }

/** Pantalla del grupo: ver/compartir el actual, crear uno nuevo o unirse con QR/link. */
@Composable
fun GroupScreen(vm: MeshViewModel, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(Mode.Menu) }
    var pending by remember { mutableStateOf<ChannelSet?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val channels by vm.channels.collectAsState()

    val scan = rememberLauncherForActivityResult(ScanContract()) { res ->
        val set = res.contents?.let(Group::fromUrl)
        if (set != null) { pending = set; mode = Mode.Review; error = null }
        else if (res.contents != null) error = "Ese QR no es un grupo de Meshtastic."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HudHeader("Grupo", onBack = if (mode == Mode.Menu) null else ({ mode = Mode.Menu }))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (mode) {
            Mode.Menu -> {
                val current = remember(channels) { vm.currentChannelSet() }
                if (vm.hasPrivateGroup() && current != null) {
                    Text("Grupo: ${current.getSettings(0).name}", style = MaterialTheme.typography.titleLarge)
                    HudOutlinedButton(onClick = { mode = Mode.Rename }, Modifier.fillMaxWidth()) { Text("Cambiar el nombre") }
                    Text("Mostrale este QR a los demás para que se unan (sirve con esta app o la oficial).")
                    QrCard(Group.toUrl(current))
                    if (vm.trackingConfigured() == false) {
                        Text(
                            "El nodo todavía difunde la posición con la config por defecto (cada hora). " +
                                "Para seguir al grupo en el campo conviene cada 5 min, o antes si te movés 25 m.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HudOutlinedButton(onClick = { vm.applyTracking(); onBack() }, Modifier.fillMaxWidth()) {
                            Text("Ajustar el nodo para rastreo")
                        }
                    }
                } else {
                    Text("Este nodo no tiene grupo privado", style = MaterialTheme.typography.titleLarge)
                    Text("Hoy usa el canal público como principal: lo que mandes (incluida tu posición) lo puede leer cualquiera.")
                }
                HudButton(onClick = { mode = Mode.Create }, Modifier.fillMaxWidth()) { Text("Crear grupo nuevo") }
                HudOutlinedButton(onClick = {
                    scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Apuntá al QR del grupo").setBeepEnabled(false).setOrientationLocked(false))
                }, Modifier.fillMaxWidth()) { Text("Unirme escaneando QR") }
                HudOutlinedButton(onClick = { mode = Mode.Join }, Modifier.fillMaxWidth()) { Text("Unirme pegando el link") }
            }

            Mode.Create -> {
                var name by remember { mutableStateOf("") }
                Text("Crear grupo", style = MaterialTheme.typography.titleLarge)
                Text("Se genera una clave AES-256 nueva. Solo quien tenga el QR puede leer los mensajes, posiciones y waypoints del grupo. Los demás nodos igual los repiten.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(Group.MAX_NAME) },
                    label = { Text("Nombre (hasta ${Group.MAX_NAME})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HudButton(
                    onClick = { pending = vm.newGroup(name); mode = Mode.Review },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Siguiente") }
            }

            Mode.Join -> {
                var text by remember { mutableStateOf("") }
                Text("Unirme con link", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("https://meshtastic.org/e/#…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                HudButton(onClick = {
                    val set = Group.fromUrl(text)
                    if (set == null) error = "Ese link no es un grupo de Meshtastic."
                    else { pending = set; mode = Mode.Review; error = null }
                }, Modifier.fillMaxWidth()) { Text("Siguiente") }
            }

            Mode.Rename -> {
                val old = remember(channels) { channels[0]?.settings?.name.orEmpty() }
                var name by remember { mutableStateOf(old) }
                Text("Cambiar el nombre del grupo", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(Group.MAX_NAME) },
                    label = { Text("Nombre nuevo (hasta ${Group.MAX_NAME})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Ojo: el nombre es parte de la llave del canal. Después de cambiarlo, los demás " +
                        "tienen que escanear el QR nuevo; hasta entonces no se leen entre ustedes. " +
                        "La clave y la frecuencia no cambian.",
                    style = MaterialTheme.typography.bodySmall,
                    color = io.github.protohommer.meshpaper.ui.theme.SigFair,
                )
                HudButton(
                    onClick = { vm.renameGroup(name); mode = Mode.Menu },
                    enabled = name.isNotBlank() && name != old,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cambiar y reiniciar el nodo") }
            }

            Mode.Review -> pending?.let { set ->
                Text("Revisá antes de aplicar", style = MaterialTheme.typography.titleLarge)
                Summary(set)
                Text(
                    "Se reemplazan los canales del nodo y su radio. El nodo se reinicia y la app se reconecta sola.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HudButton(onClick = { vm.applyChannelSet(set); onBack() }, Modifier.fillMaxWidth()) {
                    Text("Aplicar al nodo")
                }
            }
        }
    }
}

@Composable
private fun Summary(set: ChannelSet) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            set.settingsList.forEachIndexed { i, s ->
                val kind = when {
                    Group.isPrivate(s) -> "privado (${s.psk.size() * 8} bits)"
                    s.psk.size() == 1 -> "público"
                    s.psk.isEmpty -> "sin cifrar"
                    else -> "clave de ${s.psk.size()} bytes"
                }
                val pos = when (val p = s.moduleSettings.positionPrecision) {
                    0 -> "no manda posición"
                    32 -> "posición exacta"
                    else -> "posición aprox. ($p bits)"
                }
                Text(
                    "${if (i == 0) "Principal" else "Secundario $i"}: ${s.name.ifEmpty { "LongFast" }} · $kind · $pos",
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (set.hasLoraConfig()) {
                val l = set.loraConfig
                Text("Radio: ${l.region} · ${if (l.usePreset) l.modemPreset.name else "custom"} · slot ${l.channelNum.takeIf { it != 0 } ?: "automático"}")
            } else {
                Text("Radio: no cambia")
            }
        }
    }
}

@Composable
private fun QrCard(url: String) {
    val context = LocalContext.current
    val bmp = remember(url) { qrBitmap(url, 720) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Image(bmp.asImageBitmap(), contentDescription = "QR del grupo", Modifier.size(300.dp))
            Spacer(Modifier.height(8.dp))
            HudOutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                context.startActivity(Intent.createChooser(send, "Compartir grupo"))
            }) { Text("Compartir link") }
            Text("Ojo: el link ES la clave. Compartilo solo con el grupo.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
