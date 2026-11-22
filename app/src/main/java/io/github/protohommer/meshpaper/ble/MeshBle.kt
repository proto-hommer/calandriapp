package io.github.protohommer.meshpaper.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.MeshProtos.ToRadio
import java.util.UUID

/** UUIDs confirmados en el firmware: C:\mt\src\BluetoothCommon.h */
object MeshUuids {
    val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

sealed interface LinkState {
    data object Disconnected : LinkState
    data class Connecting(val step: String) : LinkState
    data object Connected : LinkState
    data class Failed(val reason: String) : LinkState
}

/**
 * Conexión BLE con un nodo Meshtastic. Serializa las operaciones GATT (Android solo admite una
 * a la vez) y entrega cada FromRadio por [onFromRadio].
 *
 * Los permisos BLUETOOTH_CONNECT/SCAN se piden en la UI antes de usar esta clase.
 */
@SuppressLint("MissingPermission")
class MeshBle(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFromRadio: (FromRadio) -> Unit,
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state

    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null

    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    private var connected = CompletableDeferred<Unit>()
    private var servicesFound = CompletableDeferred<Unit>()
    private var sessionJob: Job? = null

    fun connect(device: BluetoothDevice) {
        disconnect()
        connected = CompletableDeferred()
        servicesFound = CompletableDeferred()
        sessionJob = scope.launch {
            try {
                _state.value = LinkState.Connecting("Conectando…")
                val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                gatt = g
                withTimeout(20_000) { connected.await() }

                _state.value = LinkState.Connecting("Negociando MTU…")
                op { g.requestMtu(512) }

                _state.value = LinkState.Connecting("Buscando servicio Meshtastic…")
                g.discoverServices()
                withTimeout(15_000) { servicesFound.await() }
                val svc = g.getService(MeshUuids.SERVICE)
                    ?: error("El dispositivo no tiene el servicio Meshtastic")
                toRadio = svc.getCharacteristic(MeshUuids.TO_RADIO)
                fromRadio = svc.getCharacteristic(MeshUuids.FROM_RADIO)
                fromNum = svc.getCharacteristic(MeshUuids.FROM_NUM)
                if (toRadio == null || fromRadio == null || fromNum == null) {
                    error("Faltan características Meshtastic")
                }

                // La primera lectura cifrada dispara el emparejamiento (PIN) si hace falta.
                _state.value = LinkState.Connecting("Pidiendo configuración (puede pedir PIN)…")
                val nonce = (1..Int.MAX_VALUE).random()
                send(ToRadio.newBuilder().setWantConfigId(nonce).build())
                drainFromRadio()

                enableFromNumNotify()
                _state.value = LinkState.Connected
                // Por si llegó algo entre el drenado y la suscripción.
                drainFromRadio()
            } catch (e: Exception) {
                Log.w(TAG, "Fallo de conexión", e)
                _state.value = LinkState.Failed(e.message ?: e.javaClass.simpleName)
                closeGatt()
            }
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        closeGatt()
        _state.value = LinkState.Disconnected
    }

    suspend fun send(msg: ToRadio) {
        val g = gatt ?: return
        val c = toRadio ?: return
        val bytes = msg.toByteArray()
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    /** Lee FromRadio hasta que venga vacío. */
    private suspend fun drainFromRadio() {
        val g = gatt ?: return
        val c = fromRadio ?: return
        while (true) {
            val bytes = op { g.readCharacteristic(c) } as? ByteArray ?: return
            if (bytes.isEmpty()) return
            try {
                onFromRadio(FromRadio.parseFrom(bytes))
            } catch (e: Exception) {
                Log.w(TAG, "FromRadio inválido (${bytes.size} bytes)", e)
            }
        }
    }

    private suspend fun enableFromNumNotify() {
        val g = gatt ?: return
        val c = fromNum ?: return
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(MeshUuids.CCCD) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = v
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
    }

    /** Ejecuta una operación GATT y espera su callback. */
    private suspend fun op(start: () -> Boolean): Any? = opLock.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        if (!start()) {
            pending = null
            error("La operación GATT no arrancó")
        }
        withTimeout(10_000) { d.await() }
    }

    private fun complete(status: Int, value: Any? = null) {
        val d = pending ?: return
        pending = null
        if (status == BluetoothGatt.GATT_SUCCESS) d.complete(value)
        else d.completeExceptionally(IllegalStateException("GATT status $status"))
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        pending?.cancel()
        pending = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.completeExceptionally(IllegalStateException("Desconectado (status $status)"))
                pending?.completeExceptionally(IllegalStateException("Desconectado"))
                pending = null
                if (_state.value == LinkState.Connected) {
                    _state.value = LinkState.Failed("Se perdió la conexión")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU $mtu")
            complete(status, mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesFound.complete(Unit)
            else servicesFound.completeExceptionally(IllegalStateException("discoverServices $status"))
        }

        // API 33+
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = complete(status, value)

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            complete(status, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            complete(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            complete(status)

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onFromNum(c)

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = onFromNum(c)
    }

    private fun onFromNum(c: BluetoothGattCharacteristic) {
        if (c.uuid == MeshUuids.FROM_NUM) scope.launch {
            try {
                drainFromRadio()
            } catch (e: Exception) {
                Log.w(TAG, "Error leyendo FromRadio", e)
            }
        }
    }

    private companion object {
        const val TAG = "MeshBle"
    }
}
