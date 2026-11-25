package io.github.protohommer.meshpaper.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.protobuf.ByteString
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.MeshProtos.Position
import org.meshtastic.proto.Portnums.PortNum
import kotlin.math.roundToInt

/**
 * Ubicación del teléfono con el LocationManager de Android (sin Google Play Services).
 * Escucha GPS y, si están, red y "fused" de AOSP; se queda con el mejor fix reciente.
 */
@SuppressLint("MissingPermission") // el permiso se chequea en la UI antes de llamar a start()
class PhoneLocation(context: Context, private val onFix: (Location) -> Unit) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var best: Location? = null
    private var running = false
    /** Cada cuánto pedir posición: 5 s normalmente, 1 s mientras se navega guiado. */
    private var intervalMs = 5_000L

    /** Posición cada segundo (navegación guiada) o cada 5 s. Si ya estaba escuchando, se re-registra. */
    fun setFast(fast: Boolean) {
        val ms = if (fast) 1_000L else 5_000L
        if (ms == intervalMs) return
        intervalMs = ms
        if (running) { stop(); start() }
    }

    private val listener = LocationListener { loc ->
        val b = best
        // Preferir el más nuevo salvo que sea mucho peor que uno de hace menos de 30 s.
        if (b == null || loc.time - b.time > 30_000 || loc.accuracy <= b.accuracy * 1.5f) {
            best = loc
            onFix(loc)
        }
    }

    fun start() {
        if (running || lm == null) return
        running = true
        val all = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // Navegando, solo el GPS: mezclado con la red, la posición salta de una a otra.
        val providers = if (intervalMs < 5_000L && LocationManager.GPS_PROVIDER in all) listOf(LocationManager.GPS_PROVIDER) else all
        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper()) }
        }
        // Arrancar con lo último conocido para no esperar el primer fix.
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
            .minByOrNull { it.accuracy }
            ?.let(listener::onLocationChanged)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { lm?.removeUpdates(listener) }
    }

    companion object {
        /** Paquete POSITION_APP para el propio nodo: lo guarda como su posición y la difunde él. */
        fun toPacket(loc: Location, myNodeNum: Int, id: Int): MeshProtos.MeshPacket {
            val pos = Position.newBuilder()
                .setLatitudeI((loc.latitude * 1e7).roundToInt())
                .setLongitudeI((loc.longitude * 1e7).roundToInt())
                .setTime((System.currentTimeMillis() / 1000).toInt())
                .setTimestamp((loc.time / 1000).toInt())
                .setLocationSource(Position.LocSource.LOC_EXTERNAL)
                .apply {
                    // `altitude` es sobre el nivel del mar; Android da la elipsoidal (HAE), que según
                    // la zona difiere hasta en decenas de metros. MSL solo si Android la sabe (14+).
                    if (android.os.Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) {
                        setAltitude(loc.mslAltitudeMeters.roundToInt())
                    }
                    if (loc.hasAltitude()) setAltitudeHae(loc.altitude.roundToInt())
                }
                .build()
            return MeshProtos.MeshPacket.newBuilder()
                .setTo(myNodeNum)
                .setId(id)
                .setChannel(0)
                .setPriority(MeshProtos.MeshPacket.Priority.BACKGROUND)
                .setDecoded(
                    MeshProtos.Data.newBuilder()
                        .setPortnum(PortNum.POSITION_APP)
                        .setPayload(ByteString.copyFrom(pos.toByteArray()))
                )
                .build()
        }

        /** Distancia en metros y rumbo (0–360) de a hacia b. */
        fun distanceAndBearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Pair<Float, Float> {
            val r = FloatArray(2)
            Location.distanceBetween(aLat, aLon, bLat, bLon, r)
            return r[0] to ((r[1] + 360f) % 360f)
        }

        fun cardinal(bearing: Float): String =
            listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(((bearing + 22.5f) % 360f) / 45f).toInt()]

        fun formatDistance(m: Float): String =
            if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000f).replace('.', ',')
    }
}
