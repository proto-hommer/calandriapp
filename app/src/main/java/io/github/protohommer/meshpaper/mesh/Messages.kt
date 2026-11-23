package io.github.protohommer.meshpaper.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.meshtastic.proto.MeshProtos.Routing
import java.io.File

/** Dirección de broadcast de Meshtastic (0xFFFFFFFF). */
const val BROADCAST: Int = -1

/** Tope de texto: el payload útil de un paquete es 233 bytes; dejamos margen. */
const val MAX_TEXT_BYTES = 200

enum class MsgStatus {
    RECEIVED,
    SENDING,    // en el nodo, esperando ACK
    RELAYED,    // ACK implícito: otro nodo lo repitió (para broadcast es lo máximo que se sabe)
    DELIVERED,  // ACK real del destinatario (solo DM)
    FAILED,
}

/** Conversación: un canal (broadcast por índice local) o un DM con un nodo. */
sealed interface Conv {
    val key: String

    data class Channel(val index: Int) : Conv {
        override val key get() = "ch:$index"
    }

    data class Direct(val num: Int) : Conv {
        override val key get() = "dm:$num"
    }

    companion object {
        fun parse(key: String): Conv? {
            val (kind, v) = key.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
            val n = v.toIntOrNull() ?: return null
            return when (kind) {
                "ch" -> Channel(n)
                "dm" -> Direct(n)
                else -> null
            }
        }
    }
}

data class ChatMsg(
    val conv: String,
    val id: Int,
    val from: Int,
    val to: Int,
    val channel: Int,
    val text: String,
    val time: Long,        // epoch s
    val mine: Boolean,
    val status: MsgStatus,
    val error: String? = null,
    val attempt: Int = 1,
    /**
     * Saltos (repetidores) que dio: para los recibidos, los del mensaje; para los propios
     * entregados, los del ACK que volvió del destinatario. null = no se sabe (firmware viejo).
     */
    val hops: Int? = null,
    /** Id del paquete al que responde (Data.reply_id), o null si no es una respuesta. */
    val replyTo: Int? = null,
)

/** Saltos de un paquete según su hop_start y hop_limit (null si el firmware no manda hop_start). */
fun hopsOf(hopStart: Int, hopLimit: Int): Int? =
    if (hopStart != 0 && hopStart >= hopLimit) hopStart - hopLimit else null

/** "directo", "1 salto", "3 saltos". */
fun hopsText(h: Int): String = when (h) {
    0 -> "directo"
    1 -> "1 salto"
    else -> "$h saltos"
}

/** Texto corto en castellano para cada error de ruteo. */
fun routingErrorText(e: Routing.Error): String = when (e) {
    Routing.Error.MAX_RETRANSMIT -> "nadie lo recibió"
    Routing.Error.NO_ROUTE -> "sin ruta al destino"
    Routing.Error.GOT_NAK -> "lo rechazaron"
    Routing.Error.TIMEOUT -> "tiempo agotado"
    Routing.Error.NO_CHANNEL -> "el destino no tiene este canal"
    Routing.Error.TOO_LARGE -> "mensaje demasiado largo"
    Routing.Error.DUTY_CYCLE_LIMIT -> "límite de uso del aire, esperá un rato"
    Routing.Error.RATE_LIMIT_EXCEEDED -> "demasiados mensajes seguidos"
    Routing.Error.PKI_FAILED, Routing.Error.PKI_UNKNOWN_PUBKEY, Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY ->
        "falta la clave pública del destino"
    else -> e.name.lowercase()
}

/** Guarda los mensajes de cada nodo local en un JSON chico en el almacenamiento de la app. */
class MessageStore(private val dir: File) {
    private fun file(node: Int) = File(dir, "msgs_%08x.json".format(node))

    fun load(node: Int): List<ChatMsg> = try {
        val f = file(node)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i -> fromJson(a.getJSONObject(i)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(node: Int, msgs: List<ChatMsg>) {
        val a = JSONArray()
        msgs.takeLast(MAX_KEPT).forEach { a.put(toJson(it)) }
        val f = file(node)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
    }

    private fun toJson(m: ChatMsg) = JSONObject().apply {
        put("conv", m.conv); put("id", m.id); put("from", m.from); put("to", m.to)
        put("ch", m.channel); put("text", m.text); put("time", m.time); put("mine", m.mine)
        // Al recargar, lo que quedó "enviando" ya no va a recibir ACK.
        val st = if (m.status == MsgStatus.SENDING) MsgStatus.FAILED else m.status
        put("status", st.name)
        (if (m.status == MsgStatus.SENDING) "sin confirmación" else m.error)?.let { put("error", it) }
        put("attempt", m.attempt)
        m.hops?.let { put("hops", it) }
        m.replyTo?.let { put("reply", it) }
    }

    private fun fromJson(o: JSONObject): ChatMsg? = try {
        ChatMsg(
            conv = o.getString("conv"), id = o.getInt("id"), from = o.getInt("from"), to = o.getInt("to"),
            channel = o.getInt("ch"), text = o.getString("text"), time = o.getLong("time"),
            mine = o.getBoolean("mine"), status = MsgStatus.valueOf(o.getString("status")),
            error = o.optString("error").ifEmpty { null }, attempt = o.optInt("attempt", 1),
            hops = if (o.has("hops")) o.getInt("hops") else null,
            replyTo = if (o.has("reply")) o.getInt("reply") else null,
        )
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_KEPT = 1000
    }
}
