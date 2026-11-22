package io.github.protohommer.meshpaper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.protohommer.meshpaper.ble.LinkState
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
                link is LinkState.Disconnected || link is LinkState.Failed -> ScanScreen(vm, link)
                link is LinkState.Connecting -> Center {
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(8.dp))
                    Text((link as LinkState.Connecting).step)
                    Spacer(Modifier.padding(8.dp))
                    OutlinedButton(onClick = vm::disconnect) { Text("Cancelar") }
                }
                else -> NodesScreen(vm)
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
private fun NodesScreen(vm: MeshViewModel) {
    val nodes by vm.nodes.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val now = System.currentTimeMillis() / 1000
    val sorted = nodes.values.sortedWith(
        compareByDescending<MeshNode> { it.num == me }.thenByDescending { it.lastHeard }
    )
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${nodes.size} nodos", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = vm::disconnect) { Text("Desconectar") }
        }
        Spacer(Modifier.padding(4.dp))
        LazyColumn {
            items(sorted, key = { it.num }) { n ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row {
                        Text(
                            "${n.shortName.ifEmpty { "?" }}  ${n.longName.ifEmpty { n.id }}",
                            fontWeight = FontWeight.Bold,
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
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
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
