package io.github.protohommer.meshpaper

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.protohommer.meshpaper.ble.LinkState
import io.github.protohommer.meshpaper.mesh.Conv
import io.github.protohommer.meshpaper.mesh.PhoneLocation
import io.github.protohommer.meshpaper.mesh.Signal
import io.github.protohommer.meshpaper.ui.ChatScreen
import io.github.protohommer.meshpaper.ui.ChatsScreen
import io.github.protohommer.meshpaper.ui.GroupScreen
import io.github.protohommer.meshpaper.ui.HudButton
import io.github.protohommer.meshpaper.ui.HudHeader
import io.github.protohommer.meshpaper.ui.HudLabel
import io.github.protohommer.meshpaper.ui.HudOutlinedButton
import io.github.protohommer.meshpaper.ui.HudPanel
import io.github.protohommer.meshpaper.ui.HudStat
import io.github.protohommer.meshpaper.ui.HudValue
import io.github.protohommer.meshpaper.ui.MapScreen
import io.github.protohommer.meshpaper.ui.brand.BrandIntro
import io.github.protohommer.meshpaper.ui.SettingsScreen
import io.github.protohommer.meshpaper.ui.StatusDot
import io.github.protohommer.meshpaper.ui.agoText
import io.github.protohommer.meshpaper.ui.color
import io.github.protohommer.meshpaper.ui.hudCorners
import io.github.protohommer.meshpaper.ui.theme.HudBg
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudLine
import io.github.protohommer.meshpaper.ui.theme.HudSurface
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.MeshPaperTheme
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigWeak

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // antes de super.onCreate: fondo de la marca hasta el primer cuadro
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        val firstLaunch = savedInstanceState == null
        setContent {
            MeshPaperTheme {
                // La intro solo al abrir la app de cero (no al rotar ni al volver de segundo plano).
                var intro by rememberSaveable { mutableStateOf(firstLaunch) }
                Box(Modifier.fillMaxSize()) {
                    App()
                    if (intro) BrandIntro(onDone = { intro = false })
                }
            }
        }
    }
}

private val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
)

private val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private enum class Tab(val label: String) {
    MAP("Mapa"), NODES("Nodos"), CHATS("Mensajes"), GROUP("Grupo"), SETTINGS("Ajustes")
}

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
    ) { res ->
        granted = blePermissions.all { res[it] ?: (ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED) }
        vm.locationPermitted = hasLocation(context)
    }
    LaunchedEffect(Unit) { vm.locationPermitted = hasLocation(context) }
    LaunchedEffect(granted) { if (granted && vm.bluetoothOn) vm.autoConnect() }

    val link by vm.link.collectAsState()
    val apply by vm.apply.collectAsState()
    val msgs by vm.messages.collectAsState()
    val lastRead by vm.lastRead.collectAsState()
    val unread = msgs.count { !it.mine && it.time > (lastRead[it.conv] ?: 0) }

    var tabName by rememberSaveable { mutableStateOf(Tab.MAP.name) }
    val tab = Tab.valueOf(tabName)
    var chat by rememberSaveable { mutableStateOf<String?>(null) }
    val connected = link == LinkState.Connected

    BackHandler(enabled = tab != Tab.MAP || chat != null) {
        if (tab == Tab.CHATS && chat != null) chat = null else tabName = Tab.MAP.name
    }
    fun openChat(key: String) { chat = key; tabName = Tab.CHATS.name }

    // La barra de arriba flota sobre el contenido (sobre el mapa se ve el mapa debajo); el resto
    // de las pantallas arrancan debajo de ella.
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    Column(Modifier.fillMaxSize().background(HudBg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !granted -> Box(Modifier.fillMaxSize().padding(top = topPad)) {
                    CenterPanel("Permisos") {
                        Text("La app necesita Bluetooth para hablar con el nodo y ubicación para compartir tu posición con el grupo.")
                        Spacer(Modifier.height(12.dp))
                        HudButton(onClick = { launcher.launch(blePermissions + locationPermissions) }) { Text("DAR PERMISO") }
                    }
                }
                !vm.bluetoothOn -> Box(Modifier.fillMaxSize().padding(top = topPad)) {
                    CenterPanel("Bluetooth apagado") {
                        Text("Prendé el Bluetooth y volvé a abrir la app.")
                    }
                }
                else -> {
                    when (tab) {
                        Tab.MAP -> MapScreen(vm, onMessage = { openChat(Conv.Direct(it).key) }, topInset = topPad)
                        else -> Column(Modifier.fillMaxSize().padding(top = topPad)) {
                            if (!connected) LinkPanel(vm, link, Modifier.padding(12.dp))
                            when (tab) {
                                Tab.NODES -> NodesScreen(vm, onAskLocation = { launcher.launch(locationPermissions) }, onNode = { openChat(Conv.Direct(it).key) })
                                Tab.CHATS -> if (chat != null) {
                                    Box(Modifier.navigationBarsPadding()) { ChatScreen(vm, chat!!, onBack = { chat = null }) }
                                } else ChatsScreen(vm, onOpen = { chat = it })
                                Tab.GROUP -> if (connected) GroupScreen(vm, onBack = { tabName = Tab.MAP.name })
                                else Unit
                                Tab.SETTINGS -> SettingsScreen(vm, connected)
                                Tab.MAP -> Unit
                            }
                        }
                    }
                    if (tab == Tab.MAP && !connected) LinkPanel(vm, link, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = topPad + 4.dp, end = 70.dp))
                    ApplyBanner(vm, apply, Modifier.align(Alignment.TopCenter).padding(top = topPad).padding(12.dp))
                }
            }
            TopStatus(vm, Modifier.align(Alignment.TopCenter))
            if (apply is ApplyState.Working) WorkingOverlay((apply as ApplyState.Working).step)
        }
        if (!(tab == Tab.CHATS && chat != null)) {
            NavBar(tab, unread, onSelect = { tabName = it.name; if (it != Tab.CHATS) chat = null })
        }
    }
    if (link is LinkState.NeedPin) PinDialog(vm)
}

