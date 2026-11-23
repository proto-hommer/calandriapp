package io.github.protohommer.meshpaper.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.MeshViewModel
import io.github.protohommer.meshpaper.NodeEdits
import io.github.protohommer.meshpaper.mesh.Group
import io.github.protohommer.meshpaper.mesh.Range
import io.github.protohommer.meshpaper.mesh.Regions
import io.github.protohommer.meshpaper.mesh.Hardware
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudLine
import io.github.protohommer.meshpaper.ui.theme.HudSurface
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigWeak
import org.meshtastic.proto.ChannelProtos.Channel
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.RebroadcastMode
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.Role
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode
import org.meshtastic.proto.ConfigProtos.Config.PositionConfig
import org.meshtastic.proto.MeshProtos
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** Ajustes del nodo conectado: nombre, rol, posición, radio y canales. */
@Composable
fun SettingsScreen(vm: MeshViewModel) {
    val owner by vm.owner.collectAsState()
    val device by vm.deviceConfig.collectAsState()
    val pos by vm.positionConfig.collectAsState()
    val lora by vm.lora.collectAsState()
    val channels by vm.channels.collectAsState()
    val o = owner; val d = device; val p = pos; val l = lora
    if (o == null || d == null || p == null || l == null || channels.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            HudHeader("Ajustes del nodo")
            Text("Leyendo la configuración del nodo…", color = HudTextDim)
        }
        return
    }
    SettingsContent(vm, o, d, p, l, channels)
}

