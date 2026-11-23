package io.github.protohommer.meshpaper

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.protohommer.meshpaper.ble.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano mientras hay una sesión con el nodo: mantiene vivo el proceso (y con
 * él la conexión BLE, los mensajes y el envío de la posición) con la pantalla apagada o la app
 * cerrada desde recientes. No hace nada por su cuenta: todo sigue en [MeshViewModel]; solo
 * muestra el aviso fijo y se va cuando la sesión termina (Desconectar).
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vm = (application as CalandriApp).mesh
        if (intent?.action == ACTION_STOP) {
            vm.disconnect()
            stop()
            return START_NOT_STICKY
        }
        try {
            val n = Notifier.link(this, "Conectando…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.LINK_ID, n, types(this))
            else startForeground(Notifier.LINK_ID, n)
        } catch (e: Exception) {
            // Android no deja arrancarlo (por ejemplo, desde segundo plano): la app sigue andando
            // mientras esté abierta, como antes.
            Log.e(TAG, "no pudo pasar a primer plano", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) watch = scope.launch {
            combine(vm.keepAlive, vm.link, vm.nodes, vm.myNodeNum) { keep, link, nodes, me ->
                Triple(keep, link, me?.let { nodes[it] }?.let { it.shortName.ifEmpty { it.longName } })
            }.collect { (keep, link, name) ->
                if (!keep) { stop(); return@collect }
                val text = when (link) {
                    LinkState.Connected -> "Conectada a ${name ?: "tu nodo"}: recibís mensajes con la pantalla apagada"
                    is LinkState.Connecting, LinkState.NeedPin -> "Conectando con el nodo…"
                    is LinkState.Failed, LinkState.Disconnected -> "Sin enlace: reintentando…"
                }
                if (Notifier.canPost(this@MeshService)) {
                    try { NotificationManagerCompat.from(this@MeshService).notify(Notifier.LINK_ID, Notifier.link(this@MeshService, text)) }
                    catch (_: SecurityException) {}
                }
            }
        }
        // Si Android mata el proceso, no lo revive solo: la sesión se retoma al abrir la app.
        return START_NOT_STICKY
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MeshService"
        const val ACTION_STOP = "io.github.protohommer.calandriapp.STOP"

        /** Dispositivo conectado siempre; ubicación también si hay permiso (para seguir mandando la posición). */
        private fun types(ctx: Context): Int {
            if (Build.VERSION.SDK_INT < 29) return 0
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val loc = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (loc) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            return t
        }

        /** Se llama con la app en pantalla (Android no deja arrancarlo desde segundo plano). */
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java)) }
            catch (e: Exception) { Log.e(TAG, "no arrancó", e) }
        }
    }
}
