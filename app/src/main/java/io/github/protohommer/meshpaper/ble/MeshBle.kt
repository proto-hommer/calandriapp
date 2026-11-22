package io.github.protohommer.meshpaper.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
        sessionJob = scope.launch {
            var step = "Emparejando"
            try {
                step = "Conectando"
                val g = connectWithRetry(device)

                // Emparejar sobre el enlace ya abierto, antes de cualquier lectura cifrada.
                // createBond() sin conexión previa falla en algunos teléfonos (Motorola/MediaTek).
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    step = "Emparejando"
                    _state.value = LinkState.Connecting(PAIRING_HINT)
                    pair(device)
                }

                step = "Buscando servicio"
                _state.value = LinkState.Connecting("Buscando servicio Meshtastic…")
                servicesFound = CompletableDeferred()
                g.discoverServices()
                withTimeout(15_000) { servicesFound.await() }

                // MTU alto ayuda pero no es imprescindible: si falla seguimos con el default.
                _state.value = LinkState.Connecting("Negociando MTU…")
                try {
                    op { g.requestMtu(512) }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    Log.w(TAG, "requestMtu falló, sigo con el MTU por defecto", e)
                }
                val svc = g.getService(MeshUuids.SERVICE)
                    ?: error("El dispositivo no tiene el servicio Meshtastic")
                toRadio = svc.getCharacteristic(MeshUuids.TO_RADIO)
                fromRadio = svc.getCharacteristic(MeshUuids.FROM_RADIO)
                fromNum = svc.getCharacteristic(MeshUuids.FROM_NUM)
                if (toRadio == null || fromRadio == null || fromNum == null) {
                    error("Faltan características Meshtastic")
                }

                step = "Pidiendo configuración"
                _state.value = LinkState.Connecting("Pidiendo configuración…")
                val nonce = (1..Int.MAX_VALUE).random()
                send(ToRadio.newBuilder().setWantConfigId(nonce).build())
                drainFromRadio()

                step = "Suscribiendo a FromNum"
                enableFromNumNotify()
                _state.value = LinkState.Connected
                // Por si llegó algo entre el drenado y la suscripción.
                drainFromRadio()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val why = if (e is TimeoutCancellationException) "no respondió a tiempo" else e.message
                Log.e(TAG, "Fallo en: $step", e)
                _state.value = LinkState.Failed("$step: ${why ?: e.javaClass.simpleName}")
                closeGatt()
            }
        }
    }

    /**
     * Empareja con el nodo desde la app. Android abre su diálogo del sistema para el PIN (si la app
     * está en primer plano; si no, lo manda como notificación). Espera hasta BONDED o falla.
     */
    private suspend fun pair(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val done = CompletableDeferred<Unit>()
        val t0 = System.currentTimeMillis()
        val trace = StringBuilder()
        fun mark(what: String) {
            trace.append(" → ").append(what).append(" (").append((System.currentTimeMillis() - t0) / 100 / 10.0).append("s)")
            Log.e(TAG, "pair: $what")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d?.address != device.address) return
                if (i.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                    mark("pide PIN (variante ${i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)})")
                    return
                }
                val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                mark(when (st) {
                    BluetoothDevice.BOND_BONDING -> "emparejando"
                    BluetoothDevice.BOND_BONDED -> "emparejado"
                    BluetoothDevice.BOND_NONE -> "sin emparejar"
                    else -> "estado $st"
                })
                when (st) {
                    BluetoothDevice.BOND_BONDED -> done.complete(Unit)
                    BluetoothDevice.BOND_NONE ->
                        done.completeExceptionally(IllegalStateException("falló:$trace"))
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            mark("createBond")
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                error("Android no quiso iniciar el emparejamiento")
            }
            withTimeout(90_000) { done.await() }
        } finally {
            context.unregisterReceiver(receiver)
        }
        delay(500) // dejar que el stack termine de guardar las claves
    }

    /** connectGatt con reintentos: el 133 al conectar suele ser transitorio. */
    private suspend fun connectWithRetry(device: BluetoothDevice): BluetoothGatt {
        var last: Exception? = null
        repeat(3) { attempt ->
            _state.value = LinkState.Connecting(
                if (attempt == 0) "Conectando…" else "Conectando (reintento ${attempt + 1}/3)…"
            )
            connected = CompletableDeferred()
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt = g
            try {
                withTimeout(20_000) { connected.await() }
                delay(600) // algunos stacks necesitan un respiro antes de discoverServices/MTU
                return g
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) last = e else throw e
            } catch (e: Exception) {
                last = e
            }
            closeGatt()
            delay(1_000)
        }
        throw last ?: IllegalStateException("no conectó")
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

    companion object {
        private const val TAG = "MeshBle"
        const val PAIRING_HINT = "Emparejando: poné el PIN que muestra el nodo (por defecto 123456). " +
            "Si no aparece el cuadro, mirá las notificaciones."
    }
}