@Composable
private fun SettingsContent(
    vm: MeshViewModel,
    owner: MeshProtos.User,
    device: DeviceConfig,
    pos: PositionConfig,
    lora: LoRaConfig,
    channels: Map<Int, Channel>,
) {
    val meta by vm.metadata.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val fix by vm.myFix.collectAsState()
    val myNode = me?.let { nodes[it] }

    // ---- estado editable (se reinicia cuando el nodo manda la config de nuevo) ----
    var longName by remember(owner) { mutableStateOf(owner.longName) }
    var shortName by remember(owner) { mutableStateOf(owner.shortName) }
    var role by remember(device) { mutableStateOf(device.role) }
    var rebroadcast by remember(device) { mutableStateOf(device.rebroadcastMode) }
    var fixed by remember(pos) { mutableStateOf(pos.fixedPosition) }
    val curLat = if (pos.fixedPosition) myNode?.lat else null
    val curLon = if (pos.fixedPosition) myNode?.lon else null
    var latText by remember(pos, curLat) { mutableStateOf(curLat?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var lonText by remember(pos, curLon) { mutableStateOf(curLon?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var altitude by remember(pos) { mutableStateOf<Int?>(null) }
    var posSecs by remember(pos) { mutableIntStateOf(pos.positionBroadcastSecs) }
    var smart by remember(pos) { mutableStateOf(pos.positionBroadcastSmartEnabled) }
    var smartDist by remember(pos) { mutableIntStateOf(pos.broadcastSmartMinimumDistance) }
    var smartSecs by remember(pos) { mutableIntStateOf(pos.broadcastSmartMinimumIntervalSecs) }
    var preset by remember(lora) { mutableStateOf(lora.modemPreset) }
    var slot by remember(lora) { mutableIntStateOf(lora.channelNum) }
    var hops by remember(lora) { mutableIntStateOf(lora.hopLimit) }
    var txPower by remember(lora) { mutableIntStateOf(lora.txPower) }
    var precision by remember(channels) {
        mutableStateOf(channels.mapValues { it.value.settings.moduleSettings.positionPrecision })
    }
    var primary by remember(channels) { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<NodeEdits?>(null) }
    var askReboot by remember { mutableStateOf(false) }
    var askForget by remember { mutableStateOf(false) }

    // ---- validación y armado de los cambios ----
    val longOk = longName.isNotBlank() && longName.toByteArray().size <= MAX_LONG_NAME
    val shortOk = shortName.isNotBlank() && shortName.toByteArray().size <= MAX_SHORT_NAME
    val lat = latText.replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 && it != 0.0 }
    val lon = lonText.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 && it != 0.0 }
    val fixedOk = !fixed || (lat != null && lon != null)
    val valid = longOk && shortOk && fixedOk

    val edits = run {
        val newOwner = if (longName != owner.longName || shortName != owner.shortName)
            owner.toBuilder().setLongName(longName.trim()).setShortName(shortName.trim()).build() else null
        val newDevice = device.toBuilder().setRole(role).setRebroadcastMode(rebroadcast).build()
            .takeIf { it != device }
        val newPos = pos.toBuilder()
            .setFixedPosition(fixed)
            .setPositionBroadcastSecs(posSecs)
            .setPositionBroadcastSmartEnabled(smart)
            .setBroadcastSmartMinimumDistance(smartDist)
            .setBroadcastSmartMinimumIntervalSecs(smartSecs)
            .build().takeIf { it != pos }
        val movedFix = fixed && lat != null && lon != null &&
            (!pos.fixedPosition || curLat == null || curLon == null ||
                (lat * 1e7).roundToInt() != (curLat * 1e7).roundToInt() ||
                (lon * 1e7).roundToInt() != (curLon * 1e7).roundToInt())
        val newFix = if (movedFix) MeshProtos.Position.newBuilder()
            .setLatitudeI((lat!! * 1e7).roundToInt())
            .setLongitudeI((lon!! * 1e7).roundToInt())
            .apply { altitude?.let { setAltitude(it) } }
            .setLocationSource(MeshProtos.Position.LocSource.LOC_MANUAL)
            .build() else null
        val newLora = lora.toBuilder().setModemPreset(preset).setChannelNum(slot).setHopLimit(hops).setTxPower(txPower)
            .apply { if (preset != lora.modemPreset) setUsePreset(true) }
            .build().takeIf { it != lora }
        NodeEdits(
            owner = newOwner, device = newDevice, position = newPos, fixed = newFix,
            removeFixed = pos.fixedPosition && !fixed, lora = newLora,
            channels = editedChannels(channels, precision, primary),
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HudHeader("Ajustes del nodo")

            // ---------- Identidad ----------
            HudPanel(Modifier.fillMaxWidth(), title = "Identidad") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HudStat("Id", owner.id.ifEmpty { me?.let { "!%08x".format(it) } ?: "—" })
                    HudStat("Placa", Hardware.name(owner.hwModel), Modifier.weight(1f, fill = false))
                    meta?.firmwareVersion?.takeIf { it.isNotEmpty() }?.let { HudStat("Firmware", it.substringBeforeLast('.')) }
                }
                firmwareWarning(meta?.firmwareVersion)?.let { Warn(it, SigWeak) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = longName,
                    onValueChange = { longName = it.fitBytes(MAX_LONG_NAME) },
                    label = { Text("Nombre largo") },
                    singleLine = true,
                    isError = !longOk,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.fitBytes(MAX_SHORT_NAME) },
                    label = { Text("Nombre corto (hasta 4)") },
                    singleLine = true,
                    isError = !shortOk,
                    modifier = Modifier.fillMaxWidth(),
                )
                Note("El corto es el que se ve en el mapa y en la pantalla de los otros nodos.")
            }

            // ---------- Rol ----------
            HudPanel(Modifier.fillMaxWidth(), title = "Rol") {
                Select("Qué hace el nodo", role, ROLES.map { it.first }, { roleName(it) }, { r -> ROLES.first { it.first == r }.second }) { role = it }
                Note(ROLES.firstOrNull { it.first == role }?.second ?: "")
                if (role != device.role && role in WARN_ROLES) Warn("Este rol cambia también intervalos del nodo y lo marca como \"no recibe mensajes directos\".")
                Spacer(Modifier.height(8.dp))
                Select("Qué repite", rebroadcast, REBROADCAST.map { it.first }, { rebroadcastName(it) }, { m -> REBROADCAST.first { it.first == m }.second }) { rebroadcast = it }
                Note(REBROADCAST.firstOrNull { it.first == rebroadcast }?.second ?: "")
                if (rebroadcast == RebroadcastMode.NONE && role in listOf(Role.ROUTER, Role.ROUTER_LATE)) {
                    Warn("Un router no puede tener \"Nada\": el nodo lo va a dejar en \"Todo\".")
                }
            }

            // ---------- Posición ----------
            HudPanel(Modifier.fillMaxWidth(), title = "Posición") {
                val hasGps = pos.gpsMode == PositionConfig.GpsMode.ENABLED
                Choice(if (hasGps) "Del GPS del nodo" else "Del celular", !fixed) { fixed = false }
                Choice("Fija (cargada a mano)", fixed) { fixed = true }
                if (fixed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = latText, onValueChange = { latText = it; altitude = null },
                            label = { Text("Latitud") }, singleLine = true, isError = lat == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = lonText, onValueChange = { lonText = it; altitude = null },
                            label = { Text("Longitud") }, singleLine = true, isError = lon == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    HudOutlinedButton(onClick = { fix?.let { useFix(it) { la, lo, alt -> latText = la; lonText = lo; altitude = alt } } }, enabled = fix != null) {
                        Text(if (fix != null) "USAR MI UBICACIÓN (±${fix!!.accuracy.toInt()} m)" else "SIN GPS EN EL CELU")
                    }
                    Note("Con posición fija el nodo ignora la del celular. Sirve para nodos que quedan quietos (una base, un repetidor).")
                } else {
                    Note(
                        if (hasGps) "El nodo usa su propio GPS."
                        else "Este nodo no tiene GPS (o está apagado): usa la del celular conectado (interruptor \"Tu posición al grupo\" en Nodos)."
                    )
                }
                Spacer(Modifier.height(8.dp))
                Select("Mandar la posición cada", posSecs, withCurrent(INTERVALS.map { it.first }, posSecs), { intervalName(it) }) { posSecs = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Difusión inteligente")
                        Note("Además, manda antes si se movió.")
                    }
                    Switch(checked = smart, onCheckedChange = { smart = it })
                }
                if (smart) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            Select("Si se movió", smartDist, withCurrent(SMART_DISTANCES, smartDist), { if (it == 0) "por defecto" else "$it m" }) { smartDist = it }
                        }
                        Box(Modifier.weight(1f)) {
                            Select("Como mucho cada", smartSecs, withCurrent(SMART_INTERVALS, smartSecs), { intervalName(it) }) { smartSecs = it }
                        }
                    }
                }
            }

            // ---------- Radio ----------
            HudPanel(Modifier.fillMaxWidth(), title = "Radio") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HudStat("Región", lora.region.name)
                    HudStat("Frecuencia", frequencyText(lora.region, preset, slot) ?: "—")
                }
                Spacer(Modifier.height(8.dp))
                Select("Preset (velocidad / alcance)", preset, withCurrent(presetsFor(lora.region), preset), { presetName(it) }) { preset = it }
                val slots = slotCount(lora.region, preset)
                if (slots != null) {
                    Select("Canal de frecuencia (slot)", slot, (0..slots).toList(), { s ->
                        if (s == 0) "automático (sale del nombre del canal principal)"
                        else "slot $s · ${frequencyText(lora.region, preset, s)}"
                    }) { slot = it }
                } else {
                    OutlinedTextField(
                        value = slot.toString(), onValueChange = { slot = it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0 },
                        label = { Text("Canal de frecuencia (slot, 0 = automático)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        Select("Saltos", hops, (1..7).toList(), { "$it" }) { hops = it }
                    }
                    Box(Modifier.weight(1f)) {
                        Select("Potencia", txPower, withCurrent(txPowersFor(lora.region), txPower), { if (it == 0) "máxima" else "$it dBm" }) { txPower = it }
                    }
                }
                if (preset != lora.modemPreset || slot != lora.channelNum) {
                    Warn("Con otro preset o slot, este nodo deja de oír a los que no tengan lo mismo (el grupo usa LongFast, slot ${Group.ANZ_LONGFAST_SLOT}).")
                }
            }

            // ---------- Canales ----------
            HudPanel(Modifier.fillMaxWidth(), title = "Canales") {
                val private0 = channels[0]?.settings?.let(Group::isPrivate) == true
                val privateOther = channels.entries.firstOrNull { it.key != 0 && Group.isPrivate(it.value.settings) }
                if (!private0 && privateOther != null && primary == 0) {
                    Warn("El canal privado no es el principal: el nombre, la batería y la posición de este nodo salen por el público.")
                }
                for ((i, ch) in channels.toSortedMap()) {
                    val s = ch.settings
                    val shownIndex = when (i) { primary -> 0; 0 -> primary; else -> i }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name.ifEmpty { presetName(lora.modemPreset) },
                                fontWeight = if (shownIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            )
                            HudLabel(
                                (if (shownIndex == 0) "principal" else "secundario $shownIndex") + " · " + when {
                                    Group.isPrivate(s) -> "privado"
                                    s.psk.size() == 1 -> "público"
                                    s.psk.isEmpty -> "sin cifrar"
                                    else -> "clave propia"
                                },
                                color = if (Group.isPrivate(s)) HudCyan else HudTextDim,
                            )
                        }
                        if (i != 0 && shownIndex != 0) {
                            TextButton(onClick = { primary = i }) { Text("HACER PRINCIPAL") }
                        } else if (i != 0) {
                            TextButton(onClick = { primary = 0 }) { Text("DESHACER") }
                        }
                    }
                    val prec = precision[i] ?: 0
                    Select("Posición por este canal", prec, withCurrent(PRECISIONS, prec), { precisionName(it) }) {
                        precision = precision + (i to it)
                    }
                }
                if (primary != 0 && lora.channelNum == 0) {
                    Warn("El slot está en automático: cambiar el principal cambia la frecuencia. Fijá el slot en Radio.")
                }
                Note("El nombre y la clave de los canales se cambian en la pestaña Grupo.")
            }

            // ---------- Mantenimiento ----------
            HudPanel(Modifier.fillMaxWidth(), title = "Mantenimiento") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudOutlinedButton(onClick = { askReboot = true }) { Text("REINICIAR") }
                    HudOutlinedButton(onClick = { askForget = true }) { Text("OLVIDAR NODOS") }
                }
                Note("Olvidar nodos borra la lista de nodos conocidos (deja los favoritos). Sirve para limpiar nodos fantasma.")
            }

            // ---------- Acerca de ----------
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CalandriApp", style = MaterialTheme.typography.titleMedium, color = HudCyan)
                HudLabel("versión ${appVersion()}")
                Spacer(Modifier.height(6.dp))
                Text("powered by Meshtastic®", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Meshtastic® es una marca registrada de Meshtastic LLC. Los componentes de software de " +
                        "Meshtastic se publican bajo distintas licencias; ver GitHub para más detalles. " +
                        "CalandriApp es una app independiente: no es la app oficial ni está patrocinada " +
                        "ni respaldada por el proyecto Meshtastic. Sin garantía: se usa bajo tu propio riesgo.",
                    style = MaterialTheme.typography.bodySmall, color = HudTextDim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp),
                )
            }

            // ---------- Licencias ----------
            HudPanel(Modifier.fillMaxWidth(), title = "Licencias") {
                for ((what, license) in LICENSES) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(what, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = HudTextDim)
                    }
                }
                Note("Datos del mapa © colaboradores de OpenStreetMap, disponibles bajo la licencia ODbL (openstreetmap.org/copyright).")
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---------- barra de guardar ----------
        Column(Modifier.fillMaxWidth().background(HudSurface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(HudLine))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        !valid -> "Revisá los campos en rojo"
                        edits.isEmpty -> "Sin cambios"
                        else -> "Cambios: " + changedSections(edits).joinToString(", ")
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!valid) SigWeak else HudTextDim,
                    maxLines = 2,
                )
                HudButton(onClick = { confirm = edits }, enabled = valid && !edits.isEmpty) { Text("GUARDAR") }
            }
        }
    }

    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("GUARDAR EN EL NODO") },
            text = { Text("Se cambia: ${changedSections(e).joinToString(", ")}.\n\nEl nodo guarda y se reinicia; la app se vuelve a conectar sola.") },
            confirmButton = { TextButton(onClick = { vm.saveNodeSettings(e); confirm = null }) { Text("GUARDAR") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCELAR") } },
        )
    }
    if (askReboot) {
        AlertDialog(
            onDismissRequest = { askReboot = false },
            title = { Text("REINICIAR EL NODO") },
            text = { Text("Se corta el Bluetooth unos segundos y la app se vuelve a conectar sola.") },
            confirmButton = { TextButton(onClick = { vm.rebootNode(); askReboot = false }) { Text("REINICIAR") } },
            dismissButton = { TextButton(onClick = { askReboot = false }) { Text("CANCELAR") } },
        )
    }
    if (askForget) {
        AlertDialog(
            onDismissRequest = { askForget = false },
            title = { Text("OLVIDAR NODOS") },
            text = { Text("El nodo borra su lista de nodos conocidos (menos los favoritos) y se reinicia. Los va a ir conociendo de nuevo a medida que los oiga.") },
            confirmButton = { TextButton(onClick = { vm.forgetNodes(); askForget = false }) { Text("OLVIDAR") } },
            dismissButton = { TextButton(onClick = { askForget = false }) { Text("CANCELAR") } },
        )
    }
}

