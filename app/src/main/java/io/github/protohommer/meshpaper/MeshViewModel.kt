package io.github.protohommer.meshpaper

import com.google.protobuf.ByteString

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.protohommer.meshpaper.ble.LinkState
import io.github.protohommer.meshpaper.ble.MeshBle
import io.github.protohommer.meshpaper.ble.MeshUuids
import io.github.protohommer.meshpaper.mesh.Antenna
import io.github.protohommer.meshpaper.mesh.BROADCAST
import io.github.protohommer.meshpaper.mesh.ChatMsg
import io.github.protohommer.meshpaper.mesh.Conv
import io.github.protohommer.meshpaper.mesh.Group
import io.github.protohommer.meshpaper.mesh.Regions
import io.github.protohommer.meshpaper.mesh.hopsOf
import io.github.protohommer.meshpaper.mesh.MAX_TEXT_BYTES
import io.github.protohommer.meshpaper.mesh.MessageStore
import io.github.protohommer.meshpaper.mesh.MsgStatus
import io.github.protohommer.meshpaper.mesh.PhoneLocation
import io.github.protohommer.meshpaper.mesh.TrackPoint
import io.github.protohommer.meshpaper.mesh.TrackStore
import io.github.protohommer.meshpaper.mesh.WaypointStore
import io.github.protohommer.meshpaper.mesh.Wpt
import android.location.Location
import io.github.protohommer.meshpaper.mesh.routingErrorText
import io.github.protohommer.meshpaper.mesh.Calandria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.AppOnlyProtos.ChannelSet
import org.meshtastic.proto.ChannelProtos.Channel
import org.meshtastic.proto.ChannelProtos.ChannelSettings
import org.meshtastic.proto.ConfigProtos.Config
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.PositionConfig
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.MeshProtos.MeshPacket
import org.meshtastic.proto.MeshProtos.NodeInfo
import org.meshtastic.proto.MeshProtos.Routing
import org.meshtastic.proto.MeshProtos.ToRadio
import org.meshtastic.proto.MeshProtos.Waypoint
import org.meshtastic.proto.Portnums.PortNum

data class FoundDevice(val device: BluetoothDevice, val name: String, val rssi: Int, val bonded: Boolean)

/** rssi de un emparejado que no vimos anunciarse. */
const val NO_RSSI = Int.MIN_VALUE
private const val RECONNECT_TOTAL_MS = 120_000L

/** Roles con los que el firmware apaga el Bluetooth en un ESP32 apenas no hay teléfono conectado. */
val BLUETOOTH_OFF_ROLES = setOf(DeviceConfig.Role.ROUTER, DeviceConfig.Role.ROUTER_LATE)
/** Lo que entra en set_canned_message_module_messages (char[201] en el firmware). */
const val CANNED_MAX_BYTES = 200
private const val SCAN_MS = 30_000L
/** Nombre BLE por defecto del firmware: "<nombre corto o Meshtastic>_<4 hex del nodo>". */
private val MESH_NAME = Regex(".*_[0-9a-fA-F]{4}")

/** Días sin oír un nodo para considerarlo fantasma (Ajustes → Mantenimiento → Limpiar fantasmas). */
const val GHOST_STALE_DAYS = 7

data class MeshNode(
    val num: Int,
    val id: String,
    val longName: String,
    val shortName: String,
    val lastHeard: Long,      // segundos epoch, 0 = desconocido
    val snr: Float,
    val hopsAway: Int?,
    val battery: Int?,
    val lat: Double?,
    val lon: Double?,
    val channel: Int?,        // índice local del canal en que lo oímos (0 = primario); null = sin prueba
    val rssi: Int? = null,    // del último paquete que llegó directo (0 saltos)
    val mute: Boolean = false, // rol CLIENT_MUTE: no repite, no aporta cobertura
    val group: Boolean = false, // alguna vez lo oímos (con prueba) por el canal privado: tiene la clave
    val role: DeviceConfig.Role? = null, // del NodeInfo; null = todavía no sabemos
    val hw: MeshProtos.HardwareModel? = null, // modelo de placa, del NodeInfo
    val alt: Int? = null,     // altitud sobre el nivel del mar (m), si la manda
    val precisionBits: Int? = null, // bits de precisión de la posición (32 = exacta)
    val favorite: Boolean = false, // marcado como favorito en el nodo (NodeInfo.is_favorite)
    val ignored: Boolean = false,  // ignorado en el nodo (NodeInfo.is_ignored)
) {
    /**
     * Error de la posición si viene redondeada a propósito (canal con precisión reducida):
     * el firmware deja los [precisionBits] bits altos de latitude_i (1e-7°) y centra el resto.
     */
    val positionErrorM: Double?
        get() = precisionBits?.takeIf { it in 1..31 }?.let { (1L shl (32 - it)) * 1e-7 * 111_320 / 2 }
}

/**
 * Altitud sobre el nivel del mar de una posición. El firmware difunde `altitude` (MSL) por
 * defecto y manda 0 cuando no la tiene; si viene la elipsoidal (HAE) con la separación del
 * geoide, MSL = HAE − separación. La HAE sola no sirve: según la zona difiere del nivel del mar
 * hasta en decenas de metros.
 */
fun mslOf(p: MeshProtos.Position): Int? = when {
    p.hasAltitude() && p.altitude != 0 -> p.altitude
    p.hasAltitudeHae() && p.hasAltitudeGeoidalSeparation() && p.altitudeHae != 0 ->
        p.altitudeHae - p.altitudeGeoidalSeparation
    else -> null
}

/**
 * ¿Es del grupo? Alguna vez lo oímos por el canal privado (índice 0) después de armar el grupo.
 * El resto de los nodos solo sirven de repetidores. El canal tiene que estar probado
 * (ver [channelProof] y [MeshViewModel.upsert]): un 0 sin prueba no cuenta. Es "pegajoso":
 * un miembro que tiene el público como primario manda su NodeInfo y telemetría por el público,
 * pero si alguna vez habló por el privado, tiene la clave.
 */
fun isGroupMember(n: MeshNode, me: Int?, hasGroup: Boolean, since: Long): Boolean =
    n.num == me || (hasGroup && n.group && n.lastHeard > 0 && n.lastHeard >= since)

/**
 * Canal que un paquete prueba para su emisor, o null si no prueba nada. Dos casos traen
 * channel = 0 sin ser del canal privado:
 * - DM cifrado con PKI: viaja con hash 0 y queda en 0 al descifrarlo (nodo ajeno que nos escribe).
 * - Replay de la NodeDB al conectar (posición/telemetría, sin rx_rssi): repite el canal guardado,
 *   que el firmware solo asigna al recibir un NodeInfo; sin NodeInfo queda en 0 por defecto.
 * Todo paquete recibido de verdad por radio trae rx_rssi... salvo con señal muy fuerte: el
 * chip mide 0 dBm (nodos a pocos metros con mucha potencia) y un firmware 2.7 manda el 0 como
 * "sin dato". Por eso los tipos que el firmware nunca repite desde la NodeDB (solo repite
 * posición, telemetría y estado: ver PhoneAPI::makeReplay*) cuentan aunque no traigan rx_rssi.
 */
private fun channelProof(p: MeshPacket): Int? =
    if (p.hasDecoded() && !p.pkiEncrypted && (p.hasRxRssi() || p.decoded.portnum in LIVE_ONLY_PORTS)) p.channel else null

/** Paquetes que solo pueden haber llegado por radio en el momento (nunca son un replay). */
private val LIVE_ONLY_PORTS = setOf(
    PortNum.TEXT_MESSAGE_APP, PortNum.WAYPOINT_APP, PortNum.NODEINFO_APP, PortNum.ROUTING_APP,
)

/** Ajustes del nodo conectado que cambiaron. null / vacío = no tocar. */
data class NodeEdits(
    val owner: MeshProtos.User? = null,
    val device: DeviceConfig? = null,
    val position: PositionConfig? = null,
    val fixed: MeshProtos.Position? = null,   // poner (o mover) la posición fija
    val removeFixed: Boolean = false,         // volver a la posición del celular
    val lora: LoRaConfig? = null,
    val channels: List<Channel> = emptyList(),
) {
    val isEmpty: Boolean
        get() = owner == null && device == null && position == null && fixed == null && !removeFixed &&
            lora == null && channels.isEmpty()
}

sealed interface ApplyState {
    data object Idle : ApplyState
    data class Working(val step: String) : ApplyState
    data class Done(val text: String) : ApplyState
    data class Failed(val reason: String) : ApplyState
}

@SuppressLint("MissingPermission")
class MeshViewModel(app: Application) : AndroidViewModel(app) {
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = app.getSharedPreferences("mesh", Context.MODE_PRIVATE)

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Por qué la búsqueda no puede andar (ubicación apagada, Bluetooth apagado, error). null = bien. */
    private val _scanHint = MutableStateFlow<String?>(null)
    val scanHint: StateFlow<String?> = _scanHint

    private val _myNodeNum = MutableStateFlow<Int?>(null)
    val myNodeNum: StateFlow<Int?> = _myNodeNum

