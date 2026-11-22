package io.github.protohommer.meshpaper.mesh

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un punto del rastro de un nodo. */
data class TrackPoint(val lat: Double, val lon: Double, val time: Long /* epoch s */)

/**
 * Rastro de cada nodo (por nodo local conectado), en un JSON chico en el almacenamiento de la app.
 * Solo agrega un punto si el nodo se movió o pasó un rato, para no llenar de ruido.
 */
class TrackStore(private val dir: File) {
    private fun file(node: Int) = File(dir, "tracks_%08x.json".format(node))

    fun load(node: Int): Map<Int, List<TrackPoint>> = try {
        val f = file(node)
        if (!f.exists()) emptyMap() else {
            val o = JSONObject(f.readText())
            o.keys().asSequence().mapNotNull { k ->
                val num = k.toLongOrNull()?.toInt() ?: return@mapNotNull null
                val a = o.getJSONArray(k)
                num to (0 until a.length()).map { i ->
                    val p = a.getJSONArray(i)
                    TrackPoint(p.getDouble(0), p.getDouble(1), p.getLong(2))
                }
            }.toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun save(node: Int, tracks: Map<Int, List<TrackPoint>>) {
        val o = JSONObject()
        for ((num, pts) in tracks) {
            val a = JSONArray()
            pts.forEach { a.put(JSONArray().put(it.lat).put(it.lon).put(it.time)) }
            o.put((num.toLong() and 0xffffffffL).toString(), a)
        }
        val f = file(node)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        const val MIN_MOVE_M = 15f
        const val HEARTBEAT_S = 300L
        const val MAX_POINTS = 1000
        const val MAX_AGE_S = 7 * 24 * 3600L

        /** Devuelve el rastro con el punto agregado, o null si no hacía falta agregarlo. */
        fun append(track: List<TrackPoint>, p: TrackPoint): List<TrackPoint>? {
            val last = track.lastOrNull()
            if (last != null) {
                if (p.time < last.time) return null // posición vieja que llegó tarde
                val r = FloatArray(1)
                Location.distanceBetween(last.lat, last.lon, p.lat, p.lon, r)
                if (r[0] < MIN_MOVE_M && p.time - last.time < HEARTBEAT_S) return null
            }
            val cutoff = p.time - MAX_AGE_S
            return (track + p).dropWhile { it.time < cutoff }.takeLast(MAX_POINTS)
        }
    }
}
