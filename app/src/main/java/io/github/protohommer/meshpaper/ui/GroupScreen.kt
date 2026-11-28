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

/**
 * Pantalla de los grupos: un nodo puede estar en varios a la vez (cada uno es un canal privado con
 * su clave). Se ve cada grupo con su QR; se suma uno nuevo sin perder los que hay, se elige cuál es
 * el principal (el que recibe la posición automática) y se puede salir de uno.
 */
@Composable
fun GroupScreen(vm: MeshViewModel, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(Mode.Menu) }
    var pending by remember { mutableStateOf<ChannelSet?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(0) }
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
                val groups = remember(channels) { vm.privateGroups() }
                var openQr by remember { mutableStateOf<Int?>(if (groups.size == 1) groups[0].index else null) }
                var leaving by remember { mutableStateOf<Int?>(null) }
                var note by remember { mutableStateOf<String?>(null) }
                if (groups.isEmpty()) {
                    Text("Este nodo no tiene grupo privado", style = MaterialTheme.typography.titleLarge)
                    Text("Hoy usa el canal público como principal: lo que mandes (incluida tu posición) lo puede leer cualquiera.")
                } else {
                    Text(if (groups.size == 1) "Tu grupo" else "Tus ${groups.size} grupos", style = MaterialTheme.typography.titleLarge)
                    if (groups.size > 1) Text(
                        "Cada grupo es un canal privado con su clave: los de un grupo no leen lo del otro. Tu posición " +
                            "sale sola solo por el principal; a los demás se la mandás con un toque.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (g in groups) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(g.settings.name.ifEmpty { "(sin nombre)" }, style = MaterialTheme.typography.titleMedium)
                            HudLabel(
                                (if (g.index == 0) "Principal · recibe tu posición automática" else "Canal ${g.index}") +
                                    " · ${vm.groupMemberCount(g.index)} miembros oídos",
                                maxLines = 2,
                            )
                            if (openQr == g.index) vm.groupChannelSet(g.index)?.let { QrCard(Group.toUrl(it)) }
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { openQr = if (openQr == g.index) null else g.index }) {
                                    Text(if (openQr == g.index) "Ocultar QR" else "Mostrar QR")
                                }
                                if (groups.size > 1) TextButton(onClick = {
                                    note = vm.sendPositionTo(g.index) ?: "Posición mandada a ${g.settings.name}."
                                }) { Text("Mandar mi posición") }
                                if (g.index != 0) TextButton(onClick = { vm.makePrimary(g.index); onBack() }) { Text("Hacer principal") }
                                TextButton(onClick = { renaming = g.index; mode = Mode.Rename }) { Text("Cambiar el nombre") }
                                TextButton(onClick = { leaving = g.index }) {
                                    Text("Salir", color = io.github.protohommer.meshpaper.ui.theme.SigWeak)
                                }
                            }
                            if (leaving == g.index) {
                                Text(
                                    "¿Salir de ${g.settings.name}? Dejás de leer sus mensajes y de ver a sus miembros. " +
                                        "Para volver hace falta el QR." + if (g.index == 0) " Otro canal pasa a ser el principal." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { leaving = null }) { Text("Cancelar") }
                                    TextButton(onClick = { vm.leaveGroup(g.index); onBack() }) {
                                        Text("Salir del grupo", color = io.github.protohommer.meshpaper.ui.theme.SigWeak)
                                    }
                                }
                            }
                        }
                    }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (groups.isNotEmpty() && vm.trackingConfigured() == false) {
                    Text(
                        "El nodo todavía difunde la posición con la config por defecto (cada hora). " +
                            "Para seguir al grupo en el campo conviene cada 5 min, o antes si te movés 25 m.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HudOutlinedButton(onClick = { vm.applyTracking(); onBack() }, Modifier.fillMaxWidth()) {
                        Text("Ajustar el nodo para rastreo")
                    }
                }
                HudButton(onClick = { mode = Mode.Create }, Modifier.fillMaxWidth()) {
                    Text(if (groups.isEmpty()) "Crear grupo nuevo" else "Crear otro grupo")
                }
                HudOutlinedButton(onClick = {
                    scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Apuntá al QR del grupo").setBeepEnabled(false).setOrientationLocked(false))
                }, Modifier.fillMaxWidth()) { Text(if (groups.isEmpty()) "Unirme escaneando QR" else "Sumarme a otro grupo (QR)") }
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
                val old = remember(channels, renaming) { channels[renaming]?.settings?.name.orEmpty() }
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
                    onClick = { vm.renameGroup(name, renaming); mode = Mode.Menu },
                    enabled = name.isNotBlank() && name != old,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cambiar y reiniciar el nodo") }
            }

            Mode.Review -> pending?.let { set ->
                Text("Revisá antes de aplicar", style = MaterialTheme.typography.titleLarge)
                Summary(set)
                val has = vm.privateGroups().isNotEmpty()
                val fresh = remember(set, channels) { vm.newGroupsIn(set) }
                val clash = remember(set, channels) { vm.addIncompatibility(set) }
                if (has && fresh.isNotEmpty()) {
                    if (clash == null) {
                        Text(
                            "Lo podés sumar a tus grupos: seguís en los que tenés y además entrás a este. " +
                                "El nodo se reinicia y la app se reconecta sola.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HudButton(onClick = { vm.addGroups(set); onBack() }, Modifier.fillMaxWidth()) {
                            Text("Sumar a mis grupos")
                        }
                    } else {
                        Text(
                            "Este grupo usa otra radio ($clash). Un nodo tiene una sola radio para todos sus grupos: " +
                                "para estar en los dos tienen que usar la misma. Si lo aplicás, reemplaza a los que tenés.",
                            style = MaterialTheme.typography.bodySmall,
                            color = io.github.protohommer.meshpaper.ui.theme.SigFair,
                        )
                    }
                } else if (has) {
                    Text("Ya estás en este grupo.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (has) "O reemplazar: se borran los canales del nodo (todos tus grupos) y quedan los de este QR, con su radio."
                    else "Se reemplazan los canales del nodo y su radio. El nodo se reinicia y la app se reconecta sola.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val replace = @Composable { Text(if (has) "Reemplazar todo" else "Aplicar al nodo") }
                if (has) HudOutlinedButton(onClick = { vm.applyChannelSet(set); onBack() }, Modifier.fillMaxWidth()) { replace() }
                else HudButton(onClick = { vm.applyChannelSet(set); onBack() }, Modifier.fillMaxWidth()) { replace() }
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
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Image(
                bmp.asImageBitmap(), contentDescription = "QR del grupo",
                Modifier.size(300.dp).align(androidx.compose.ui.Alignment.CenterHorizontally),
            )
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
