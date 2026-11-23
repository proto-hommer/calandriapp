package io.github.protohommer.meshpaper.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.meshtastic.proto.MeshProtos.Waypoint
import java.io.File
import kotlin.math.roundToInt

/**
 * Waypoint de la malla (WAYPOINT_APP). Convención del firmware (meshUtils.h waypointIsActive):
 * expire 0 = no vence, 1 = "borrar", otro = epoch en que vence. Si locked_to != 0, solo ese
 * nodo puede cambiarlo o borrarlo.
 */
data class Wpt(
    val id: Int,
    val lat: Double,
    val lon: Double,
    val name: String,
    val description: String,
    val icon: Int,        // code point unicode (emoji); 0 = sin ícono
    val expire: Long,     // epoch s; 0 = no vence
    val lockedTo: Int,
    val from: Int,        // quién lo mandó (0 = desconocido)
    val time: Long,       // cuándo lo recibimos/creamos, epoch s
) {
    /**
     * El ícono como texto. El protobuf trae un solo code point, sin el selector de variante: los
     * símbolos viejos (⚠ U+26A0, ❗, ⛔, ✈…) sin U+FE0F se dibujan como letra (negra y chica), no
     * como emoji. Se lo agregamos a los del rango de símbolos; a los que ya son emoji no les cambia nada.
     */
    val iconText: String get() {
        if (icon <= 0 || !Character.isValidCodePoint(icon)) return DEFAULT_ICON_TEXT
        val s = String(Character.toChars(icon))
        return if (icon in 0x2000 until 0x1F000) s + "\uFE0F" else s
    }

    fun isActive(now: Long): Boolean = expire == 0L || (expire > 1 && expire > now)

    fun toProto(): Waypoint = Waypoint.newBuilder()
        .setId(id)
        .setLatitudeI((lat * 1e7).roundToInt())
        .setLongitudeI((lon * 1e7).roundToInt())
        .setExpire(expire.toInt())
        .setLockedTo(lockedTo)
        .setName(name)
        .setDescription(description)
        .setIcon(icon)
        .build()

    companion object {
        const val MAX_NAME = 30          // mesh.options: Waypoint.name max_size:30
        const val MAX_DESCRIPTION = 100  // mesh.options: Waypoint.description max_size:100
        const val DEFAULT_ICON = 0x1F4CD // 📍
        const val DEFAULT_ICON_TEXT = "📍"

        fun fromProto(w: Waypoint, from: Int, now: Long): Wpt? {
            if (!w.hasLatitudeI() || !w.hasLongitudeI()) return null
            return Wpt(
                id = w.id, lat = w.latitudeI / 1e7, lon = w.longitudeI / 1e7,
                name = w.name, description = w.description, icon = w.icon,
                expire = w.expire.toLong() and 0xffffffffL, lockedTo = w.lockedTo, from = from, time = now,
            )
        }

        /** Recorta a [max] bytes UTF-8 sin cortar un carácter por la mitad (nanopb usa bytes). */
        fun fitBytes(s: String, max: Int): String {
            var out = s
            while (out.toByteArray(Charsets.UTF_8).size > max) out = out.dropLast(1)
            return out
        }
    }
}

/** Waypoints de cada nodo local, en JSON en el almacenamiento de la app. */
class WaypointStore(private val dir: File) {
    private fun file(node: Int) = File(dir, "wpts_%08x.json".format(node))

    fun load(node: Int): Map<Int, Wpt> = try {
        val f = file(node)
        if (!f.exists()) emptyMap() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i -> fromJson(a.getJSONObject(i)) }.associateBy { it.id }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun save(node: Int, wpts: Map<Int, Wpt>) {
        val a = JSONArray()
        wpts.values.forEach { a.put(toJson(it)) }
        val f = file(node)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
    }

    private fun toJson(w: Wpt) = JSONObject().apply {
        put("id", w.id); put("lat", w.lat); put("lon", w.lon); put("name", w.name)
        put("desc", w.description); put("icon", w.icon); put("expire", w.expire)
        put("locked", w.lockedTo); put("from", w.from); put("time", w.time)
    }

    private fun fromJson(o: JSONObject): Wpt? = try {
        Wpt(
            id = o.getInt("id"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
            name = o.optString("name"), description = o.optString("desc"), icon = o.optInt("icon"),
            expire = o.optLong("expire"), lockedTo = o.optInt("locked"), from = o.optInt("from"),
            time = o.optLong("time"),
        )
    } catch (e: Exception) {
        null
    }
}
