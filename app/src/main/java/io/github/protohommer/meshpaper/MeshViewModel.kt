package io.github.protohommer.meshpaper

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.protohommer.meshpaper.ble.LinkState
import io.github.protohommer.meshpaper.ble.MeshBle
import io.github.protohommer.meshpaper.ble.MeshUuids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.MeshProtos.NodeInfo

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
)

@SuppressLint("MissingPermission")
class MeshViewModel(app: Application) : AndroidViewModel(app) {
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _myNodeNum = MutableStateFlow<Int?>(null)
    val myNodeNum: StateFlow<Int?> = _myNodeNum

    private val _nodes = MutableStateFlow<Map<Int, MeshNode>>(emptyMap())
    val nodes: StateFlow<Map<Int, MeshNode>> = _nodes

    private val ble = MeshBle(app, viewModelScope, ::onFromRadio)
    val link: StateFlow<LinkState> = ble.state

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: d.name ?: d.address
            _found.update { list ->
                (list.filterNot { it.device.address == d.address } + FoundDevice(d, name, result.rssi, d.bondState == BluetoothDevice.BOND_BONDED))
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
        _nodes.value = emptyMap()
        _myNodeNum.value = null
        ble.connect(d.device)
    }

    fun disconnect() = ble.disconnect()

    private fun onFromRadio(msg: FromRadio) {
        when (msg.payloadVariantCase) {
            FromRadio.PayloadVariantCase.MY_INFO -> _myNodeNum.value = msg.myInfo.myNodeNum
            FromRadio.PayloadVariantCase.NODE_INFO -> upsert(msg.nodeInfo)
            else -> Unit // Config, canales, paquetes: etapas siguientes
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
        )
        _nodes.update { it + (n.num to node) }
    }

    override fun onCleared() {
        stopScan()
        ble.disconnect()
    }
}