// ---------- piezas de UI ----------

@Composable
private fun <T> Select(
    label: String,
    value: T,
    options: List<T>,
    text: (T) -> String,
    detail: ((T) -> String?)? = null,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp)) {
        HudLabel(label)
        Spacer(Modifier.height(2.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().border(1.dp, HudLine, HudButtonShape).clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text(value), Modifier.weight(1f), maxLines = 1)
                Text("▾", color = HudCyan)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(text(o), fontWeight = if (o == value) FontWeight.Bold else FontWeight.Normal)
                                detail?.invoke(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HudTextDim) }
                            }
                        },
                        onClick = { onSelect(o); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}

@Composable
private fun Note(text: String) {
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = HudTextDim, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Warn(text: String, color: Color = SigFair) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Text("▲", color = color, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Lo que la app incluye o usa, con su licencia. */
private val LICENSES = listOf(
    "Protobufs de Meshtastic" to "GPL-3.0",
    "osmdroid" to "Apache-2.0",
    "ZXing / zxing-android-embedded" to "Apache-2.0",
    "Protocol Buffers (lite)" to "BSD-3-Clause",
    "AndroidX y Jetpack Compose" to "Apache-2.0",
    "Kotlin y kotlinx.coroutines" to "Apache-2.0",
    "Space Grotesk (logo)" to "OFL-1.1",
    "Datos de OpenStreetMap" to "ODbL",
)

@Composable
private fun appVersion(): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—"
    }
}