// ---------- Barra de estado superior ----------

/** Alto de la barra de arriba, sin contar la barra del sistema. */
private val TopBarHeight = 54.dp

/**
 * Estado arriba: sin franja ni línea, flota con un degradado del fondo hacia transparente,
 * así sobre el mapa se integra y en las otras pantallas se funde con el fondo.
 */
@Composable
private fun TopStatus(vm: MeshViewModel, modifier: Modifier = Modifier) {
    val link by vm.link.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val channels by vm.channels.collectAsState()
    val since by vm.groupSince.collectAsState()
    val fix by vm.myFix.collectAsState()
    val hasGroup = remember(channels) { vm.hasPrivateGroup() }
    val myNode = me?.let { nodes[it] }
    val members = nodes.values.count { it.num != me && isGroupMember(it, me, hasGroup, since) }
    val connected = link == LinkState.Connected
    val (linkColor, state) = when (link) {
        LinkState.Connected -> SigGood to "enlazado"
        is LinkState.Connecting, LinkState.NeedPin -> SigFair to "conectando"
        is LinkState.Failed -> SigWeak to "sin enlace"
        LinkState.Disconnected -> HudTextDim to "sin enlace"
    }
    val nodeName = if (connected) myNode?.shortName?.ifEmpty { null } ?: myNode?.id ?: "—" else "—"
    Box(
        modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                0f to HudBg.copy(alpha = 0.97f),
                0.7f to HudBg.copy(alpha = 0.82f),
                1f to Color.Transparent,
            )
        )
    ) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .height(TopBarHeight + 16.dp).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // El nodo y el estado del enlace.
            Box(Modifier.width(3.dp).height(30.dp).background(linkColor))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                HudValue(nodeName.uppercase(), color = if (connected) MaterialTheme.colorScheme.onSurface else HudTextDim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(linkColor, 6.dp)
                    Spacer(Modifier.width(5.dp))
                    HudLabel("CalandriApp · $state", color = linkColor.copy(alpha = 0.85f))
                }
            }
            TopReadout(
                "Grupo",
                when {
                    channels.isEmpty() -> "—"
                    hasGroup -> channels[0]?.settings?.name.orEmpty().ifEmpty { "—" }
                    else -> "público"
                },
                when {
                    channels.isEmpty() -> HudTextDim
                    hasGroup -> HudCyan
                    else -> SigWeak
                },
                Modifier.widthIn(max = 92.dp),
            )
            TopDivider()
            TopReadout("Miemb.", "$members", MaterialTheme.colorScheme.onSurface)
            TopDivider()
            TopReadout("GPS", fix?.let { "±${it.accuracy.toInt()}m" } ?: "—", if (fix != null) SigGood else HudTextDim)
        }
    }
}

@Composable
private fun TopReadout(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HudLabel(label)
        HudValue(value, color = color)
    }
}

@Composable
private fun TopDivider() {
    Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(22.dp).background(HudLine))
}

// ---------- Navegación inferior ----------

