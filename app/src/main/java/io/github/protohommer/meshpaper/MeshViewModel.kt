package io.github.protohommer.meshpaper

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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
import io.github.protohommer.meshpaper.mesh.Group
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.AppOnlyProtos.ChannelSet
import org.meshtastic.proto.ChannelProtos.Channel
import org.meshtastic.proto.ChannelProtos.ChannelSettings
import org.meshtastic.proto.ConfigProtos.Config
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.MeshProtos.MeshPacket
import org.meshtastic.proto.MeshProtos.NodeInfo
import org.meshtastic.proto.MeshProtos.ToRadio
import org.meshtastic.proto.Portnums.PortNum

data class FoundDevice(val device: BluetoothDevice, val name: String, val rssi: Int, val bonded: Boolean)

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
    val channel: Int,         // índice local del canal en que lo oímos (0 = primario)
)

sealed interface ApplyState {
    data object Idle : ApplyState
    data class Working(val step: String) : ApplyState
    data object Done : ApplyState
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

    private val _myNodeNum = MutableStateFlow<Int?>(null)
    val myNodeNum: StateFlow<Int?> = _myNodeNum

    private val _nodes = MutableStateFlow<Map<Int, MeshNode>>(emptyMap())
    val nodes: StateFlow<Map<Int, MeshNode>> = _nodes

    /** Canales del nodo, por índice (solo los activos). */
    private val _channels = MutableStateFlow<Map<Int, Channel>>(emptyMap())
    val channels: StateFlow<Map<Int, Channel>> = _channels

    private val _lora = MutableStateFlow<LoRaConfig?>(null)
    val lora: StateFlow<LoRaConfig?> = _lora

    private val _apply = MutableStateFlow<ApplyState>(ApplyState.Idle)
    val apply: StateFlow<ApplyState> = _apply

    /** Desde cuándo (epoch s) este nodo está en el grupo actual. Para no contar nodos viejos. */
    private val _groupSince = MutableStateFlow(prefs.getLong("groupSince", 0L))
    val groupSince: StateFlow<Long> = _groupSince

    private val ble = MeshBle(app, viewModelScope, ::onFromRadio).apply {
        knownPin = prefs.getString("pin", MeshBle.DEFAULT_PIN) ?: MeshBle.DEFAULT_PIN
        onPinAccepted = { prefs.edit().putString("pin", it).apply() }
    }
    val link: StateFlow<LinkState> = ble.state

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    private var lastDevice: BluetoothDevice? = null
    private var applyJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: d.name ?: d.address
            _found.update { list ->
                (list.filterNot { it.device.address == d.address } +
                    FoundDevice(d, name, result.rssi, d.bondState == BluetoothDevice.BOND_BONDED))
                    .sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        _found.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        _scanning.value = true
    }

    fun stopScan() {
        if (!_scanning.value) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _scanning.value = false
    }

    /** El emparejamiento (diálogo de PIN de Android) ocurre dentro de la conexión. */
    fun pairAndConnect(d: FoundDevice) = connect(d)

    fun connect(d: FoundDevice) {
        stopScan()
        connectDevice(d.device)
    }

    private fun connectDevice(device: BluetoothDevice) {
        lastDevice = device
        _nodes.value = emptyMap()
        _channels.value = emptyMap()
        _lora.value = null
        _myNodeNum.value = null
        ble.connect(device)
    }

    fun submitPin(pin: String) = ble.submitPin(pin)

