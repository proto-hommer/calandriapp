package io.github.protohommer.meshpaper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.protohommer.meshpaper.ble.LinkState
import io.github.protohommer.meshpaper.ui.ChatScreen
import io.github.protohommer.meshpaper.ui.ChatsScreen
import io.github.protohommer.meshpaper.ui.GroupScreen
import io.github.protohommer.meshpaper.mesh.Conv
import io.github.protohommer.meshpaper.ui.theme.MeshPaperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MeshPaperTheme { App() } }
    }
}

private val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MeshViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(blePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> granted = res.values.all { it } }

    val link by vm.link.collectAsState()
    val apply by vm.apply.collectAsState()
    // Pantalla dentro de la sesión conectada: "nodes", "group", "chats" o "chat:<conv>".
    var screen by rememberSaveable { mutableStateOf("nodes") }
    BackHandler(enabled = link is LinkState.Connected && screen != "nodes") {
        screen = if (screen.startsWith("chat:")) "chats" else "nodes"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Mesh Paper") }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                !granted -> Center {
                    Text("La app necesita permiso de Bluetooth para hablar con el nodo.")
                    Spacer(Modifier.padding(8.dp))
                    Button(onClick = { launcher.launch(blePermissions) }) { Text("Dar permiso") }
                }
                !vm.bluetoothOn -> Center { Text("Prendé el Bluetooth y volvé a abrir la app.") }
                apply is ApplyState.Working -> Center {
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(8.dp))
                    Text((apply as ApplyState.Working).step)
                }
                link is LinkState.Disconnected || link is LinkState.Failed -> ScanScreen(vm, link)
                link is LinkState.NeedPin -> PinScreen(vm)
                link is LinkState.Connecting -> Center {
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(8.dp))
                    Text((link as LinkState.Connecting).step)
                    Spacer(Modifier.padding(8.dp))
                    OutlinedButton(onClick = vm::disconnect) { Text("Cancelar") }
                }
                screen == "group" -> GroupScreen(vm, onBack = { screen = "nodes" })
                screen == "chats" -> ChatsScreen(vm, onOpen = { screen = "chat:$it" }, onBack = { screen = "nodes" })
                screen.startsWith("chat:") -> ChatScreen(vm, screen.removePrefix("chat:"), onBack = { screen = "chats" })
                else -> NodesScreen(
                    vm,
                    onGroup = { screen = "group" },
                    onChats = { screen = "chats" },
                    onNode = { screen = "chat:" + Conv.Direct(it).key },
                )
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun PinScreen(vm: MeshViewModel) {
    var pin by remember { mutableStateOf("123456") }
    Center {
        Text("El nodo pide PIN", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(4.dp))
        Text("Poné el que muestra la pantalla del nodo (por defecto 123456).")
        Spacer(Modifier.padding(8.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            label = { Text("PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Spacer(Modifier.padding(8.dp))
        Row {
            OutlinedButton(onClick = vm::disconnect) { Text("Cancelar") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { vm.submitPin(pin) }, enabled = pin.length == 6) { Text("Emparejar") }
        }
    }
}

@Composable
private fun ScanScreen(vm: MeshViewModel, link: LinkState) {
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (link is LinkState.Failed) {
            Text("Error: ${link.reason}", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.padding(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (scanning) vm.stopScan() else vm.startScan() }) {
                Text(if (scanning) "Detener" else "Buscar nodos")
            }
            if (scanning) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.padding(4.dp))
            }
        }
        Spacer(Modifier.padding(8.dp))
        LazyColumn {
            items(found, key = { it.device.address }) { d ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.Bold)
                        Text(
                            "${d.device.address} · ${d.rssi} dBm · " +
                                if (d.bonded) "emparejado" else "sin emparejar",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (d.bonded) {
                        Button(onClick = { vm.connect(d) }) { Text("Conectar") }
                    } else {
                        OutlinedButton(onClick = { vm.pairAndConnect(d) }) { Text("Emparejar") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NodesScreen(vm: MeshViewModel, onGroup: () -> Unit, onChats: () -> Unit, onNode: (Int) -> Unit) {
    val nodes by vm.nodes.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val channels by vm.channels.collectAsState()
    val since by vm.groupSince.collectAsState()
    val apply by vm.apply.collectAsState()
    val msgs by vm.messages.collectAsState()
    val lastRead by vm.lastRead.collectAsState()
    val unread = msgs.count { !it.mine && it.time > (lastRead[it.conv] ?: 0) }
    val now = System.currentTimeMillis() / 1000
    val hasGroup = remember(channels) { vm.hasPrivateGroup() }
    val groupName = channels[0]?.settings?.name.orEmpty()

    // Miembros: los oímos por el canal privado (índice 0) después de armar el grupo.
    fun isMember(n: MeshNode) = n.num == me ||
        (hasGroup && n.channel == 0 && n.lastHeard > 0 && n.lastHeard >= since)
    val byRecent = compareByDescending<MeshNode> { it.num == me }.thenByDescending { it.lastHeard }
    val members = nodes.values.filter(::isMember).sortedWith(byRecent)
    val others = nodes.values.filterNot(::isMember).sortedWith(byRecent)
    var showOthers by remember { mutableStateOf(!hasGroup) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (hasGroup) "Grupo: $groupName" else "Sin grupo privado",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasGroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text("${members.size} en el grupo · ${others.size} otros", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onGroup) { Text(if (hasGroup) "QR" else "Armar grupo") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::disconnect) { Text("Salir") }
        }
        Spacer(Modifier.padding(2.dp))
        Button(onClick = onChats, Modifier.fillMaxWidth()) {
            Text(if (unread > 0) "Mensajes ($unread sin leer)" else "Mensajes")
        }
        when (val a = apply) {
            is ApplyState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Grupo aplicado al nodo.", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = vm::dismissApply) { Text("OK") }
            }
            is ApplyState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("No se pudo aplicar: ${a.reason}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::dismissApply) { Text("OK") }
            }
            else -> Unit
        }
        Spacer(Modifier.padding(4.dp))
        LazyColumn {
            items(members, key = { it.num }) { n -> NodeRow(n, me, now, onClick = onNode) }
            if (others.isNotEmpty()) {
                item(key = "others") {
                    TextButton(onClick = { showOthers = !showOthers }) {
                        Text((if (showOthers) "▾ " else "▸ ") +
                            (if (hasGroup) "Otros nodos / repetidores (${others.size})" else "Nodos (${others.size})"))
                    }
                }
                if (showOthers) items(others, key = { it.num }) { n -> NodeRow(n, me, now, dim = hasGroup, onClick = onNode) }
            }
        }
    }
}

@Composable
private fun NodeRow(n: MeshNode, me: Int?, now: Long, dim: Boolean = false, onClick: (Int) -> Unit = {}) {
    val alpha = if (dim) 0.6f else 1f
    val tap = if (n.num != me) Modifier.clickable { onClick(n.num) } else Modifier
    Column(Modifier.fillMaxWidth().then(tap).padding(vertical = 10.dp)) {
        Row {
            Text(
                "${n.shortName.ifEmpty { "?" }}  ${n.longName.ifEmpty { n.id }}",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (n.num == me) Text("  (este nodo)", color = MaterialTheme.colorScheme.primary)
        }
        val parts = buildList {
            add(n.id)
            if (n.num != me) add(ago(now, n.lastHeard))
            n.hopsAway?.let { add(if (it == 0) "directo" else "$it saltos") }
            if (n.hopsAway == 0 && n.snr != 0f) add("SNR %.1f".format(n.snr))
            n.battery?.let { add(if (it > 100) "enchufado" else "bat $it%") }
            if (n.lat != null) add("con posición")
        }
        Text(
            parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
    }
    HorizontalDivider()
}

private fun ago(now: Long, t: Long): String {
    if (t == 0L) return "nunca oído"
    val s = (now - t).coerceAtLeast(0)
    return when {
        s < 60 -> "hace ${s}s"
        s < 3600 -> "hace ${s / 60} min"
        s < 86400 -> "hace ${s / 3600} h"
        else -> "hace ${s / 86400} d"
    }
}