@Composable
private fun NavBar(tab: Tab, unread: Int, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(HudSurface).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudLine))
        Row(Modifier.fillMaxWidth().height(50.dp)) {
            for (t in Tab.entries) {
                val active = t == tab
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.width(28.dp).height(2.dp).background(if (active) HudCyan else Color.Transparent))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t.label.uppercase() + if (t == Tab.CHATS && unread > 0) " ·$unread" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            active -> HudCyan
                            t == Tab.CHATS && unread > 0 -> SigFair
                            else -> HudTextDim
                        },
                    )
                }
            }
        }
        // Crédito al proyecto en el que se apoya la app (y a su marca).
        Text(
            "powered by Meshtastic®",
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = HudTextDim.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---------- Enlace con el nodo ----------

/** Panel para conectar: reconectar al último, buscar y emparejar. */
@Composable
private fun LinkPanel(vm: MeshViewModel, link: LinkState, modifier: Modifier = Modifier) {
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanHint by vm.scanHint.collectAsState()
    var searched by remember { mutableStateOf(false) }
    val last = remember(link) { vm.lastDeviceName() }
    HudPanel(modifier.fillMaxWidth(), title = "Enlace con el nodo", accent = if (link is LinkState.Failed) SigWeak else HudCyan) {
        when (link) {
            is LinkState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 10.dp).height(22.dp).width(22.dp), strokeWidth = 2.dp)
                Text(link.step, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::disconnect) { Text("CANCELAR") }
            }
            LinkState.NeedPin -> Text("Esperando el PIN…", style = MaterialTheme.typography.bodySmall)
            else -> {
                if (link is LinkState.Failed) {
                    Text(link.reason, color = SigWeak, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (last != null) HudButton(onClick = { vm.reconnectLast() }) { Text("CONECTAR ${last.take(14).uppercase()}") }
                    HudOutlinedButton(onClick = { if (scanning) vm.stopScan() else { searched = true; vm.startScan() } }) {
                        Text(if (scanning) "DETENER" else "BUSCAR")
                    }
                }
                scanHint?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = SigWeak, style = MaterialTheme.typography.bodySmall)
                }
                if (searched && !scanning && found.isEmpty() && scanHint == null) {
                    Spacer(Modifier.height(6.dp))
                    HudLabel(
                        "No apareció ningún nodo. Fijate que esté prendido y cerca, y que no esté conectado " +
                            "a otra app (la oficial de Meshtastic, por ejemplo): mientras tiene una conexión, el nodo no se anuncia."
                    )
                }
                if (scanning || found.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(found, key = { it.device.address }) { d ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, fontWeight = FontWeight.SemiBold)
                                    HudLabel(
                                        if (d.rssi == NO_RSSI) "emparejado · no se lo oye ahora"
                                        else "${d.rssi} dBm · " + if (d.bonded) "emparejado" else "sin emparejar"
                                    )
                                }
                                if (d.bonded) HudButton(onClick = { vm.connect(d) }) { Text("CONECTAR") }
                                else HudOutlinedButton(onClick = { vm.pairAndConnect(d) }) { Text("EMPAREJAR") }
                            }
                        }
                        if (scanning && found.isEmpty()) item { HudLabel("Buscando nodos Meshtastic…") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDialog(vm: MeshViewModel) {
    var pin by remember { mutableStateOf("123456") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("PIN DEL NODO") },
        text = {
            Column {
                Text("Poné el que muestra la pantalla del nodo (por defecto 123456).")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.submitPin(pin) }, enabled = pin.length == 6) { Text("EMPAREJAR") } },
        dismissButton = { TextButton(onClick = vm::disconnect) { Text("CANCELAR") } },
    )
}

@Composable
private fun ApplyBanner(vm: MeshViewModel, apply: ApplyState, modifier: Modifier) {
    val (text, color) = when (apply) {
        is ApplyState.Done -> apply.text to SigGood
        is ApplyState.Failed -> "No se pudo aplicar: ${apply.reason}" to SigWeak
        else -> return
    }
    HudPanel(modifier.fillMaxWidth(), accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = vm::dismissApply) { Text("OK") }
        }
    }
}

@Composable
private fun BoxScope.WorkingOverlay(step: String) {
    Box(Modifier.matchParentSize().background(HudBg.copy(alpha = 0.85f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        HudPanel(Modifier.padding(32.dp).hudCorners(), title = "Trabajando") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(step)
            }
        }
    }
}

@Composable
private fun CenterPanel(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        HudPanel(Modifier.fillMaxWidth().hudCorners(), title = title, padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            content()
        }
    }
}

// ---------- Nodos ----------