    private val _nodes = MutableStateFlow<Map<Int, MeshNode>>(emptyMap())
    val nodes: StateFlow<Map<Int, MeshNode>> = _nodes

    /** Nodos que alguna vez oímos por el canal privado. Se guarda por nodo propio y se borra al cambiar el grupo. */
    @Volatile private var groupNums: Set<Int> = emptySet()

    /** Canales del nodo, por índice (solo los activos). */
    private val _channels = MutableStateFlow<Map<Int, Channel>>(emptyMap())
    val channels: StateFlow<Map<Int, Channel>> = _channels

    private val _lora = MutableStateFlow<LoRaConfig?>(null)
    val lora: StateFlow<LoRaConfig?> = _lora

    private val _apply = MutableStateFlow<ApplyState>(ApplyState.Idle)
    val apply: StateFlow<ApplyState> = _apply

    /** Nodos Calandria que se anunciaron (modelo y firmware), por número. Se guarda entre sesiones. */
    private val _calandria = MutableStateFlow(loadCalandria())
    val calandria: StateFlow<Map<Int, Calandria.Info>> = _calandria
    /** A quién ya le preguntamos "¿qué sos?" en esta conexión (para no repetir). */
    private val askedIdentity = mutableSetOf<Int>()

    /** Mensajes rápidos del nodo conectado; null = todavía no los leímos. */
    private val _canned = MutableStateFlow<List<String>?>(null)
    val canned: StateFlow<List<String>?> = _canned

    /** Desde cuándo (epoch s) este nodo está en el grupo actual. Para no contar nodos viejos. */
    private val _groupSince = MutableStateFlow(0L)
    val groupSince: StateFlow<Long> = _groupSince

    private val ble = MeshBle(app, viewModelScope, ::onFromRadio).apply {
        knownPin = prefs.getString("pin", MeshBle.DEFAULT_PIN) ?: MeshBle.DEFAULT_PIN
        onPinAccepted = { prefs.edit().putString("pin", it).apply() }
    }
    /** En la variante demo (capturas del README) la app se muestra conectada, sin nodo de verdad. */
    val link: StateFlow<LinkState> = if (BuildConfig.DEMO) MutableStateFlow<LinkState>(LinkState.Connected) else ble.state

    private val store = MessageStore(app.filesDir)

    /** Mensajes del nodo local conectado (todas las conversaciones), en orden de llegada. */
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages

    /** Última vez (epoch s) que se leyó cada conversación. */
    private val _lastRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastRead: StateFlow<Map<String, Long>> = _lastRead

    private val ackTimeouts = mutableMapOf<Int, Job>()

    // ---------- Mapa: rastros y waypoints ----------

    private val trackStore = TrackStore(app.filesDir)
    private val waypointStore = WaypointStore(app.filesDir)

    /** Rastro de cada nodo (incluido el propio, con el GPS del teléfono). */
    private val _tracks = MutableStateFlow<Map<Int, List<TrackPoint>>>(emptyMap())
    val tracks: StateFlow<Map<Int, List<TrackPoint>>> = _tracks

    /** Waypoints conocidos por id (incluye vencidos; la UI filtra). */
    private val _waypoints = MutableStateFlow<Map<Int, Wpt>>(emptyMap())
    val waypoints: StateFlow<Map<Int, Wpt>> = _waypoints

    // ---------- Posición del teléfono ----------

    private val _position = MutableStateFlow<PositionConfig?>(null)
    val positionConfig: StateFlow<PositionConfig?> = _position

    // ---------- Ajustes del nodo conectado ----------

    private val _device = MutableStateFlow<DeviceConfig?>(null)
    val deviceConfig: StateFlow<DeviceConfig?> = _device

    /** El User del nodo conectado, tal como lo manda en su NodeInfo. */
    private val _owner = MutableStateFlow<MeshProtos.User?>(null)
    val owner: StateFlow<MeshProtos.User?> = _owner

    private val _metadata = MutableStateFlow<MeshProtos.DeviceMetadata?>(null)
    val metadata: StateFlow<MeshProtos.DeviceMetadata?> = _metadata

    // ---------- Antenas (cargadas a mano, por nodo, para estimar alcance) ----------

    private val antennaPrefs = app.getSharedPreferences("antennas", Context.MODE_PRIVATE)
    private val _antennas = MutableStateFlow(
        antennaPrefs.all.mapNotNull { (k, v) ->
            val num = k.toLongOrNull(16)?.toInt() ?: return@mapNotNull null
            (v as? String)?.let(Antenna::decode)?.let { num to it }
        }.toMap()
    )
    val antennas: StateFlow<Map<Int, Antenna>> = _antennas

    fun setAntenna(num: Int, a: Antenna?) {
        val key = "%08x".format(num)
        antennaPrefs.edit().apply { if (a == null) remove(key) else putString(key, a.encode()) }.apply()
        _antennas.update { if (a == null) it - num else it + (num to a) }
    }

    /** Compartir la posición del celular con el grupo (a través del nodo). */
    private val _sharePosition = MutableStateFlow(prefs.getBoolean("sharePosition", true))
    val sharePosition: StateFlow<Boolean> = _sharePosition

    /** Último fix del teléfono (aunque no se haya mandado). */
    private val _myFix = MutableStateFlow<Location?>(null)
    val myFix: StateFlow<Location?> = _myFix

    /** Cuándo (epoch ms) se mandó la última posición al nodo. 0 = nunca en esta sesión. */
    private val _lastPosSent = MutableStateFlow(0L)
    val lastPosSent: StateFlow<Long> = _lastPosSent
    private var lastSentFix: Location? = null

    private val phoneLocation = PhoneLocation(app) { loc ->
        if (BuildConfig.DEMO) return@PhoneLocation // la posición es la inventada
        _myFix.value = loc
        if (!loc.hasAccuracy() || loc.accuracy <= MAX_FIX_ACCURACY_M) {
            _myNodeNum.value?.let { recordTrack(it, loc.latitude, loc.longitude, loc.time / 1000) }
        }
        maybeSendPosition(loc)
    }

    /** La UI avisa cuándo hay permiso de ubicación; arranca o frena el GPS según la conexión. */
    var locationPermitted = false
        set(v) { field = v; updateLocationUpdates() }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------- Sesión en segundo plano y avisos ----------

    /**
     * true desde que se conecta a un nodo hasta que se toca Desconectar: mientras tanto corre el
     * [MeshService] (aviso fijo) y, si se pierde el enlace, se reintenta solo.
     */
    private val _keepAlive = MutableStateFlow(false)
    val keepAlive: StateFlow<Boolean> = _keepAlive
    private var reconnectJob: Job? = null

    /** La app está en pantalla (lo marca MainActivity). Si no, los mensajes van como notificación. */
    @Volatile var uiVisible = false
    /** Conversación abierta en pantalla: de esa no se avisa. */
    @Volatile var viewingConv: String? = null

    /** Mensajes ajenos recién llegados, para el cartel dentro de la app. */
    private val _incoming = MutableSharedFlow<ChatMsg>(extraBufferCapacity = 8)
    val incoming: SharedFlow<ChatMsg> = _incoming

    /** Conversación a abrir pedida desde una notificación. */
    val openRequest = MutableStateFlow<String?>(null)

    /** Nodo a mostrar en el mapa, pedido desde la lista de nodos. */
    val focusNode = MutableStateFlow<Int?>(null)

    /** Mensajes sin leer por conversación desde la última notificación (para apilarlos). */
    private val notified = mutableMapOf<String, MutableList<Pair<String, String>>>()

    /** Carga los datos inventados de [Demo] (solo en la variante demo). */
    private fun loadDemo() {
        val d = Demo.build(System.currentTimeMillis() / 1000)
        _groupSince.value = 1L
        groupNums = d.group
        _myNodeNum.value = Demo.ME
        _channels.value = d.channels
        _lora.value = d.lora
        _owner.value = d.owner
        _device.value = d.device
        _position.value = trackingPosition(d.position) // ya ajustado para rastreo
        _nodes.value = d.nodes
        _messages.value = d.messages
        _tracks.value = d.tracks
        _waypoints.value = d.waypoints
        _antennas.value = d.antennas
        _myFix.value = d.fix
    }

    init {
        if (BuildConfig.DEMO) loadDemo()
        viewModelScope.launch {
            var prev: LinkState = LinkState.Disconnected
            link.collect {
                updateLocationUpdates()
                if (it == LinkState.Connected) {
                    lastDevice?.let { d -> prefs.edit().putString("lastDevice", d.address).apply() }
                    reconnectJob?.cancel(); reconnectJob = null
                    askedIdentity.clear()
                    _canned.value = null
                    launch {
                        delay(3_000) // que termine de llegar la config
                        _myNodeNum.value?.let { me -> askIdentity(me) }
                        syncTimezone()
                        if (Updater.autoCheck(getApplication())) Calandria.checkLatest()
                        // Al Calandria conectado, cuál es su último firmware (él no tiene internet).
                        delay(5_000) // la identidad del nodo llega en ~1 s
                        tellLatestFirmware()
                    }
                }
                // Se cortó estando conectados (nodo fuera de alcance, se apagó…): reintentar solo.
                if (prev == LinkState.Connected && it is LinkState.Failed && _keepAlive.value && applyJob?.isActive != true) startReconnect()
                prev = it
            }
        }
    }