    fun disconnect() {
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

    fun dismissApply() {
        _apply.value = ApplyState.Idle
    }

    /**
     * Escribe el ChannelSet en el nodo dentro de una transacción de edición:
     * begin_edit → set_channel (0..7) → set_config(lora) → commit_edit.
     * Al hacer commit el nodo guarda, apaga el BLE y puede reiniciarse: reconectamos solos.
     */
    fun applyChannelSet(set: ChannelSet) {
        val me = _myNodeNum.value ?: run {
            _apply.value = ApplyState.Failed("todavía no sé cuál es el nodo local")
            return
        }
        val device = lastDevice ?: return
        applyJob?.cancel()
        applyJob = viewModelScope.launch {
            try {
                _apply.value = ApplyState.Working("Escribiendo canales en el nodo…")
                sendAdmin(me, AdminMessage.newBuilder().setBeginEditSettings(true).build())
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
                sendAdmin(me, AdminMessage.newBuilder().setCommitEditSettings(true).build())

                val now = System.currentTimeMillis() / 1000
                prefs.edit().putLong("groupSince", now).apply()
                _groupSince.value = now

                _apply.value = ApplyState.Working("El nodo está guardando y reiniciando…")
                // Esperar a que el nodo corte y volver a conectar.
                withTimeoutOrNull(15_000) { link.first { it !is LinkState.Connected } }
                ble.disconnect()
                var ok = false
                for (attempt in 1..6) {
                    delay(if (attempt == 1) 8_000 else 5_000)
                    _apply.value = ApplyState.Working("Reconectando (intento $attempt/6)…")
                    connectDevice(device)
                    val st = withTimeoutOrNull(60_000) {
                        link.first { it is LinkState.Connected || it is LinkState.Failed }
                    }
                    if (st is LinkState.Connected) { ok = true; break }
                }
                _apply.value = if (ok) ApplyState.Done
                else ApplyState.Failed("se aplicó, pero no pude reconectar: probá Conectar a mano")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "applyChannelSet", e)
                _apply.value = ApplyState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun sendAdmin(to: Int, msg: AdminMessage) {
        val packet = MeshPacket.newBuilder()
            .setTo(to)
            .setId((1..Int.MAX_VALUE).random())
            .setChannel(0)
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(PortNum.ADMIN_APP)
                    .setPayload(msg.toByteString())
            )
            .build()
        ble.send(ToRadio.newBuilder().setPacket(packet).build())
        delay(150) // no saturar la cola del nodo
    }

    // ---------- FromRadio ----------

    private fun onFromRadio(msg: FromRadio) {
        when (msg.payloadVariantCase) {
            FromRadio.PayloadVariantCase.MY_INFO -> _myNodeNum.value = msg.myInfo.myNodeNum
            FromRadio.PayloadVariantCase.NODE_INFO -> upsert(msg.nodeInfo)
            FromRadio.PayloadVariantCase.CHANNEL -> {
                val c = msg.channel
                _channels.update { if (c.role == Channel.Role.DISABLED) it - c.index else it + (c.index to c) }
            }
            FromRadio.PayloadVariantCase.CONFIG ->
                if (msg.config.payloadVariantCase == Config.PayloadVariantCase.LORA) _lora.value = msg.config.lora
            FromRadio.PayloadVariantCase.PACKET -> onPacket(msg.packet)
            else -> Unit
        }
    }

    /** Cada paquete recibido actualiza "último oído" y el canal del nodo que lo mandó. */
    private fun onPacket(p: MeshPacket) {
        val from = p.from
        if (from == 0 || from == _myNodeNum.value) return
        val t = if (p.hasRxTime()) p.rxTime.toLong() and 0xffffffffL else System.currentTimeMillis() / 1000
        val user = if (p.hasDecoded() && p.decoded.portnum == PortNum.NODEINFO_APP) {
            try { MeshProtos.User.parseFrom(p.decoded.payload) } catch (e: Exception) { null }
        } else null
        _nodes.update { map ->
            val old = map[from] ?: MeshNode(
                from, "!%08x".format(from), "", "", 0, 0f, null, null, null, null, p.channel,
            )
            map + (from to old.copy(
                lastHeard = t,
                snr = if (p.rxSnr != 0f) p.rxSnr else old.snr,
                channel = p.channel,
                longName = user?.longName?.ifEmpty { null } ?: old.longName,
                shortName = user?.shortName?.ifEmpty { null } ?: old.shortName,
                id = user?.id?.ifEmpty { null } ?: old.id,
            ))
        }
    }

    private fun upsert(n: NodeInfo) {
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
            channel = n.channel,
        )
        _nodes.update { it + (n.num to node) }
    }

    override fun onCleared() {
        stopScan()
        ble.disconnect()
    }

    private companion object {
        const val TAG = "MeshVM"
        const val MAX_CHANNELS = 8
    }
}