@Composable
private fun NodesScreen(vm: MeshViewModel, onAskLocation: () -> Unit, onNode: (Int) -> Unit) {
    val nodes by vm.nodes.collectAsState()
    val me by vm.myNodeNum.collectAsState()
    val channels by vm.channels.collectAsState()
    val since by vm.groupSince.collectAsState()
    val fix by vm.myFix.collectAsState()
    val link by vm.link.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(10_000); now = System.currentTimeMillis() / 1000 } }
    val hasGroup = remember(channels) { vm.hasPrivateGroup() }

    fun isMember(n: MeshNode) = isGroupMember(n, me, hasGroup, since)
    val byRecent = compareByDescending<MeshNode> { it.num == me }.thenByDescending { it.lastHeard }
    val members = nodes.values.filter(::isMember).sortedWith(byRecent)
    val others = nodes.values.filterNot(::isMember).sortedWith(byRecent)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            PositionPanel(vm, onAskLocation)
            Spacer(Modifier.height(12.dp))
        }
        item { SectionTitle("Grupo · ${members.size}", HudCyan) }
        if (members.isEmpty()) item {
            HudLabel(if (hasGroup) "Todavía no se oyó a nadie del grupo" else "Sin grupo privado: armalo en GRUPO")
            Spacer(Modifier.height(8.dp))
        }
        items(members, key = { it.num }) { n -> NodeRow(n, me, true, fix, now, onNode) }
        if (others.isNotEmpty()) {
            item { Spacer(Modifier.height(12.dp)); SectionTitle("Repetidores y otros · ${others.size}", MaterialTheme.colorScheme.tertiary) }
            items(others, key = { it.num }) { n -> NodeRow(n, me, false, fix, now, onNode) }
        }
        item {
            Spacer(Modifier.height(16.dp))
            if (link == LinkState.Connected) {
                HudOutlinedButton(onClick = vm::disconnect, Modifier.fillMaxWidth()) { Text("DESCONECTAR DEL NODO") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(12.dp).background(color))
        Spacer(Modifier.width(8.dp))
        HudLabel(text, color = color)
    }
}

@Composable
private fun NodeRow(n: MeshNode, me: Int?, member: Boolean, fix: Location?, now: Long, onClick: (Int) -> Unit) {
    val tone = Signal.tone(n, me, member, now)
    val lost = n.num != me && Signal.isLost(n, now)
    val tap = if (n.num != me) Modifier.clickable { onClick(n.num) } else Modifier
    Row(
        Modifier.fillMaxWidth().then(tap).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(44.dp).height(30.dp)
                .background(tone.color().copy(alpha = if (lost) 0.08f else 0.16f), io.github.protohommer.meshpaper.ui.HudButtonShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                n.shortName.ifEmpty { "?" }.take(4), style = MaterialTheme.typography.labelLarge,
                color = tone.color().copy(alpha = if (lost) 0.6f else 1f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.longName.ifEmpty { n.id }, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (n.num == me) { Spacer(Modifier.width(6.dp)); HudLabel("este nodo", color = HudCyan) }
            }
            val parts = buildList {
                if (n.num != me) add(agoText(now, n.lastHeard))
                Signal.describe(n)?.let { add(it) }
                n.battery?.let { add(if (it > 100) "USB" else "bat $it%") }
                if (fix != null && n.num != me && n.lat != null && n.lon != null) {
                    val (d, b) = PhoneLocation.distanceAndBearing(fix.latitude, fix.longitude, n.lat, n.lon)
                    add("${PhoneLocation.formatDistance(d)} ${PhoneLocation.cardinal(b)}")
                }
                if (!member && n.mute) add("no repite")
            }
            HudLabel(parts.joinToString(" · "))
        }
        if (n.num != me) {
            Spacer(Modifier.width(8.dp))
            // El punto es la señal (saltos); el color del nombre corto dice qué es el nodo.
            StatusDot(Signal.linkTone(tone, n, now).color().copy(alpha = if (lost) 0.4f else 1f), 10.dp)
        }
    }
    HorizontalDivider(color = HudLine)
}

private fun hasLocation(context: android.content.Context) = locationPermissions.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** Posición propia: si se comparte, cuándo se mandó y con qué precisión. */
@Composable
private fun PositionPanel(vm: MeshViewModel, onAskLocation: () -> Unit) {
    val context = LocalContext.current
    val share by vm.sharePosition.collectAsState()
    val fix by vm.myFix.collectAsState()
    val sent by vm.lastPosSent.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); tick++ } }
    val permitted = remember(tick) { hasLocation(context) }
    val nowS = remember(tick, sent) { System.currentTimeMillis() / 1000 }
    val status = when {
        !share -> "No se comparte"
        !permitted -> "Falta permiso de ubicación"
        fix == null -> "Buscando GPS…"
        sent == 0L -> "GPS ±${fix!!.accuracy.toInt()} m · todavía sin mandar"
        else -> "Mandada ${agoText(nowS, sent / 1000)} · ±${fix!!.accuracy.toInt()} m"
    }
    HudPanel(Modifier.fillMaxWidth(), title = "Tu posición al grupo") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (share && !permitted) TextButton(onClick = onAskLocation) { Text("PERMITIR") }
            else Switch(checked = share, onCheckedChange = vm::setSharePosition)
        }
    }
}
