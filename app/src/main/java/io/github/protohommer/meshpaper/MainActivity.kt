package io.github.protohommer.meshpaper

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.content.Intent
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState
import io.github.protohommer.meshpaper.ui.GlassStyle
import io.github.protohommer.meshpaper.ui.LocalHaze
import io.github.protohommer.meshpaper.ui.neonGlow
import io.github.protohommer.meshpaper.ui.neon
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
        val vm = (application as CalandriApp).mesh
        handleIntent(intent)
        // Sin intro si se abre tocando una notificación: se va directo al chat.
        val firstLaunch = savedInstanceState == null && intent?.getStringExtra(Notifier.EXTRA_CONV) == null
        setContent {
            MeshPaperTheme {
                // Íconos de la barra de estado y de navegación: claros de noche, oscuros de día.
                val day = io.github.protohommer.meshpaper.ui.theme.Appearance.isDay
                androidx.compose.runtime.LaunchedEffect(day) {
                    val style = if (day) SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
                    else SystemBarStyle.dark(AColor.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }

                // La intro solo al abrir la app de cero (no al rotar ni al volver de segundo plano).
                var intro by rememberSaveable { mutableStateOf(firstLaunch) }
                Box(Modifier.fillMaxSize()) {
                    App(vm)
                    if (intro) BrandIntro(onDone = { intro = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Tocar una notificación de mensaje abre esa conversación. */
    private fun handleIntent(intent: Intent?) {
        val conv = intent?.getStringExtra(Notifier.EXTRA_CONV) ?: return
        (application as CalandriApp).mesh.openRequest.value = conv
        intent.removeExtra(Notifier.EXTRA_CONV)
    }

    override fun onStart() {
        super.onStart()
        (application as CalandriApp).mesh.uiVisible = true
    }

    override fun onStop() {
        (application as CalandriApp).mesh.uiVisible = false
        super.onStop()
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
fun App(vm: MeshViewModel) {
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

    // Permiso de notificaciones (Android 13+), una sola vez: para avisar de mensajes con la app cerrada.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(context)) {
            val p = context.getSharedPreferences("mesh", android.content.Context.MODE_PRIVATE)
            if (!p.getBoolean("askedNotif", false)) {
                p.edit().putBoolean("askedNotif", true).apply()
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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

    // Conectada: el servicio mantiene la sesión con la pantalla apagada (se arranca con la app en pantalla).
    val keepAlive by vm.keepAlive.collectAsState()
    LaunchedEffect(link, keepAlive) { if (keepAlive && link == LinkState.Connected) MeshService.start(context) }

    // Pedido de abrir un chat desde una notificación.
    val openReq by vm.openRequest.collectAsState()
    LaunchedEffect(openReq) { openReq?.let { openChat(it); vm.openRequest.value = null } }

    // Qué chat está a la vista: de ese no se avisa.
    androidx.compose.runtime.SideEffect { vm.viewingConv = if (tab == Tab.CHATS) chat else null }

    // Cartel de mensaje nuevo dentro de la app (si estás en otra pantalla).
    var banner by remember { mutableStateOf<io.github.protohommer.meshpaper.mesh.ChatMsg?>(null) }
    LaunchedEffect(Unit) { vm.incoming.collect { banner = it } }
    LaunchedEffect(banner) { if (banner != null) { kotlinx.coroutines.delay(5_000); banner = null } }

    // La barra de arriba flota sobre el contenido (sobre el mapa se ve el mapa debajo); el resto
    // de las pantallas arrancan debajo de ella.
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    // La barra de abajo también flota: en el mapa se ve el mapa desenfocado debajo (vidrio).
    val showNav = !(tab == Tab.CHATS && chat != null)
    val density = LocalDensity.current
    var navH by remember { mutableStateOf(0.dp) }
    val bottomPad: Dp = if (showNav) navH else 0.dp
    // Lo que desenfocan los paneles: solo el mapa (las otras pantallas tienen fondo liso).
    val haze = rememberHazeState()
    CompositionLocalProvider(LocalHaze provides haze.takeIf { tab == Tab.MAP && granted && vm.bluetoothOn }) {
    Box(Modifier.fillMaxSize().background(HudBg)) {
        Box(Modifier.fillMaxSize().padding(bottom = if (tab == Tab.MAP) 0.dp else bottomPad)) {
            when {
                !granted -> Box(Modifier.fillMaxSize().padding(top = topPad)) {
                    CenterPanel("Permisos") {
                        Text("La app necesita Bluetooth para hablar con el nodo y ubicación para compartir tu posición con el grupo.")
                        Spacer(Modifier.height(12.dp))
                        HudButton(onClick = { launcher.launch(blePermissions + locationPermissions) }) { Text("Dar permiso") }
                    }
                }
                !vm.bluetoothOn -> Box(Modifier.fillMaxSize().padding(top = topPad)) {
                    CenterPanel("Bluetooth apagado") {
                        Text("Prendé el Bluetooth y volvé a abrir la app.")
                    }
                }
                else -> {
                    when (tab) {
                        Tab.MAP -> MapScreen(vm, onMessage = { openChat(Conv.Direct(it).key) }, topInset = topPad, bottomInset = bottomPad)
                        else -> Column(Modifier.fillMaxSize().padding(top = topPad)) {
                            if (!connected) LinkPanel(vm, link, Modifier.padding(12.dp))
                            when (tab) {
                                Tab.NODES -> NodesScreen(
                                    vm, onAskLocation = { launcher.launch(locationPermissions) },
                                    onNode = { openChat(Conv.Direct(it).key) },
                                    onShowOnMap = { vm.focusNode.value = it; chat = null; tabName = Tab.MAP.name },
                                )
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
                    banner?.let { m ->
                        MessageBanner(
                            vm, m, Modifier.align(Alignment.TopCenter).padding(top = topPad).padding(horizontal = 12.dp, vertical = 6.dp),
                            onOpen = { openChat(m.conv); banner = null },
                            onClose = { banner = null },
                        )
                    }
                }
            }
            TopStatus(vm, Modifier.align(Alignment.TopCenter))
            if (apply is ApplyState.Working) WorkingOverlay((apply as ApplyState.Working).step, onCancel = vm::cancelApply)
        }
        if (showNav) {
            NavBar(
                tab, unread, onSelect = { tabName = it.name; if (it != Tab.CHATS) chat = null },
                Modifier.align(Alignment.BottomCenter).onSizeChanged { navH = with(density) { it.height.toDp() } },
            )
        }
    }
    }
    if (link is LinkState.NeedPin) PinDialog(vm)

    // Actualizaciones: al abrir la app (una vez por arranque), si está activado y hay internet.
    LaunchedEffect(Unit) { Updater.checkOnLaunch(context) }
    io.github.protohommer.meshpaper.ui.UpdateDialog()
}

// ---------- Barra de estado superior ----------

/** Alto de la barra de arriba, sin contar la barra del sistema. */
private val TopBarHeight = 58.dp

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
    // Con el propio: "3" si son tres nodos en el grupo contando este.
    val members = nodes.values.count { it.num != me && isGroupMember(it, me, hasGroup, since) } +
        if (hasGroup && myNode != null) 1 else 0
    val connected = link == LinkState.Connected
    val (linkColor, state) = when (link) {
        LinkState.Connected -> io.github.protohommer.meshpaper.ui.theme.SigMe to "enlazado"
        is LinkState.Connecting, LinkState.NeedPin -> SigFair to "conectando"
        is LinkState.Failed -> SigWeak to "sin enlace"
        LinkState.Disconnected -> HudTextDim to "sin enlace"
    }
    val nodeName = if (connected) myNode?.shortName?.ifEmpty { null } ?: myNode?.id ?: "—" else "—"
    // Tocar el nombre del nodo: cambiar de nodo o desconectar.
    var showLink by remember { mutableStateOf(false) }
    if (showLink && connected) {
        AlertDialog(
            onDismissRequest = { showLink = false },
            title = { Text("NODO ${nodeName.uppercase()}") },
            text = {
                Column {
                    myNode?.longName?.takeIf { it.isNotEmpty() }?.let { Text(it, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)) }
                    io.github.protohommer.meshpaper.ui.LinkActions(vm, onDone = { showLink = false })
                }
            },
            confirmButton = { TextButton(onClick = { showLink = false }) { Text("Cerrar") } },
        )
    }
    val haze = LocalHaze.current
    @Suppress("UNUSED_VARIABLE") val unusedHaze = haze
    // 2.0: una franja propia (superficie y línea abajo), para que no se pierda con el resto.
    Column(modifier.fillMaxWidth().background(HudSurface)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .height(TopBarHeight).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // El nodo y el estado del enlace (el punto cian: estás enlazado, sos vos).
            Column(
                Modifier.weight(1f).clickable(enabled = connected, onClickLabel = "Cambiar de nodo o desconectar") { showLink = true },
            ) {
                Text(
                    nodeName.uppercase() + if (connected) " ▾" else "",
                    style = io.github.protohommer.meshpaper.ui.theme.MonoValue.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    // Tu nodo: en cian, como todo lo tuyo.
                    color = if (connected) io.github.protohommer.meshpaper.ui.theme.SigMe else HudTextDim, maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(linkColor, 6.dp)
                    Spacer(Modifier.width(6.dp))
                    HudLabel(state, color = linkColor)
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
                    hasGroup -> MaterialTheme.colorScheme.onSurface
                    else -> SigWeak
                },
                Modifier.widthIn(max = 104.dp), sans = true,
            )
            Spacer(Modifier.width(16.dp))
            TopReadout("Miemb.", "$members", MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(16.dp))
            // GPS con semáforo: verde hasta 10 m, amarillo hasta 30, rojo más (o sin fix).
            val acc = fix?.accuracy
            TopReadout(
                "GPS", acc?.let { "±${it.toInt()} m" } ?: "—",
                when {
                    acc == null -> HudTextDim
                    acc <= 10f -> io.github.protohommer.meshpaper.ui.theme.StatusOk
                    acc <= 30f -> io.github.protohommer.meshpaper.ui.theme.StatusBusy
                    else -> io.github.protohommer.meshpaper.ui.theme.StatusBad
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudLine))
    }
}

@Composable
private fun TopReadout(label: String, value: String, color: Color, modifier: Modifier = Modifier, sans: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        HudLabel(label)
        if (sans) Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        else HudValue(value, color = color)
    }
}

@Composable
private fun TopDivider() {
    Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(22.dp).background(HudLine))
}

// ---------- Navegación inferior ----------

@Composable
private fun NavBar(tab: Tab, unread: Int, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth()
            .background(HudBg)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudLine))
        Row(Modifier.fillMaxWidth().height(62.dp)) {
            for (t in Tab.entries) {
                val active = t == tab
                val color = if (active) MaterialTheme.colorScheme.onSurface else HudTextDim
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(onClickLabel = t.label) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        TabIcon(t, color)
                        if (t == Tab.CHATS && unread > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp)
                                    .background(SigWeak, androidx.compose.foundation.shape.CircleShape)
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) { Text("$unread", style = MaterialTheme.typography.labelSmall, color = HudBg) }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.width(18.dp).height(2.dp).background(if (active) color else Color.Transparent))
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

/** Íconos de línea de la barra de abajo, dibujados (24 dp, trazo fino). */
@Composable
private fun TabIcon(t: Tab, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(1.7f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun p(vararg xy: Float) = androidx.compose.ui.graphics.Path().apply {
            moveTo(xy[0] * u, xy[1] * u)
            var k = 2
            while (k < xy.size) { lineTo(xy[k] * u, xy[k + 1] * u); k += 2 }
        }
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
        when (t) {
            Tab.MAP -> {
                drawPath(p(3f, 6f, 9f, 4f, 15f, 6f, 21f, 4f, 21f, 18f, 15f, 20f, 9f, 18f, 3f, 20f).apply { close() }, color, style = st)
                drawLine(color, o(9f, 4f), o(9f, 18f), st.width)
                drawLine(color, o(15f, 6f), o(15f, 20f), st.width)
            }
            Tab.NODES -> {
                drawCircle(color, 2.5f * u, o(6f, 6f), style = st)
                drawCircle(color, 2.5f * u, o(18f, 6f), style = st)
                drawCircle(color, 2.5f * u, o(12f, 18f), style = st)
                drawLine(color, o(8f, 7.5f), o(11f, 16f), st.width)
                drawLine(color, o(16f, 7.5f), o(13f, 16f), st.width)
                drawLine(color, o(8.5f, 6f), o(15.5f, 6f), st.width)
            }
            Tab.CHATS -> drawPath(p(4f, 5f, 20f, 5f, 20f, 16f, 10f, 16f, 5f, 20f, 5f, 16f, 4f, 16f).apply { close() }, color, style = st)
            Tab.GROUP -> {
                drawCircle(color, 3.2f * u, o(9f, 9f), style = st)
                drawCircle(color, 2.5f * u, o(17f, 10f), style = st)
                drawArc(color, 180f, 180f, false, o(3f, 13.5f), androidx.compose.ui.geometry.Size(12f * u, 11f * u), style = st)
                drawArc(color, 200f, 140f, false, o(14f, 14f), androidx.compose.ui.geometry.Size(8f * u, 9f * u), style = st)
            }
            Tab.SETTINGS -> {
                drawCircle(color, 3f * u, o(12f, 12f), style = st)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val c = kotlin.math.cos(a).toFloat()
                    val s = kotlin.math.sin(a).toFloat()
                    drawLine(color, o(12f + 6f * c, 12f + 6f * s), o(12f + 9f * c, 12f + 9f * s), st.width, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
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
                TextButton(onClick = vm::disconnect) { Text("Cancelar") }
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
                                if (d.bonded) HudButton(onClick = { vm.connect(d) }) { Text("Conectar") }
                                else HudOutlinedButton(onClick = { vm.pairAndConnect(d) }) { Text("Emparejar") }
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
        title = { Text("PIN del nodo") },
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
        confirmButton = { TextButton(onClick = { vm.submitPin(pin) }, enabled = pin.length == 6) { Text("Emparejar") } },
        dismissButton = { TextButton(onClick = vm::disconnect) { Text("Cancelar") } },
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
private fun BoxScope.WorkingOverlay(step: String, onCancel: () -> Unit) {
    // CANCELAR aparece a los pocos segundos: escribir la config es rápido, lo que se puede
    // estirar es la reconexión (nodo que no vuelve, Bluetooth apagado, etc.).
    var canCancel by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(6_000); canCancel = true }
    Box(Modifier.matchParentSize().background(HudBg.copy(alpha = 0.85f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        HudPanel(Modifier.padding(32.dp).hudCorners(), title = "Trabajando") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(step)
            }
            if (canCancel) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancelar") }
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
private fun NodesScreen(vm: MeshViewModel, onAskLocation: () -> Unit, onNode: (Int) -> Unit, onShowOnMap: (Int) -> Unit) {
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            PositionPanel(vm, onAskLocation)
            Spacer(Modifier.height(12.dp))
        }
        item { SectionTitle("Grupo · ${members.size}", HudTextDim) }
        if (members.isEmpty()) item {
            HudLabel(if (hasGroup) "Todavía no se oyó a nadie del grupo" else "Sin grupo privado: armalo en GRUPO")
            Spacer(Modifier.height(8.dp))
        }
        items(members, key = { it.num }) { n -> NodeRow(n, me, true, fix, now, onNode, onShowOnMap) }
        if (others.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)); SectionTitle("Repetidores y otros · ${others.size}", HudTextDim) }
            items(others, key = { it.num }) { n -> NodeRow(n, me, false, fix, now, onNode, onShowOnMap) }
        }
        item {
            Spacer(Modifier.height(16.dp))
            if (link == LinkState.Connected) {
                HudOutlinedButton(onClick = vm::disconnect, Modifier.fillMaxWidth()) { Text("Desconectar del nodo") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HudLabel(text, color = color)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    n: MeshNode, me: Int?, member: Boolean, fix: Location?, now: Long,
    onClick: (Int) -> Unit, onShowOnMap: (Int) -> Unit,
) {
    val tone = Signal.tone(n, me, member, now)
    val lost = n.num != me && Signal.isLost(n, now)
    // Toque: mensaje directo. Toque largo: menú (ver en el mapa, mensaje).
    var menu by remember { mutableStateOf(false) }
    val onMap = (n.lat != null && n.lon != null) || (n.num == me && fix != null)
    Box {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = { if (n.num != me) onClick(n.num) else menu = true },
                onLongClick = { menu = true },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // La forma dice qué es: círculo del grupo, rombo fijo, rombo gris ajeno, anillo cortado perdido, retícula vos.
        io.github.protohommer.meshpaper.ui.NodeGlyph(
            when {
                n.num == me -> io.github.protohommer.meshpaper.ui.Glyph.ME
                lost -> io.github.protohommer.meshpaper.ui.Glyph.LOST
                !member -> io.github.protohommer.meshpaper.ui.Glyph.FOREIGN
                Signal.isFixedRole(n) -> io.github.protohommer.meshpaper.ui.Glyph.FIXED
                else -> io.github.protohommer.meshpaper.ui.Glyph.MEMBER
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val nameColor = when {
                    lost -> SigWeak
                    !member && !n.mute -> io.github.protohommer.meshpaper.ui.theme.SigRelay
                    !member -> HudTextDim
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    n.shortName.ifEmpty { n.id.takeLast(4) }.uppercase(),
                    style = io.github.protohommer.meshpaper.ui.theme.MonoValue.copy(fontSize = 16.sp), color = nameColor, maxLines = 1,
                )
                if (member && n.num != me && !lost) {
                    Spacer(Modifier.width(8.dp))
                    io.github.protohommer.meshpaper.ui.SignalBars(io.github.protohommer.meshpaper.ui.signalBars(Signal.quality(n, now)), false)
                }
                if (n.num == me) { Spacer(Modifier.width(8.dp)); HudLabel("este nodo", color = io.github.protohommer.meshpaper.ui.theme.SigMe) }
                Spacer(Modifier.width(8.dp))
                Text(
                    n.longName.ifEmpty { n.id }, style = MaterialTheme.typography.bodyMedium, color = HudTextDim,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
            val parts = buildList {
                if (lost) add("perdido")
                if (!member) add(if (n.mute) "no repite" else "repite")
                if (member && Signal.isFixedRole(n)) add("fijo")
                Signal.describe(n)?.let { add(it) }
                if (n.num != me) add(agoText(now, n.lastHeard))
            }
            Text(
                parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp, fontSize = 12.sp),
                color = if (lost) SigWeak else HudTextDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val dist = if (fix != null && n.num != me && n.lat != null && n.lon != null) {
                val (d, b) = PhoneLocation.distanceAndBearing(fix.latitude, fix.longitude, n.lat, n.lon)
                "${PhoneLocation.formatDistance(d)} ${PhoneLocation.cardinal(b)}"
            } else null
            Text(
                dist ?: "—", style = io.github.protohommer.meshpaper.ui.theme.MonoValue.copy(fontWeight = FontWeight.Normal),
                color = if (lost) SigWeak else MaterialTheme.colorScheme.onSurface,
            )
            n.battery?.let {
                Text(
                    if (it > 100) "USB" else "bat $it %", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp, fontSize = 12.sp),
                    color = HudTextDim,
                )
            }
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(if (onMap) "Ver en el mapa" else "Ver en el mapa (no mandó su posición)") },
            enabled = onMap,
            onClick = { menu = false; onShowOnMap(n.num) },
        )
        if (n.num != me) androidx.compose.material3.DropdownMenuItem(
            text = { Text("Mensaje directo") },
            onClick = { menu = false; onClick(n.num) },
        )
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
            if (share && !permitted) TextButton(onClick = onAskLocation) { Text("Permitir") }
            else Switch(checked = share, onCheckedChange = vm::setSharePosition)
        }
    }
}


// ---------- Cartel de mensaje nuevo ----------

/** Aviso arriba cuando llega un mensaje y no estás en ese chat: quién, de dónde y qué dice. */
@Composable
private fun MessageBanner(
    vm: MeshViewModel, m: io.github.protohommer.meshpaper.mesh.ChatMsg, modifier: Modifier,
    onOpen: () -> Unit, onClose: () -> Unit,
) {
    val nodes by vm.nodes.collectAsState()
    val sender = nodes[m.from]?.let { it.longName.ifEmpty { it.shortName } } ?: "!%08x".format(m.from)
    val conv = io.github.protohommer.meshpaper.mesh.Conv.parse(m.conv)
    val accent = if (conv is io.github.protohommer.meshpaper.mesh.Conv.Direct) SigFair else HudCyan
    val where = if (conv is io.github.protohommer.meshpaper.mesh.Conv.Direct) "Mensaje directo"
    else io.github.protohommer.meshpaper.ui.convTitle(vm, m.conv)
    HudPanel(modifier.fillMaxWidth().clickable(onClick = onOpen), title = where, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            TextButton(onClick = onClose) { Text("✕") }
        }
    }
}