// ---------- datos ----------

private const val MAX_LONG_NAME = 39  // User.long_name max_size:40 (incluye el \0)
private const val MAX_SHORT_NAME = 4  // User.short_name max_size:5

/** Corta el texto para que entre en [max] bytes UTF-8 sin partir caracteres. */
private fun String.fitBytes(max: Int): String {
    var out = this
    while (out.toByteArray().size > max) out = out.dropLast(1)
    return out
}

private fun useFix(loc: Location, set: (String, String, Int?) -> Unit) {
    set(
        "%.6f".format(Locale.US, loc.latitude),
        "%.6f".format(Locale.US, loc.longitude),
        if (loc.hasAltitude()) loc.altitude.roundToInt() else null,
    )
}

/** Roles vigentes (sin los deprecados), en el orden en que conviene elegirlos. */
private val ROLES: List<Pair<Role, String>> = listOf(
    Role.CLIENT to "Lo normal: manda y recibe, y repite lo que oye.",
    Role.CLIENT_MUTE to "No repite nada. Para un nodo pegado a otro del grupo, así no duplican.",
    Role.CLIENT_HIDDEN to "Transmite lo mínimo indispensable (sigilo o ahorro de batería).",
    Role.CLIENT_BASE to "Para un techo o altillo: repite como router lo de tus favoritos y como cliente lo demás.",
    Role.TRACKER to "Prioriza mandar la posición.",
    Role.SENSOR to "Prioriza mandar telemetría (sensores).",
    Role.ROUTER to "Infraestructura en un cerro o torre con muy buena cobertura. No para techos ni móviles.",
    Role.ROUTER_LATE to "Repite siempre, pero después de los demás. Para cubrir huecos.",
    Role.LOST_AND_FOUND to "Manda su ubicación como mensaje por el canal público cada 5 min. Solo para recuperarlo.",
    Role.TAK to "Para usar con ATAK; reduce los envíos de rutina.",
    Role.TAK_TRACKER to "Para ATAK: manda la posición seguido y reduce lo demás.",
)

