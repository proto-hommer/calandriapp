package io.github.protohommer.meshpaper.map

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Navegación dentro de la app hacia un nodo o un waypoint.
 *
 * Con datos de rutas de la zona sigue calles y senderos (BRouter); sin datos, o mientras calcula,
 * muestra la línea recta con distancia y rumbo, que anda siempre. Recalcula si uno se sale de la
 * ruta o si el destino (un nodo) se movió, y avisa al llegar.
 */
object Nav {
    private const val TAG = "Nav"
    private const val OFF_ROUTE_M = 60.0
    private const val TARGET_MOVED_M = 80.0
    private const val ARRIVED_M = 25.0
    private const val MIN_REROUTE_MS = 20_000L

    data class Target(val nodeNum: Int?, val wptId: Int?, val name: String, val lat: Double, val lon: Double)

    sealed interface Status {
        data object Routing : Status
        data object Ok : Status
        /** Faltan los datos de rutas de estos cuadros: se navega en línea recta. */
        data class NeedData(val tiles: List<String>) : Status
        data class Downloading(val tile: String, val progress: Float) : Status
        data class Failed(val message: String) : Status
        data object Arrived : Status
    }

    data class State(
        val target: Target,
        val mode: Routing.Mode,
        val status: Status,
        val route: Routing.Route? = null,
        /** Metros que faltan (por la ruta si hay; si no, en línea recta). */
        val remainingM: Double? = null,
        val remainingS: Int? = null,
        /** Rumbo en línea recta al destino. */
        val bearing: Float? = null,
        val straightM: Double? = null,
        val offRoute: Boolean = false,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lastRouteAt = 0L
    private var lastFix: Location? = null
    private lateinit var app: Context

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nav", Context.MODE_PRIVATE)
    fun lastMode(ctx: Context): Routing.Mode =
        runCatching { Routing.Mode.valueOf(prefs(ctx).getString("mode", null) ?: "") }.getOrDefault(Routing.Mode.WALK)

    fun start(ctx: Context, target: Target, fix: Location?) {
        app = ctx.applicationContext
        lastFix = fix
        _state.value = State(target, lastMode(app), Status.Routing)
        update()
        reroute()
    }

    fun stop() {
        job?.cancel()
        _state.value = null
    }

    fun setMode(mode: Routing.Mode) {
        val s = _state.value ?: return
        prefs(app).edit().putString("mode", mode.name).apply()
        _state.value = s.copy(mode = mode, route = null, status = Status.Routing)
        reroute()
    }

    /** Nueva posición del teléfono. */
    fun onFix(fix: Location?) {
        if (fix == null || _state.value == null) return
        lastFix = fix
        update()
        val s = _state.value ?: return
        val stale = System.currentTimeMillis() - lastRouteAt > MIN_REROUTE_MS
        if (s.offRoute && stale && s.status == Status.Ok) reroute()
    }

    /** El destino (un nodo) mandó otra posición. */
    fun onTargetMoved(lat: Double, lon: Double) {
        val s = _state.value ?: return
        if (s.target.lat == lat && s.target.lon == lon) return
        val moved = dist(s.target.lat, s.target.lon, lat, lon)
        _state.value = s.copy(target = s.target.copy(lat = lat, lon = lon))
        update()
        if (moved > TARGET_MOVED_M && s.route != null && System.currentTimeMillis() - lastRouteAt > MIN_REROUTE_MS) reroute()
    }

    fun download(tiles: List<String>) {
        if (_state.value == null) return
        job?.cancel()
        job = scope.launch {
            try {
                for (t in tiles) {
                    _state.value = _state.value?.copy(status = Status.Downloading(t, 0f))
                    Routing.download(app, t) { p ->
                        scope.launch { _state.value = _state.value?.copy(status = Status.Downloading(t, p)) }
                    }
                }
                _state.value = _state.value?.copy(status = Status.Routing)
                reroute()
            } catch (e: Exception) {
                Log.e(TAG, "download", e)
                _state.value = _state.value?.copy(status = Status.Failed("No se pudo bajar: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    fun retry() = reroute()

    private fun reroute() {
        val s = _state.value ?: return
        val f = lastFix ?: run {
            _state.value = s.copy(status = Status.Failed("Esperando la ubicación del teléfono…"))
            return
        }
        job?.cancel()
        lastRouteAt = System.currentTimeMillis()
        _state.value = s.copy(status = if (s.route == null) Status.Routing else s.status)
        val t = s.target
        val mode = s.mode
        job = scope.launch {
            try {
                val r = Routing.route(app, f.latitude, f.longitude, t.lat, t.lon, mode)
                val cur = _state.value ?: return@launch
                if (cur.mode != mode) return@launch
                _state.value = cur.copy(route = r, status = Status.Ok)
            } catch (e: Routing.MissingData) {
                _state.value = _state.value?.copy(route = null, status = Status.NeedData(e.tiles))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "route", e)
                _state.value = _state.value?.copy(route = null, status = Status.Failed("Sin ruta: ${e.message ?: e.javaClass.simpleName}"))
            }
            update()
        }
    }

    /** Recalcula lo que falta, si se salió de la ruta y si llegó. */
    private fun update() {
        val s = _state.value ?: return
        val f = lastFix ?: return
        val straight = dist(f.latitude, f.longitude, s.target.lat, s.target.lon)
        val bearing = FloatArray(2).also { Location.distanceBetween(f.latitude, f.longitude, s.target.lat, s.target.lon, it) }[1]
            .let { (it + 360f) % 360f }
        // Al llegar queda "llegaste" hasta cerrar: no recalcula ni vuelve a avisar.
        if (s.status == Status.Arrived || straight <= ARRIVED_M) {
            _state.value = s.copy(status = Status.Arrived, remainingM = straight, straightM = straight, bearing = bearing, offRoute = false)
            return
        }
        val r = s.route
        var remaining = straight
        var remainingS: Int? = null
        var off = false
        if (r != null && r.points.size >= 2) {
            // Punto de la ruta más cercano y lo que falta desde ahí.
            var best = Double.MAX_VALUE
            var bi = 0
            for ((i, p) in r.points.withIndex()) {
                val d = dist(f.latitude, f.longitude, p.first, p.second)
                if (d < best) { best = d; bi = i }
            }
            var along = 0.0
            for (i in bi until r.points.size - 1) along += dist(r.points[i].first, r.points[i].second, r.points[i + 1].first, r.points[i + 1].second)
            // Si el destino se movió desde que se calculó, sumamos el tramo final en recta.
            val tail = r.points.last().let { dist(it.first, it.second, s.target.lat, s.target.lon) }
            remaining = best + along + tail
            off = best > OFF_ROUTE_M
            remainingS = if (r.distanceM > 0) (r.seconds * (remaining / r.distanceM)).toInt() else null
        } else {
            remainingS = (straight / (s.mode.kmh / 3.6)).toInt()
        }
        _state.value = s.copy(remainingM = remaining, remainingS = remainingS, bearing = bearing, straightM = straight, offRoute = off)
    }

    private fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, r)
        return r[0].toDouble()
    }
}