    /** Reintenta conectar al mismo nodo, cada vez más espaciado, hasta lograrlo o que se corte la sesión. */
    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        val d = lastDevice ?: return
        reconnectJob = viewModelScope.launch {
            var wait = 5_000L
            while (_keepAlive.value && link.value != LinkState.Connected) {
                delay(wait)
                if (!_keepAlive.value || applyJob?.isActive == true) break
                val st = link.value
                if (st is LinkState.Failed || st == LinkState.Disconnected) connectDevice(d)
                // Esperar a que ese intento termine (conecta o falla) antes del siguiente.
                link.first { it == LinkState.Connected || it is LinkState.Failed || it == LinkState.Disconnected }
                wait = (wait * 2).coerceAtMost(120_000L)
            }
        }
    }

    private fun notifyIncoming(m: ChatMsg) {
        val ctx = getApplication<Application>()
        if (uiVisible) {
            if (viewingConv != m.conv) _incoming.tryEmit(m)
            return
        }
        val sender = _nodes.value[m.from]?.let { it.longName.ifEmpty { it.shortName } } ?: "!%08x".format(m.from)
        val c = Conv.parse(m.conv)
        val channel = when {
            c is Conv.Direct -> Notifier.CH_DM
            c is Conv.Channel && c.index == 0 && hasPrivateGroup() -> Notifier.CH_GROUP
            else -> Notifier.CH_PUBLIC
        }
        val title = if (c is Conv.Direct) "Mensaje de $sender"
        else io.github.protohommer.meshpaper.ui.convTitle(this, m.conv)
        val list = notified.getOrPut(m.conv) { mutableListOf() }
        list += sender to m.text
        Notifier.message(ctx, m.conv, channel, title, list)
    }

    /** Nombre del último nodo al que nos conectamos (para el botón "Reconectar"). */
    fun lastDeviceName(): String? = lastBonded()?.let { runCatching { it.name }.getOrNull() ?: it.address }

    /**
     * El último nodo al que nos conectamos. Si no hay uno guardado, un emparejado que tenga en
     * caché el servicio de Meshtastic.
     */
    private fun lastBonded(): BluetoothDevice? = runCatching {
        val bonded = adapter?.bondedDevices.orEmpty()
        val addr = prefs.getString("lastDevice", null)
        if (addr != null) bonded.firstOrNull { it.address == addr }
        else bonded.firstOrNull { d -> looksMeshtastic(d, null, runCatching { d.name }.getOrNull()) }
    }.getOrNull()

    private var autoTried = false

    /** Al abrir la app: reconecta solo al último nodo emparejado (una vez). */
    fun autoConnect() {
        if (autoTried) return
        autoTried = true
        if (link.value == LinkState.Disconnected) reconnectLast()
    }

    /** Reconecta al último nodo, si sigue emparejado. Devuelve false si no hay a cuál. */
    fun reconnectLast(): Boolean {
        val d = lastBonded() ?: return false
        stopScan()
        connectDevice(d)
        return true
    }

    fun setSharePosition(on: Boolean) {
        prefs.edit().putBoolean("sharePosition", on).apply()
        _sharePosition.value = on
        updateLocationUpdates()
    }

    /** Navegación guiada en pantalla: GPS cada segundo, aunque no haya nodo ni se comparta la posición. */
    private var guidedLocation = false

    fun setGuidedLocation(on: Boolean) {
        if (on == guidedLocation) return
        guidedLocation = on
        phoneLocation.setFast(on)
        updateLocationUpdates()
    }

    private fun updateLocationUpdates() {
        val forNode = _sharePosition.value && link.value == LinkState.Connected
        if (locationPermitted && (forNode || guidedLocation)) phoneLocation.start()
        else phoneLocation.stop()
    }

    /**
     * Le pasa al nodo la posición del teléfono. El nodo la difunde por el primer canal con
     * position_precision > 0 (el privado del grupo), con su difusión "smart".
     * Mandamos si nos movimos >= 10 m (cada 30 s como mucho) o cada 2 min igual, para que
     * el nodo tenga hora y posición frescas. El firmware descarta más de una cada 10 s.
     */
    private fun maybeSendPosition(loc: Location) {
        val me = _myNodeNum.value ?: return
        if (link.value != LinkState.Connected || !_sharePosition.value) return
        if (loc.hasAccuracy() && loc.accuracy > MAX_FIX_ACCURACY_M) return
        val now = System.currentTimeMillis()
        val since = now - _lastPosSent.value
        val moved = lastSentFix?.distanceTo(loc) ?: Float.MAX_VALUE
        val due = since >= POS_HEARTBEAT_MS || (since >= POS_MIN_INTERVAL_MS && moved >= POS_MIN_MOVE_M)
        if (!due) return
        _lastPosSent.value = now
        lastSentFix = loc
        viewModelScope.launch {
            try {
                // Android da la altitud elipsoidal; desde Android 14 se puede pasar al nivel del mar.
                if (android.os.Build.VERSION.SDK_INT >= 34 && loc.hasAltitude()) withContext(Dispatchers.IO) {
                    runCatching { android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(getApplication(), loc) }
                }
                ble.send(ToRadio.newBuilder().setPacket(PhoneLocation.toPacket(loc, me, newPacketId())).build())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "no pude mandar la posición", e)
                _lastPosSent.value = 0L
            }
        }
    }

    /**
     * Config de posición del nodo para rastreo de campo: difusión "smart" (sale antes si se mueve
     * 25 m, como mucho una por minuto) y un latido cada 5 min. Nada de posición fija: así el nodo
     * acepta la del teléfono. Respeta el resto de lo que ya tenga (pines de GPS, flags).
     */
    private fun trackingPosition(current: PositionConfig?): PositionConfig =
        (current?.toBuilder() ?: PositionConfig.newBuilder())
            .setFixedPosition(false)
            .setPositionBroadcastSmartEnabled(true)
            .setBroadcastSmartMinimumDistance(25)
            .setBroadcastSmartMinimumIntervalSecs(60)
            .setPositionBroadcastSecs(300)
            .build()

    private var lastDevice: BluetoothDevice? = null
    private var lastLoaded: String? = null
    private var applyJob: Job? = null

    /**
     * ¿Es un nodo Meshtastic? Por el servicio anunciado o, si el anuncio no lo trae (pasa con
     * algunas placas y celulares), por el nombre por defecto del firmware: "<nombre>_abcd".
     */
    private fun looksMeshtastic(d: BluetoothDevice, uuids: List<ParcelUuid>?, name: String?): Boolean =
        uuids?.any { it.uuid == MeshUuids.SERVICE } == true ||
            runCatching { d.uuids?.any { it.uuid == MeshUuids.SERVICE } == true }.getOrDefault(false) ||
            (name != null && MESH_NAME.matches(name))

    private fun addFound(f: FoundDevice) = _found.update { list ->
        (list.filterNot { it.device.address == f.device.address } + f)
            .sortedWith(compareByDescending<FoundDevice> { it.rssi != NO_RSSI }.thenByDescending { it.rssi })
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val adName = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull()
            if (!looksMeshtastic(d, result.scanRecord?.serviceUuids, adName)) return
            addFound(FoundDevice(d, adName ?: d.address, result.rssi, d.bondState == BluetoothDevice.BOND_BONDED))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _scanHint.value = "No se pudo buscar (error $errorCode). Apagá y prendé el Bluetooth y probá de nuevo."
        }
    }

    private var scanTimeout: Job? = null

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _scanHint.value = "El Bluetooth del teléfono está apagado."
            return
        }
        val scanner = a.bluetoothLeScanner ?: return
        // Android 8–11 no devuelve ningún resultado si la ubicación del teléfono está apagada.
        val lm = getApplication<Application>().getSystemService(android.location.LocationManager::class.java)
        _scanHint.value = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S && lm != null &&
            !androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
        ) "Prendé la ubicación del teléfono: en este Android hace falta para buscar por Bluetooth." else null
        // Los nodos ya emparejados van primero aunque no estén anunciándose
        // (un nodo conectado a otra app deja de anunciarse).
        _found.value = emptyList()
        runCatching { a.bondedDevices }.getOrNull().orEmpty()
            .filter { looksMeshtastic(it, null, runCatching { it.name }.getOrNull()) }
            .forEach { addFound(FoundDevice(it, runCatching { it.name }.getOrNull() ?: it.address, NO_RSSI, true)) }
        // Sin filtro por hardware: en varios celulares el filtro por UUID de 128 bits no anda.
        // Filtramos en onScanResult.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            _scanHint.value = "No se pudo buscar: ${e.message}"
            return
        }
        _scanning.value = true
        scanTimeout?.cancel()
        scanTimeout = viewModelScope.launch { kotlinx.coroutines.delay(SCAN_MS); stopScan() }
    }

    fun stopScan() {
        scanTimeout?.cancel()
        if (!_scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _scanning.value = false
    }

    /** El emparejamiento (diálogo de PIN de Android) ocurre dentro de la conexión. */
    fun pairAndConnect(d: FoundDevice) = connect(d)

    fun connect(d: FoundDevice) {
        stopScan()
        connectDevice(d.device)
    }

    private fun connectDevice(device: BluetoothDevice) {
        _keepAlive.value = true
        lastDevice = device
        _nodes.value = emptyMap()
        _channels.value = emptyMap()
        _lora.value = null
        _position.value = null
        _device.value = null
        _owner.value = null
        _metadata.value = null
        _lastPosSent.value = 0L
        lastSentFix = null
        if (lastLoaded != device.address) {
            _myNodeNum.value = null
            _messages.value = emptyList()
            _tracks.value = emptyMap()
            _waypoints.value = emptyMap()
        }
        lastLoaded = device.address
        ble.connect(device)
    }

    fun submitPin(pin: String) = ble.submitPin(pin)

    /**
     * Para configurar otro nodo: suelta el actual (sin reconectar solo) y empieza a buscar.
     * El nodo que se suelta vuelve a anunciarse y también aparece en la lista.
     */
    fun switchNode() {
        disconnect()
        startScan()
    }

    /** El botón CANCELAR mientras se aplica o se reconecta: corta todo y suelta el Bluetooth. */
    fun cancelApply() {
        applyJob?.cancel(); applyJob = null
        _keepAlive.value = false
        reconnectJob?.cancel(); reconnectJob = null
        ble.disconnect()
        _apply.value = ApplyState.Failed("cancelado: conectá a mano cuando el nodo esté listo")
    }

    fun disconnect() {
        _keepAlive.value = false
        reconnectJob?.cancel(); reconnectJob = null
        applyJob?.cancel()
        _apply.value = ApplyState.Idle
        ble.disconnect()
    }

    // ---------- Grupo ----------

    /** El ChannelSet que tiene hoy el nodo (para mostrar el QR). */
    fun currentChannelSet(): ChannelSet? {
        val ch = _channels.value
        val primary = ch.values.firstOrNull { it.role == Channel.Role.PRIMARY } ?: return null
        val b = ChannelSet.newBuilder().addSettings(primary.settings)
        ch.values.filter { it.role == Channel.Role.SECONDARY }.sortedBy { it.index }
            .forEach { b.addSettings(it.settings) }
        _lora.value?.let { b.setLoraConfig(it) }
        return b.build()
    }

    fun hasPrivateGroup(): Boolean =
        _channels.value[0]?.let { it.role == Channel.Role.PRIMARY && Group.isPrivate(it.settings) } == true

    fun newGroup(name: String): ChannelSet = Group.create(name, _lora.value)

    /**
     * Le cambia el nombre al grupo (el canal principal), con la misma clave. El nombre entra en el
     * "hash" del canal que viaja en cada paquete: hasta que los demás escaneen el QR nuevo no se
     * leen entre ustedes. Si el slot de frecuencia no está fijo, sale del nombre: lo fijamos en el
     * actual para no cambiar de frecuencia.
     */
    fun renameGroup(name: String) = runEdit("Cambiando el nombre del grupo…", "Grupo renombrado: compartí el QR nuevo.") { me ->
        val ch0 = _channels.value[0] ?: error("el nodo no tiene canal principal")
        val lora = _lora.value
        if (lora != null && lora.channelNum == 0) {
            val slots = Regions.slotCount(lora.region, lora.modemPreset)
            if (slots != null) {
                val old = ch0.settings.name
                sendAdmin(me, AdminMessage.newBuilder().setSetConfig(
                    Config.newBuilder().setLora(lora.toBuilder().setChannelNum(Group.slotFor(old, slots)))
                ).build())
            }
        }
        val renamed = ch0.toBuilder().setSettings(ch0.settings.toBuilder().setName(name.take(Group.MAX_NAME)))
        sendAdmin(me, AdminMessage.newBuilder().setSetChannel(renamed).build())
    }

    fun dismissApply() {
        _apply.value = ApplyState.Idle
    }

    /**
     * Escribe el ChannelSet en el nodo dentro de una transacción de edición:
     * begin_edit → set_channel (0..7) → set_config(lora) → commit_edit.
     * Al hacer commit el nodo guarda, apaga el BLE y puede reiniciarse: reconectamos solos.
     */
    fun applyChannelSet(set: ChannelSet) = runEdit("Escribiendo canales en el nodo…", "Grupo aplicado al nodo.") { me ->
        for (i in 0 until MAX_CHANNELS) {
            val ch = Channel.newBuilder().setIndex(i)
            if (i < set.settingsCount) {
                ch.setSettings(set.getSettings(i))
                    .setRole(if (i == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY)
            } else {
                ch.setSettings(ChannelSettings.getDefaultInstance()).setRole(Channel.Role.DISABLED)
            }
            sendAdmin(me, AdminMessage.newBuilder().setSetChannel(ch).build())
        }
        if (set.hasLoraConfig()) {
            val lora = Group.mergeLora(set.loraConfig, _lora.value)
            sendAdmin(me, AdminMessage.newBuilder().setSetConfig(Config.newBuilder().setLora(lora)).build())
        }
        val pos = trackingPosition(_position.value)
        if (pos != _position.value) {
            sendAdmin(me, AdminMessage.newBuilder().setSetConfig(Config.newBuilder().setPosition(pos)).build())
        }
        val primary = set.settingsList.firstOrNull()
        resetGroup(me, primary?.takeIf { Group.isPrivate(it) }?.let { Group.fingerprint(it) })
    }

    /** Grupo nuevo (o distinto): nadie es miembro hasta que lo oigamos por el privado. */
    private fun resetGroup(me: Int, id: String?) {
        val now = System.currentTimeMillis() / 1000
        prefs.edit().putLong(sinceKey(me), now).putString(idKey(me), id).remove(groupKey(me)).apply()
        _groupSince.value = now
        groupNums = emptySet()
        _nodes.update { m -> m.mapValues { it.value.copy(group = false) } }
    }

    /** Huella del grupo que tiene ahora el nodo, o null si el primario no es privado. */
    private fun currentGroupId(): String? =
        _channels.value[0]?.takeIf { it.role == Channel.Role.PRIMARY && Group.isPrivate(it.settings) }
            ?.let { Group.fingerprint(it.settings) }

    /**
     * Si el grupo del nodo no es el que anotamos (se cambió por fuera de esta app, o es la
     * primera vez con esta versión), los miembros guardados no valen: se empieza de cero.
     */
    private fun syncGroup() {
        val me = _myNodeNum.value ?: return
        val id = currentGroupId() ?: return
        if (prefs.getString(idKey(me), null) != id) {
            Log.i(TAG, "grupo distinto del anotado: miembros a cero")
            resetGroup(me, id)
        }
    }

    private fun groupKey(me: Int) = "group:%08x".format(me)
    private fun idKey(me: Int) = "groupId:%08x".format(me)
    private fun sinceKey(me: Int) = "groupSince:%08x".format(me)

    private fun loadGroup(me: Int): Set<Int> =
        prefs.getStringSet(groupKey(me), emptySet()).orEmpty().mapNotNull { it.toLongOrNull(16)?.toInt() }.toSet()

    /** Anota un miembro probado (y lo guarda, para que siga siéndolo al reconectar). */
    private fun markGroup(num: Int) {
        if (num in groupNums) return
        val me = _myNodeNum.value ?: return
        // Un paquete por el canal 0 prueba algo solo si el 0 es el privado del grupo anotado.
        val id = currentGroupId() ?: return
        if (prefs.getString(idKey(me), null) != id) return
        groupNums = groupNums + num
        prefs.edit().putStringSet(groupKey(me), groupNums.map { "%08x".format(it) }.toSet()).apply()
    }

    /**
     * Guarda los ajustes que cambiaron en el nodo conectado, en una sola transacción.
     * Orden: el dueño antes que el rol (un rol nuevo instala defaults, p. ej. is_unmessagable,
     * que un set_owner posterior pisaría) y el rol antes que la posición (para que lo que
     * eligió el usuario le gane a los defaults del rol).
     */
    fun saveNodeSettings(e: NodeEdits) = runEdit(
        "Guardando los ajustes…",
        if (e.device?.role in BLUETOOTH_OFF_ROLES) "Guardado. Como router, el nodo apaga el Bluetooth: la app ya no se puede conectar."
        else "Ajustes guardados.",
        // Un router (ROUTER / ROUTER_LATE) en un ESP32 apaga el Bluetooth al segundo de quedar sin
        // teléfono (wait_bluetooth_secs = 1 y light sleep, PowerFSM.cpp / Default.h): reintentar
        // conectar es inútil y dejaba la app colgada varios minutos.
        reconnect = e.device?.role !in BLUETOOTH_OFF_ROLES,
    ) { me ->
        fun cfg(c: Config.Builder) = AdminMessage.newBuilder().setSetConfig(c).build()
        e.owner?.let { sendAdmin(me, AdminMessage.newBuilder().setSetOwner(it).build()) }
        e.device?.let { sendAdmin(me, cfg(Config.newBuilder().setDevice(it))) }
        if (e.removeFixed) sendAdmin(me, AdminMessage.newBuilder().setRemoveFixedPosition(true).build())
        e.position?.let { sendAdmin(me, cfg(Config.newBuilder().setPosition(it))) }
        e.fixed?.let { sendAdmin(me, AdminMessage.newBuilder().setSetFixedPosition(it).build()) }
        e.lora?.let { sendAdmin(me, cfg(Config.newBuilder().setLora(it))) }
        for (ch in e.channels) sendAdmin(me, AdminMessage.newBuilder().setSetChannel(ch).build())
    }

    fun rebootNode() = runEdit("Reiniciando el nodo…", "Nodo reiniciado.", transaction = false) { me ->
        sendAdmin(me, AdminMessage.newBuilder().setRebootSeconds(2).build())
    }

    /** Borra la lista de nodos conocidos (guarda los favoritos) y reinicia. Sirve para limpiar fantasmas. */
    fun forgetNodes() = runEdit("Borrando la lista de nodos…", "Lista de nodos borrada.", transaction = false) { me ->
        sendAdmin(me, AdminMessage.newBuilder().setNodedbReset(true).build())
    }

    /**
     * Olvida nodos sueltos (fantasmas, duplicados): el nodo conectado los borra de su lista con
     * `remove_by_nodenum`, sin reiniciar, y salen del mapa y de la lista. Si alguno vuelve a
     * transmitir, reaparece solo. Devuelve cuántos mandó a borrar (0 sin conexión).
     */
    fun forgetSome(nums: Collection<Int>): Int {
        val me = _myNodeNum.value ?: return 0
        if (link.value != LinkState.Connected) return 0
        val list = nums.filter { it != me }
        if (list.isEmpty()) return 0
        viewModelScope.launch {
            for (n in list) sendAdmin(me, AdminMessage.newBuilder().setRemoveByNodenum(n).build())
        }
        _nodes.update { it - list.toSet() }
        if (list.any { it in groupNums }) {
            groupNums = groupNums - list.toSet()
            prefs.edit().putStringSet(groupKey(me), groupNums.map { "%08x".format(it) }.toSet()).apply()
        }
        return list.size
    }

    /**
     * Marca o desmarca un nodo como favorito en el nodo conectado (`set_favorite_node` /
     * `remove_favorite_node`, sin reiniciar). En rol Base, el nodo prioriza los paquetes de sus
     * favoritos y hacia ellos. Devuelve false sin conexión.
     */
    fun setFavorite(num: Int, on: Boolean): Boolean {
        val me = _myNodeNum.value ?: return false
        if (link.value != LinkState.Connected || num == me) return false
        viewModelScope.launch {
            sendAdmin(me, AdminMessage.newBuilder().apply { if (on) setSetFavoriteNode(num) else setRemoveFavoriteNode(num) }.build())
        }
        _nodes.update { m -> m[num]?.let { m + (num to it.copy(favorite = on)) } ?: m }
        return true
    }

    /** Miembros del grupo que todavía no son favoritos del nodo conectado. */
    fun groupNotFavorite(): List<MeshNode> {
        val me = _myNodeNum.value
        return _nodes.value.values.filter { it.num != me && it.group && !it.favorite && !it.ignored }
    }

    /** Marca como favoritos a todos los del grupo. Devuelve cuántos. */
    fun favoriteGroup(): Int {
        val list = groupNotFavorite()
        if (list.isEmpty()) return 0
        val me = _myNodeNum.value ?: return 0
        if (link.value != LinkState.Connected) return 0
        viewModelScope.launch { for (n in list) sendAdmin(me, AdminMessage.newBuilder().setSetFavoriteNode(n.num).build()) }
        val nums = list.map { it.num }.toSet()
        _nodes.update { m -> m.mapValues { if (it.key in nums) it.value.copy(favorite = true) else it.value } }
        return list.size
    }

    /**
     * Posibles fantasmas: otro nodo con el mismo nombre largo se oyó más recientemente (pasa al
     * reflashear o cambiar de placa: el número de nodo cambia y el viejo queda en la lista), o no
     * se oye hace más de [GHOST_STALE_DAYS] días. Nunca el propio, ni favoritos ni ignorados.
     */
    fun ghostNodes(now: Long = System.currentTimeMillis() / 1000): List<MeshNode> {
        val me = _myNodeNum.value
        // Favoritos e ignorados nunca: un ignorado borrado vuelve a aparecer sin estar ignorado.
        val all = _nodes.value.values.filter { it.num != me && !it.favorite && !it.ignored }
        val dup = all.filter { n -> n.longName.isNotBlank() && duplicateOf(n) != null }
        val old = all.filter { it.lastHeard > 0 && now - it.lastHeard > GHOST_STALE_DAYS * 86_400L }
        return (dup + old).distinctBy { it.num }
    }

    /** El nodo más nuevo con el mismo nombre largo que [n], si [n] es el más viejo de los dos. */
    fun duplicateOf(n: MeshNode): MeshNode? {
        if (n.longName.isBlank()) return null
        return _nodes.value.values
            .filter { it.num != n.num && it.longName == n.longName && it.lastHeard > n.lastHeard }
            .maxByOrNull { it.lastHeard }
    }

    /** ¿El nodo ya tiene la config de posición para rastreo? (null = todavía no la leímos) */
    fun trackingConfigured(): Boolean? = _position.value?.let { it == trackingPosition(it) }

    /** Solo la config de posición para rastreo (nodos que ya tenían el grupo). */
    fun applyTracking() = runEdit("Ajustando la posición del nodo…", "Nodo listo para rastreo.") { me ->
        sendAdmin(me, AdminMessage.newBuilder()
            .setSetConfig(Config.newBuilder().setPosition(trackingPosition(_position.value))).build())
    }

    /**
     * Transacción de edición: begin_edit → [escrituras] → commit_edit. Al hacer commit el nodo
     * guarda, apaga el BLE y puede reiniciarse: reconectamos solos.
     */
    /**
     * Manda mensajes de admin al nodo local y espera a que reinicie para reconectar.
     * Con [transaction], van dentro de begin_edit/commit_edit (el commit guarda y reinicia);
     * sin ella, los mensajes mismos tienen que reiniciar el nodo (reboot, nodedb_reset).
     */
    private fun runEdit(
        firstStep: String,
        doneText: String,
        transaction: Boolean = true,
        reconnect: Boolean = true,
        writes: suspend (me: Int) -> Unit,
    ) {
        val me = _myNodeNum.value ?: run {
            _apply.value = ApplyState.Failed("todavía no sé cuál es el nodo local")
            return
        }
        val device = lastDevice ?: return
        applyJob?.cancel()
        applyJob = viewModelScope.launch {
            try {
                _apply.value = ApplyState.Working(firstStep)
                if (transaction) sendAdmin(me, AdminMessage.newBuilder().setBeginEditSettings(true).build())
                writes(me)
                if (transaction) sendAdmin(me, AdminMessage.newBuilder().setCommitEditSettings(true).build())

                _apply.value = ApplyState.Working("El nodo está guardando y reiniciando…")
                // Esperar a que el nodo corte y volver a conectar.
                withTimeoutOrNull(15_000) { link.first { it !is LinkState.Connected } }
                ble.disconnect()
                if (!reconnect) {
                    _keepAlive.value = false
                    _apply.value = ApplyState.Done(doneText)
                    return@launch
                }
                // Tope total: cada intento de conexión puede tardar más de un minuto (connectWithRetry),
                // y antes eran 6 seguidos sin forma de cortarlos.
                val ok = withTimeoutOrNull(RECONNECT_TOTAL_MS) {
                    var attempt = 0
                    while (true) {
                        attempt++
                        delay(if (attempt == 1) 8_000 else 5_000)
                        _apply.value = ApplyState.Working("Reconectando (intento $attempt)…")
                        connectDevice(device)
                        val st = withTimeoutOrNull(60_000) {
                            link.first { it is LinkState.Connected || it is LinkState.Failed }
                        }
                        if (st is LinkState.Connected) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } == true
                if (!ok) {
                    // Soltar el intento en curso: si queda un connectGatt pendiente, el Bluetooth del
                    // teléfono puede quedar trabado hasta reiniciarlo.
                    _keepAlive.value = false
                    ble.disconnect()
                }
                _apply.value = if (ok) ApplyState.Done(doneText)
                else ApplyState.Failed("se aplicó, pero no pude reconectar en 2 minutos: probá Conectar a mano")
            } catch (e: Exception) {
                // Un corte por tiempo de una escritura BLE (el nodo dejó de contestar) también es una
                // CancellationException: si se relanzaba, la pantalla quedaba en "Guardando…" para siempre.
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.e(TAG, "applyChannelSet", e)
                _keepAlive.value = false
                ble.disconnect()
                _apply.value = ApplyState.Failed(
                    if (e is kotlinx.coroutines.TimeoutCancellationException)
                        "el nodo dejó de contestar. Puede que parte de los cambios no se haya aplicado: reconectá y revisá"
                    else e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private suspend fun sendAdmin(to: Int, msg: AdminMessage, wantResponse: Boolean = false) {
        val packet = MeshPacket.newBuilder()
            .setTo(to)
            .setId((1..Int.MAX_VALUE).random())
            .setChannel(0)
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(PortNum.ADMIN_APP)
                    .setPayload(msg.toByteString())
                    .setWantResponse(wantResponse)
            )
            .build()
        ble.send(ToRadio.newBuilder().setPacket(packet).build())
        delay(150) // no saturar la cola del nodo
    }

    // ---------- Mensajes ----------

    private fun readKey(me: Int, conv: String) = "read:%08x:%s".format(me, conv)

    fun markRead(conv: String) {
        val me = _myNodeNum.value ?: return
        val t = System.currentTimeMillis() / 1000
        prefs.edit().putLong(readKey(me, conv), t).apply()
        _lastRead.update { it + (conv to t) }
        notified.remove(conv)
        Notifier.cancel(getApplication(), conv)
    }

    /** Manda texto a una conversación. Devuelve null si salió, o el motivo si no se puede. */
    fun sendText(conv: String, raw: String, replyTo: Int? = null): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "mensaje vacío"
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return "mensaje demasiado largo"
        val me = _myNodeNum.value ?: return "sin conexión con el nodo"
        val c = Conv.parse(conv) ?: return "conversación inválida"
        val (to, ch) = when (c) {
            is Conv.Channel -> BROADCAST to c.index
            // DM: el firmware lo cifra con PKI si conoce la clave del destino; si no, con el canal.
            is Conv.Direct -> c.num to (_nodes.value[c.num]?.channel ?: 0)
        }
        val m = ChatMsg(
            conv = conv, id = newPacketId(), from = me, to = to, channel = ch, text = text,
            time = System.currentTimeMillis() / 1000, mine = true, status = MsgStatus.SENDING,
            replyTo = replyTo,
        )
        addMessage(m)
        transmit(m)
        markRead(conv)
        return null
    }

    /** Reintento manual de un mensaje fallido: sale con id nuevo. */
    fun retry(m: ChatMsg) {
        if (!m.mine || m.status != MsgStatus.FAILED) return
        val again = m.copy(id = newPacketId(), status = MsgStatus.SENDING, error = null, attempt = m.attempt + 1)
        replaceMessage(m.id, again)
        transmit(again)
    }

    private fun transmit(m: ChatMsg) {
        if (link.value != LinkState.Connected) {
            fail(m.id, "sin conexión con el nodo", autoRetry = false)
            return
        }
        val packet = MeshPacket.newBuilder()
            .setTo(m.to)
            .setId(m.id)
            .setChannel(m.channel)
            .setWantAck(true)
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(PortNum.TEXT_MESSAGE_APP)
                    .setPayload(com.google.protobuf.ByteString.copyFromUtf8(m.text))
                    .apply { m.replyTo?.let { setReplyId(it) } }
            )
            .build()
        viewModelScope.launch {
            try {
                ble.send(ToRadio.newBuilder().setPacket(packet).build())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                fail(m.id, "no se pudo pasar al nodo", autoRetry = false)
                return@launch
            }
            // El firmware siempre contesta (ACK o MAX_RETRANSMIT); esto es por si se corta el BLE.
            ackTimeouts.remove(m.id)?.cancel()
            ackTimeouts[m.id] = launch {
                delay(ACK_TIMEOUT_MS)
                if (_messages.value.any { it.id == m.id && it.status == MsgStatus.SENDING }) {
                    fail(m.id, "sin confirmación", autoRetry = false)
                }
            }
        }
    }

    private fun onRouting(p: MeshPacket) {
        val reqId = p.decoded.requestId
        if (reqId == 0) return
        val m = _messages.value.firstOrNull { it.mine && it.id == reqId } ?: return
        val r = try { Routing.parseFrom(p.decoded.payload) } catch (e: Exception) { return }
        if (r.variantCase != Routing.VariantCase.ERROR_REASON) return
        Log.e(TAG, "ACK de ${"%08x".format(p.from)} para ${m.id}: ${r.errorReason}")
        if (m.status == MsgStatus.DELIVERED) return
        if (r.errorReason == Routing.Error.NONE) {
            // ACK real = viene del destinatario. ACK implícito = viene de nuestro propio nodo
            // (oyó que otro lo repitió).
            val delivered = m.to != BROADCAST && p.from == m.to
            ackTimeouts.remove(m.id)?.cancel()
            replaceMessage(m.id, m.copy(
                status = if (delivered) MsgStatus.DELIVERED else MsgStatus.RELAYED, error = null,
                hops = if (delivered) hopsOf(p.hopStart, p.hopLimit) else m.hops,
            ))
        } else {
            fail(m.id, routingErrorText(r.errorReason), autoRetry = true)
        }
    }

    /** Marca el mensaje como fallido; la primera vez lo reenvía solo, con id nuevo. */
    private fun fail(id: Int, why: String, autoRetry: Boolean) {
        ackTimeouts.remove(id)?.cancel()
        val m = _messages.value.firstOrNull { it.mine && it.id == id } ?: return
        if (autoRetry && m.attempt < AUTO_ATTEMPTS && link.value == LinkState.Connected) {
            Log.e(TAG, "mensaje ${m.id} falló ($why), reintento automático")
            val again = m.copy(id = newPacketId(), status = MsgStatus.SENDING, error = null, attempt = m.attempt + 1)
            replaceMessage(id, again)
            transmit(again)
        } else {
            replaceMessage(id, m.copy(status = MsgStatus.FAILED, error = why))
        }
    }

    private fun onText(p: MeshPacket) {
        val me = _myNodeNum.value ?: return
        if (p.from == 0) return
        val mine = p.from == me
        val conv = when {
            p.to == me -> Conv.Direct(p.from).key
            mine && p.to != BROADCAST -> Conv.Direct(p.to).key // mandado desde la pantalla del nodo
            else -> Conv.Channel(p.channel).key
        }
        if (_messages.value.any { it.id == p.id && it.from == p.from }) return // duplicado
        val t = if (p.hasRxTime() && p.rxTime != 0) p.rxTime.toLong() and 0xffffffffL
        else System.currentTimeMillis() / 1000
        addMessage(
            ChatMsg(
                conv = conv, id = p.id, from = p.from, to = p.to, channel = p.channel,
                text = p.decoded.payload.toStringUtf8(), time = t, mine = mine,
                status = if (mine) MsgStatus.RELAYED else MsgStatus.RECEIVED,
                hops = if (mine) null else hopsOf(p.hopStart, p.hopLimit),
                replyTo = p.decoded.replyId.takeIf { it != 0 },
            )
        )
        if (!mine) _messages.value.lastOrNull { it.id == p.id && it.from == p.from }?.let(::notifyIncoming)
    }

    private fun addMessage(m: ChatMsg) {
        _messages.update { it + m }
        persist()
    }

    private fun replaceMessage(id: Int, m: ChatMsg) {
        _messages.update { list -> list.map { if (it.id == id && it.mine) m else it } }
        persist()
    }

    private fun persist() {
        val me = _myNodeNum.value ?: return
        val snapshot = _messages.value
        viewModelScope.launch(Dispatchers.IO) {
            try { store.save(me, snapshot) } catch (e: Exception) { Log.e(TAG, "guardar mensajes", e) }
        }
    }

    private fun newPacketId(): Int = (1..Int.MAX_VALUE).random()

    // ---------- Rastros ----------

    private fun recordTrack(num: Int, lat: Double, lon: Double, time: Long) {
        val me = _myNodeNum.value ?: return
        var changed = false
        _tracks.update { map ->
            val next = TrackStore.append(map[num].orEmpty(), TrackPoint(lat, lon, time))
            changed = next != null
            if (next == null) map else map + (num to next)
        }
        if (!changed) return
        val snapshot = _tracks.value
        viewModelScope.launch(Dispatchers.IO) {
            try { trackStore.save(me, snapshot) } catch (e: Exception) { Log.e(TAG, "guardar rastros", e) }
        }
    }

    fun clearTracks() {
        val me = _myNodeNum.value ?: return
        _tracks.value = emptyMap()
        viewModelScope.launch(Dispatchers.IO) { runCatching { trackStore.save(me, emptyMap()) } }
    }

    // ---------- Waypoints ----------

    /**
     * Crea un waypoint y lo difunde por el primario (el canal privado del grupo).
     * Queda bloqueado a nuestro nodo: solo nosotros lo podemos cambiar o borrar.
     * Devuelve null si salió, o el motivo si no se puede.
     */
    fun sendWaypoint(lat: Double, lon: Double, name: String, description: String, icon: Int, expire: Long): String? {
        val me = _myNodeNum.value ?: return "sin conexión con el nodo"
        if (link.value != LinkState.Connected) return "sin conexión con el nodo"
        val n = Wpt.fitBytes(name.trim(), Wpt.MAX_NAME)
        if (n.isEmpty()) return "ponele un nombre"
        val w = Wpt(
            id = newPacketId(), lat = lat, lon = lon, name = n,
            description = Wpt.fitBytes(description.trim(), Wpt.MAX_DESCRIPTION),
            icon = icon, expire = expire, lockedTo = me, from = me, time = System.currentTimeMillis() / 1000,
        )
        broadcastWaypoint(w.toProto())
        putWaypoint(w)
        return null
    }

    /** ¿Lo podemos borrar para todos? (lo creamos nosotros o no está bloqueado) */
    fun canDeleteForAll(w: Wpt): Boolean {
        val me = _myNodeNum.value ?: return false
        return w.lockedTo == 0 || w.lockedTo == me
    }

    /**
     * Borra un waypoint. Si es nuestro (o libre), lo difunde con expire = 1, que es la convención
     * de la malla para "borrar". Si está bloqueado a otro nodo, solo lo saca de este teléfono.
     */
    fun deleteWaypoint(w: Wpt) {
        if (canDeleteForAll(w) && link.value == LinkState.Connected) {
            broadcastWaypoint(w.toProto().toBuilder().setExpire(1).build())
        }
        removeWaypoint(w.id)
    }

    private fun broadcastWaypoint(w: Waypoint) {
        val packet = MeshPacket.newBuilder()
            .setTo(BROADCAST)
            .setId(newPacketId())
            .setChannel(0)
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(PortNum.WAYPOINT_APP)
                    .setPayload(w.toByteString())
            )
            .build()
        viewModelScope.launch {
            try {
                ble.send(ToRadio.newBuilder().setPacket(packet).build())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "no pude mandar el waypoint", e)
            }
        }
    }

    private fun onWaypoint(p: MeshPacket) {
        val w = try { Waypoint.parseFrom(p.decoded.payload) } catch (e: Exception) { return }
        val from = if (p.from == 0) _myNodeNum.value ?: 0 else p.from
        val now = System.currentTimeMillis() / 1000
        val old = _waypoints.value[w.id]
        // Respetar el bloqueo: solo el dueño lo puede cambiar o borrar.
        if (old != null && old.lockedTo != 0 && old.lockedTo != from) return
        val wpt = Wpt.fromProto(w, from, now)
        if (wpt == null || !wpt.isActive(now)) {
            if (old != null) removeWaypoint(w.id)
            return
        }
        putWaypoint(wpt)
    }

    private fun putWaypoint(w: Wpt) {
        _waypoints.update { it + (w.id to w) }
        persistWaypoints()
    }

    private fun removeWaypoint(id: Int) {
        _waypoints.update { it - id }
        persistWaypoints()
    }

    private fun persistWaypoints() {
        val me = _myNodeNum.value ?: return
        val snapshot = _waypoints.value
        viewModelScope.launch(Dispatchers.IO) {
            try { waypointStore.save(me, snapshot) } catch (e: Exception) { Log.e(TAG, "guardar waypoints", e) }
        }
    }

    // ---------- FromRadio ----------

    private fun onFromRadio(msg: FromRadio) {
        when (msg.payloadVariantCase) {
            FromRadio.PayloadVariantCase.MY_INFO -> onMyInfo(msg.myInfo.myNodeNum)
            FromRadio.PayloadVariantCase.NODE_INFO -> upsert(msg.nodeInfo)
            FromRadio.PayloadVariantCase.CHANNEL -> {
                val c = msg.channel
                _channels.update { if (c.role == Channel.Role.DISABLED) it - c.index else it + (c.index to c) }
                if (c.index == 0) syncGroup()
            }
            FromRadio.PayloadVariantCase.CONFIG -> when (msg.config.payloadVariantCase) {
                Config.PayloadVariantCase.LORA -> _lora.value = msg.config.lora
                Config.PayloadVariantCase.DEVICE -> _device.value = msg.config.device
                Config.PayloadVariantCase.POSITION -> _position.value = msg.config.position
                else -> Unit
            }
            FromRadio.PayloadVariantCase.PACKET -> onPacket(msg.packet)
            FromRadio.PayloadVariantCase.METADATA -> _metadata.value = msg.metadata
            else -> Unit
        }
    }

    private fun onMyInfo(num: Int) {
        if (_myNodeNum.value == num && _messages.value.isNotEmpty()) return
        _myNodeNum.value = num
        _messages.value = store.load(num)
        // Los mensajes guardados del canal privado también prueban que el emisor tiene la clave.
        val since = prefs.getLong(sinceKey(num), Long.MAX_VALUE)
        _groupSince.value = since.takeIf { it != Long.MAX_VALUE } ?: 0L
        groupNums = loadGroup(num) + _messages.value
            .filter { !it.mine && it.conv == Conv.Channel(0).key && it.time >= since && it.from != num }
            .map { it.from }
        _tracks.value = trackStore.load(num)
        _waypoints.value = waypointStore.load(num)
        syncGroup()
        _lastRead.value = _messages.value.map { it.conv }.distinct()
            .associateWith { prefs.getLong(readKey(num, it), 0L) }
    }

    /** Cada paquete recibido actualiza "último oído" y el canal del nodo que lo mandó. */
    private fun onPacket(p: MeshPacket) {
        if (p.hasDecoded()) when (p.decoded.portnum) {
            PortNum.ROUTING_APP -> onRouting(p)
            PortNum.TEXT_MESSAGE_APP -> onText(p)
            PortNum.POSITION_APP -> onPosition(p)
            PortNum.WAYPOINT_APP -> onWaypoint(p)
            PortNum.ADMIN_APP -> onAdmin(p)
            else -> if (p.decoded.portnumValue == Calandria.PORT) onCalandria(p)
        }
        val from = p.from
        if (from == 0 || from == _myNodeNum.value) return
        val t = if (p.hasRxTime()) p.rxTime.toLong() and 0xffffffffL else System.currentTimeMillis() / 1000
        val user = if (p.hasDecoded() && p.decoded.portnum == PortNum.NODEINFO_APP) {
            try { MeshProtos.User.parseFrom(p.decoded.payload) } catch (e: Exception) { null }
        } else null
        // Saltos: hop_start - hop_limit. hop_start == 0 = firmware viejo o desconocido.
        val hops = if (p.hopStart != 0 && p.hopStart >= p.hopLimit) p.hopStart - p.hopLimit else null
        val direct = hops == 0
        val channel = channelProof(p)
        if (channel == 0) markGroup(from)
        _nodes.update { map ->
            val old = map[from] ?: MeshNode(
                from, "!%08x".format(from), "", "", 0, 0f, null, null, null, null, channel,
            )
            map + (from to old.copy(
                lastHeard = t,
                hopsAway = hops ?: old.hopsAway,
                // SNR/RSSI de un paquete repetido son del repetidor, no del nodo: solo si llegó directo.
                snr = if (direct && p.rxSnr != 0f) p.rxSnr else old.snr,
                rssi = if (direct && p.hasRxRssi()) p.rxRssi else old.rssi,
                channel = channel ?: old.channel,
                longName = user?.longName?.ifEmpty { null } ?: old.longName,
                shortName = user?.shortName?.ifEmpty { null } ?: old.shortName,
                id = user?.id?.ifEmpty { null } ?: old.id,
                mute = user?.let { it.role == Config.DeviceConfig.Role.CLIENT_MUTE } ?: old.mute,
                role = user?.role ?: old.role,
                hw = user?.hwModel ?: old.hw,
                group = from in groupNums,
            ))
        }
        _nodes.value[from]?.let(::maybeAskIdentity)
    }

    // ---------- Calandria ----------

    private fun loadCalandria(): Map<Int, Calandria.Info> =
        prefs.getStringSet("calandria", emptySet()).orEmpty().mapNotNull { e ->
            val k = e.substringBefore('=').toLongOrNull(16)?.toInt() ?: return@mapNotNull null
            Calandria.decode(e.substringAfter('='))?.let { k to it }
        }.toMap()

    private fun saveCalandria(m: Map<Int, Calandria.Info>) {
        prefs.edit().putStringSet("calandria", m.map { (k, v) -> "%08x=%s".format(k, Calandria.encode(v)) }.toSet()).apply()
    }

    /** El anuncio de un nodo Calandria (o la respuesta a nuestro pedido). */
    private fun onCalandria(p: MeshPacket) {
        val from = if (p.from == 0) _myNodeNum.value ?: return else p.from
        val info = Calandria.parse(p.decoded.payload.toByteArray(), System.currentTimeMillis() / 1000) ?: return
        val old = _calandria.value[from]
        if (old != null && old.model == info.model && old.fw == info.fw && old.env == info.env && old.ver == info.ver) return
        _calandria.update { it + (from to info) }
        saveCalandria(_calandria.value)
    }

    /**
     * "¿Qué sos?" a un nodo con placa propia que todavía no se anunció: el firmware Calandria
     * contesta directo (los demás lo ignoran). Una vez por nodo y por conexión, y solo a los que
     * oímos por el canal primario (el anuncio y la respuesta van por ahí).
     */
    private fun askIdentity(num: Int) {
        if (BuildConfig.DEMO || link.value != LinkState.Connected) return
        val me = _myNodeNum.value
        val ch = if (num == me) 0 else _nodes.value[num]?.channel ?: return
        if (ch != 0 || !askedIdentity.add(num)) return
        val packet = MeshPacket.newBuilder()
            .setTo(num)
            .setId(newPacketId())
            .setChannel(0)
            .setDecoded(MeshProtos.Data.newBuilder().setPortnumValue(Calandria.PORT).setWantResponse(true))
            .build()
        viewModelScope.launch {
            try { ble.send(ToRadio.newBuilder().setPacket(packet).build()) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                askedIdentity.remove(num)
            }
        }
    }

    /**
     * Le pasa al nodo conectado la zona horaria del teléfono (config.device.tzdef, formato POSIX),
     * si la que tiene es otra: si no, la pantalla del nodo muestra la hora UTC. Cambiar solo tzdef
     * no reinicia el nodo (AdminModule); el firmware estándar la aplica en el próximo arranque.
     */
    private suspend fun syncTimezone() {
        if (BuildConfig.DEMO || link.value != LinkState.Connected) return
        val me = _myNodeNum.value ?: return
        val dev = _device.value ?: return
        val tz = runCatching { io.github.protohommer.meshpaper.mesh.PosixTz.of() }.getOrNull() ?: return
        if (dev.tzdef == tz) return
        Log.i(TAG, "zona horaria del nodo: '${dev.tzdef}' -> '$tz'")
        val next = dev.toBuilder().setTzdef(tz).build()
        sendAdmin(me, AdminMessage.newBuilder().setSetConfig(Config.newBuilder().setDevice(next)).build())
        _device.value = next
    }

    /**
     * Al Calandria conectado le cuenta cuál es el último firmware publicado, para que lo muestre en
     * su pantalla. Va solo al nodo local (no se retransmite), por el puerto Calandria:
     *   CALANDRIA/1;latest=v2.8.3-ws154.5;commit=37413903f…
     */
    private fun tellLatestFirmware() {
        if (BuildConfig.DEMO || link.value != LinkState.Connected) return
        val me = _myNodeNum.value ?: return
        val info = _calandria.value[me] ?: return
        val r = Calandria.latest.value ?: return
        // Un build más nuevo que el publicado compara por commit en el nodo y diría "hay <vieja>": no avisar
        if (Calandria.newerThanLatest(info, r)) return
        val text = "CALANDRIA/1;latest=${r.tag};commit=${r.commit}"
        val packet = MeshPacket.newBuilder()
            .setTo(me)
            .setId(newPacketId())
            .setChannel(0)
            .setDecoded(MeshProtos.Data.newBuilder().setPortnumValue(Calandria.PORT).setPayload(ByteString.copyFromUtf8(text)))
            .build()
        viewModelScope.launch {
            runCatching { ble.send(ToRadio.newBuilder().setPacket(packet).build()) }
        }
    }

    private fun maybeAskIdentity(n: MeshNode) {
        if (n.hw == MeshProtos.HardwareModel.PRIVATE_HW && n.channel == 0 && n.num !in _calandria.value) askIdentity(n.num)
    }

    // ---------- Mensajes rápidos (canned messages del nodo) ----------

    /** Respuestas de administración del nodo conectado. */
    private fun onAdmin(p: MeshPacket) {
        val a = try { AdminMessage.parseFrom(p.decoded.payload) } catch (e: Exception) { return }
        if (a.payloadVariantCase == AdminMessage.PayloadVariantCase.GET_CANNED_MESSAGE_MODULE_MESSAGES_RESPONSE) {
            _canned.value = a.getCannedMessageModuleMessagesResponse.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /** Pide al nodo sus mensajes rápidos (llegan por [canned]). */
    fun loadCanned() {
        val me = _myNodeNum.value ?: return
        if (link.value != LinkState.Connected) return
        viewModelScope.launch {
            // Sin want_response el firmware arma la respuesta vacía y no manda nada
            // (CannedMessageModule::handleGetCannedMessageModuleMessages).
            try { sendAdmin(me, AdminMessage.newBuilder().setGetCannedMessageModuleMessagesRequest(true).build(), wantResponse = true) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
        }
    }

    /**
     * Guarda los mensajes rápidos en el nodo. El firmware los guarda sin reiniciar; en el e-paper
     * aparecen en el menú para mandarlos con el encoder. Máximo [CANNED_MAX_BYTES] en total.
     * Devuelve null si salió, o el motivo si no se puede.
     */
    fun saveCanned(list: List<String>): String? {
        val me = _myNodeNum.value ?: return "sin conexión con el nodo"
        if (link.value != LinkState.Connected) return "sin conexión con el nodo"
        val clean = list.map { it.replace("|", "/").trim() }.filter { it.isNotEmpty() }
        val joined = clean.joinToString("|")
        if (joined.toByteArray(Charsets.UTF_8).size > CANNED_MAX_BYTES) return "no entran: hasta $CANNED_MAX_BYTES caracteres en total"
        viewModelScope.launch {
            try {
                sendAdmin(me, AdminMessage.newBuilder().setSetCannedMessageModuleMessages(joined).build())
                _canned.value = clean
                _apply.value = ApplyState.Done("Mensajes rápidos guardados en el nodo.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _apply.value = ApplyState.Failed("no pude guardar los mensajes rápidos")
            }
        }
        return null
    }

    /** Posición que llega por la malla (o la propia que el nodo nos reenvía). */
    private fun onPosition(p: MeshPacket) {
        val pos = try { MeshProtos.Position.parseFrom(p.decoded.payload) } catch (e: Exception) { return }
        if (!pos.hasLatitudeI() || !pos.hasLongitudeI() || (pos.latitudeI == 0 && pos.longitudeI == 0)) return
        val from = if (p.from == 0) _myNodeNum.value ?: return else p.from
        _nodes.update { map ->
            val old = map[from] ?: MeshNode(
                from, "!%08x".format(from), "", "", 0, 0f, null, null, null, null, channelProof(p),
            )
            map + (from to old.copy(lat = pos.latitudeI / 1e7, lon = pos.longitudeI / 1e7, alt = mslOf(pos), precisionBits = pos.precisionBits, group = from in groupNums))
        }
        val t = when {
            pos.time != 0 -> pos.time.toLong() and 0xffffffffL
            p.rxTime != 0 -> p.rxTime.toLong() and 0xffffffffL
            else -> System.currentTimeMillis() / 1000
        }
        // El propio nodo: su rastro sale del GPS del teléfono, no del eco de su posición.
        if (from != _myNodeNum.value) recordTrack(from, pos.latitudeI / 1e7, pos.longitudeI / 1e7, t)
    }

    private fun upsert(n: NodeInfo) {
        if (n.num == _myNodeNum.value && n.hasUser()) _owner.value = n.user
        if (n.hasUser() && n.channel == 0 && n.num != _myNodeNum.value) markGroup(n.num)
        val node = MeshNode(
            num = n.num,
            id = n.user.id.ifEmpty { "!%08x".format(n.num) },
            longName = n.user.longName,
            shortName = n.user.shortName,
            lastHeard = n.lastHeard.toLong() and 0xffffffffL,
            snr = n.snr,
            hopsAway = if (n.hasHopsAway()) n.hopsAway else null,
            battery = if (n.deviceMetrics.hasBatteryLevel()) n.deviceMetrics.batteryLevel else null,
            lat = if (n.position.hasLatitudeI()) n.position.latitudeI / 1e7 else null,
            lon = if (n.position.hasLongitudeI()) n.position.longitudeI / 1e7 else null,
            // La NodeDB asigna el canal solo al recibir un NodeInfo (updateUser): sin user queda en 0
            // por defecto, aunque lo hayamos oído por el público. Ese 0 no prueba nada.
            channel = if (n.hasUser()) n.channel else _nodes.value[n.num]?.channel,
            mute = n.user.role == Config.DeviceConfig.Role.CLIENT_MUTE,
            role = if (n.hasUser()) n.user.role else _nodes.value[n.num]?.role,
            hw = if (n.hasUser()) n.user.hwModel else _nodes.value[n.num]?.hw,
            alt = if (n.position.hasLatitudeI()) mslOf(n.position) else _nodes.value[n.num]?.alt,
            precisionBits = if (n.position.hasLatitudeI()) n.position.precisionBits else _nodes.value[n.num]?.precisionBits,
            group = n.num in groupNums,
            favorite = n.isFavorite,
            ignored = n.isIgnored,
        )
        _nodes.update { it + (n.num to node) }
        maybeAskIdentity(node)
        if (node.lat != null && node.lon != null && n.num != _myNodeNum.value &&
            !(n.position.latitudeI == 0 && n.position.longitudeI == 0)) {
            val t = (n.position.time.toLong() and 0xffffffffL).takeIf { it != 0L } ?: node.lastHeard
            if (t != 0L) recordTrack(n.num, node.lat, node.lon, t)
        }
    }

    override fun onCleared() {
        phoneLocation.stop()
        stopScan()
        ble.disconnect()
    }

    private companion object {
        const val TAG = "MeshVM"
        const val MAX_CHANNELS = 8
        const val AUTO_ATTEMPTS = 2           // 1 envío + 1 reintento automático
        const val ACK_TIMEOUT_MS = 180_000L   // el firmware se rinde antes; esto cubre cortes de BLE
        const val MAX_FIX_ACCURACY_M = 100f
        const val POS_MIN_INTERVAL_MS = 30_000L
        const val POS_HEARTBEAT_MS = 120_000L
        const val POS_MIN_MOVE_M = 10f
    }
}