private val WARN_ROLES = setOf(Role.ROUTER, Role.ROUTER_LATE, Role.SENSOR, Role.TRACKER, Role.TAK_TRACKER)

private fun roleName(r: Role): String = when (r) {
    Role.CLIENT -> "Cliente"
    Role.CLIENT_MUTE -> "Cliente mudo"
    Role.CLIENT_HIDDEN -> "Cliente oculto"
    Role.CLIENT_BASE -> "Base"
    Role.TRACKER -> "Rastreador"
    Role.SENSOR -> "Sensor"
    Role.ROUTER -> "Router"
    Role.ROUTER_LATE -> "Router tardío"
    Role.LOST_AND_FOUND -> "Objeto perdido"
    Role.TAK -> "TAK"
    Role.TAK_TRACKER -> "TAK rastreador"
    else -> r.name.lowercase().replace('_', ' ')
}

private val REBROADCAST: List<Pair<RebroadcastMode, String>> = listOf(
    RebroadcastMode.ALL to "Repite todo, también lo de otras redes con la misma radio. Lo normal: ayuda a todos.",
    RebroadcastMode.LOCAL_ONLY to "Solo lo de sus propios canales; ignora redes ajenas.",
    RebroadcastMode.KNOWN_ONLY to "Como el anterior, y además solo de nodos que ya conoce.",
    RebroadcastMode.CORE_PORTNUMS_ONLY to "Solo lo básico: mensajes, posición, telemetría, nodos, waypoints.",
    RebroadcastMode.NONE to "No repite nada (como el cliente mudo).",
)

private fun rebroadcastName(m: RebroadcastMode): String = when (m) {
    RebroadcastMode.ALL -> "Todo"
    RebroadcastMode.LOCAL_ONLY -> "Solo mis canales"
    RebroadcastMode.KNOWN_ONLY -> "Solo nodos conocidos"
    RebroadcastMode.CORE_PORTNUMS_ONLY -> "Solo lo básico"
    RebroadcastMode.NONE -> "Nada"
    else -> m.name.lowercase().replace('_', ' ')
}

private val INTERVALS: List<Pair<Int, String>> = listOf(
    60 to "1 min", 120 to "2 min", 300 to "5 min", 600 to "10 min", 900 to "15 min",
    1800 to "30 min", 3600 to "1 h", 3 * 3600 to "3 h", 12 * 3600 to "12 h", 24 * 3600 to "24 h",
)
private val SMART_DISTANCES = listOf(10, 25, 50, 100, 250, 500)
private val SMART_INTERVALS = listOf(15, 30, 60, 120, 300, 600)

private fun intervalName(s: Int): String = when {
    s == 0 -> "por defecto"
    s % 3600 == 0 -> "${s / 3600} h"
    s % 60 == 0 -> "${s / 60} min"
    else -> "$s s"
}

/** Precisiones de posición por canal: 0 = no manda, 32 = exacta, el resto redondea a una celda. */
private val PRECISIONS = listOf(32, 19, 17, 16, 15, 14, 13, 0)

private fun precisionName(bits: Int): String = when {
    bits == 0 -> "no manda la posición"
    bits >= 32 -> "exacta"
    else -> {
        // Tamaño de la celda N-S: 2^(32-bits) unidades de 1e-7 grados.
        val m = 2.0.pow(32 - bits) * 1e-7 * 111_320
        "aproximada, ≈ " + if (m >= 1000) "%.1f km".format(Locale.forLanguageTag("es-AR"), m / 1000) else "${m.roundToInt()} m"
    }
}

/** Presets que acepta el firmware en la región (perfiles de RadioInterface.cpp). */
private fun presetsFor(region: RegionCode): List<ModemPreset> = Regions.presets(region)

private fun presetName(p: ModemPreset): String =
    p.name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

private fun slotCount(region: RegionCode, p: ModemPreset): Int? = Regions.slotCount(region, p)

private fun frequencyText(region: RegionCode, p: ModemPreset, slot: Int): String? {
    if (slot == 0) return "automática"
    val mhz = Regions.frequencyMHz(region, p, slot) ?: return null
    return "%.3f MHz".format(Locale.forLanguageTag("es-AR"), mhz)
}

/** Potencias para elegir, hasta el máximo legal de la región (el firmware además lo limita al de la radio). */
private fun txPowersFor(region: RegionCode): List<Int> {
    val max = Regions.band(region)?.maxDbm ?: 30
    return listOf(0) + listOf(30, 27, 24, 22, 20, 17, 14, 10, 7).filter { it <= max }
}

/** Avisos sobre el firmware del nodo: el formato es "2.7.26.54e0d8d". */
private fun firmwareWarning(version: String?): String? {
    val parts = version?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return null
    if (parts.size < 2) return null
    val (major, minor) = parts
    return when {
        major < 2 || (major == 2 && minor < 3) ->
            "Firmware muy viejo: no informa los saltos y varias funciones no andan. Actualizalo (flasher.meshtastic.org)."
        major == 2 && minor < 5 ->
            "Firmware viejo: sin mensajes directos cifrados (PKI). Conviene actualizarlo (flasher.meshtastic.org)."
        else -> null
    }
}

/** Si el valor actual no está en la lista (lo puso otra app), lo agrega para no perderlo. */
private fun <T> withCurrent(options: List<T>, current: T): List<T> =
    if (current in options) options else options + current

/** Canales que cambian: precisión por canal y, si se eligió otro principal, el intercambio con el 0. */
private fun editedChannels(channels: Map<Int, Channel>, precision: Map<Int, Int>, primary: Int): List<Channel> {
    val edited = channels.mapValues { (i, ch) ->
        val s = ch.settings
        val want = precision[i] ?: s.moduleSettings.positionPrecision
        if (want == s.moduleSettings.positionPrecision) ch
        else ch.toBuilder().setSettings(
            s.toBuilder().setModuleSettings(s.moduleSettings.toBuilder().setPositionPrecision(want))
        ).build()
    }
    val swapped = if (primary != 0 && edited[0] != null && edited[primary] != null) {
        val a = edited.getValue(0)
        val b = edited.getValue(primary)
        edited + (0 to a.toBuilder().setSettings(b.settings).build()) +
            (primary to b.toBuilder().setSettings(a.settings).build())
    } else edited
    return swapped.filter { (i, ch) -> ch != channels[i] }.values.sortedBy { it.index }
}

private fun changedSections(e: NodeEdits): List<String> = buildList {
    if (e.owner != null) add("nombre")
    if (e.device != null) add("rol")
    if (e.position != null || e.fixed != null || e.removeFixed) add("posición")
    if (e.lora != null) add("radio")
    if (e.channels.isNotEmpty()) add("canales")
}
