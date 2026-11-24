package io.github.protohommer.meshpaper

import android.location.Location
import com.google.protobuf.ByteString
import io.github.protohommer.meshpaper.mesh.Antenna
import io.github.protohommer.meshpaper.mesh.ChatMsg
import io.github.protohommer.meshpaper.mesh.Conv
import io.github.protohommer.meshpaper.mesh.MsgStatus
import io.github.protohommer.meshpaper.mesh.TrackPoint
import io.github.protohommer.meshpaper.mesh.Wpt
import org.meshtastic.proto.ChannelProtos.Channel
import org.meshtastic.proto.ChannelProtos.ChannelSettings
import org.meshtastic.proto.ChannelProtos.ModuleSettings
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.PositionConfig
import org.meshtastic.proto.MeshProtos

/**
 * Datos inventados para las capturas del README (solo en la variante "demo", que no se publica):
 * un grupo de expedición en el valle de Calamuchita (Córdoba). Ninguna posición es de nadie real.
 */
object Demo {
    const val ME = 0x5e0a0001
    private const val ANA = 0x5e0a0002
    private const val JUAN = 0x5e0a0003
    private const val SOFI = 0x5e0a0004
    private const val TOMI = 0x5e0a0005
    private const val BASE = 0x5e0a0006
    private const val VGB = 0x7a110001
    private const val SRC = 0x7a110002
    private const val CHM = 0x7a110003
    private const val LRT = 0x7a110004
    private const val ALP = 0x7a110005

    /** "Vos": centro de Villa General Belgrano (inventado). */
    const val MY_LAT = -31.9762
    const val MY_LON = -64.5594

    data class Data(
        val nodes: Map<Int, MeshNode>,
        val group: Set<Int>,
        val channels: Map<Int, Channel>,
        val lora: LoRaConfig,
        val owner: MeshProtos.User,
        val device: DeviceConfig,
        val position: PositionConfig,
        val messages: List<ChatMsg>,
        val tracks: Map<Int, List<TrackPoint>>,
        val waypoints: Map<Int, Wpt>,
        val antennas: Map<Int, Antenna>,
        val fix: Location,
    )

    fun build(now: Long): Data {
        fun node(
            num: Int, short: String, long: String, ago: Long, lat: Double, lon: Double, hops: Int?, snr: Float,
            group: Boolean, battery: Int? = null, role: DeviceConfig.Role = DeviceConfig.Role.CLIENT, rssi: Int? = null,
            hw: MeshProtos.HardwareModel = MeshProtos.HardwareModel.HELTEC_V3,
        ) = MeshNode(
            num = num, id = "!%08x".format(num), longName = long, shortName = short, lastHeard = now - ago,
            snr = snr, hopsAway = hops, battery = battery, lat = lat, lon = lon, channel = if (group) 0 else 1,
            rssi = rssi, mute = role == DeviceConfig.Role.CLIENT_MUTE, group = group, role = role, hw = hw, alt = null,
            precisionBits = 32,
        )
        val nodes = listOf(
            node(ME, "EX1", "Expedición 1", 5, MY_LAT, MY_LON, 0, 0f, true, battery = 88, hw = MeshProtos.HardwareModel.T_ECHO),
            node(ANA, "ANA", "Ana · cabecera", 40, -31.9195, -64.5762, 0, 8.5f, true, battery = 76, rssi = -71),
            node(JUAN, "JUAN", "Juan · sendero", 120, -31.8968, -64.7685, 2, 3.5f, true, battery = 54),
            node(SOFI, "SOFI", "Sofi · cumbre", 300, -31.9538, -64.8165, 4, -6.0f, true, battery = 31),
            node(TOMI, "TOMI", "Tomi", 2 * 3600, -32.0080, -64.6950, 3, -9.5f, true, battery = 12),
            node(BASE, "BASE", "Base Los Reartes", 90, -31.9310, -64.6230, 1, 6.0f, true, battery = 101,
                role = DeviceConfig.Role.ROUTER, hw = MeshProtos.HardwareModel.RAK4631),
            node(VGB, "VGB1", "Villa General Belgrano Rep", 60, -31.9858, -64.5655, 1, 7.0f, false,
                role = DeviceConfig.Role.ROUTER, hw = MeshProtos.HardwareModel.RAK4631),
            node(SRC, "SRC2", "Santa Rosa Mesh", 240, -32.0660, -64.5365, 2, 1.0f, false),
            node(CHM, "CHMP", "Champaquí Alto", 600, -31.9890, -64.9350, 3, -4.0f, false,
                role = DeviceConfig.Role.ROUTER),
            node(LRT, "LRT", "Los Reartes móvil", 420, -31.9205, -64.5810, 1, 2.0f, false, role = DeviceConfig.Role.CLIENT_MUTE),
            node(ALP, "ALP", "Villa Alpina", 900, -31.9470, -64.8240, 2, -2.0f, false),
        ).associateBy { it.num }

        val channels = mapOf(
            0 to Channel.newBuilder().setIndex(0).setRole(Channel.Role.PRIMARY).setSettings(
                ChannelSettings.newBuilder().setName("Sierras")
                    .setPsk(ByteString.copyFrom(ByteArray(32) { (it * 37 + 11).toByte() })) // inventada
                    .setModuleSettings(ModuleSettings.newBuilder().setPositionPrecision(32))
            ).build(),
            1 to Channel.newBuilder().setIndex(1).setRole(Channel.Role.SECONDARY).setSettings(
                ChannelSettings.newBuilder().setPsk(ByteString.copyFrom(byteArrayOf(1)))
            ).build(),
        )
        val lora = LoRaConfig.newBuilder().setRegion(LoRaConfig.RegionCode.ANZ).setUsePreset(true)
            .setModemPreset(LoRaConfig.ModemPreset.LONG_FAST).setChannelNum(20).setHopLimit(5).setTxEnabled(true).build()
        val owner = MeshProtos.User.newBuilder().setId("!%08x".format(ME)).setLongName("Expedición 1").setShortName("EX1")
            .setHwModel(MeshProtos.HardwareModel.T_ECHO).build()
        val device = DeviceConfig.newBuilder().setRole(DeviceConfig.Role.CLIENT).build()
        val position = PositionConfig.newBuilder().setPositionBroadcastSecs(300).setPositionBroadcastSmartEnabled(true).build()

        val g = Conv.Channel(0).key
        val pub = Conv.Channel(1).key
        var id = 1000
        fun msg(conv: String, from: Int, text: String, ago: Long, hops: Int?, mine: Boolean = false, status: MsgStatus = MsgStatus.RECEIVED, reply: Int? = null) =
            ChatMsg(conv, id++, from, io.github.protohommer.meshpaper.mesh.BROADCAST, if (conv == pub) 1 else 0, text, now - ago, mine, status, hops = hops, replyTo = reply)
        val m1 = msg(g, JUAN, "Llegué al puesto, sigo hacia La Cumbrecita", 50 * 60, 2)
        val messages = listOf(
            msg(g, BASE, "Base en línea. Radio OK", 3 * 3600, 1),
            msg(g, ANA, "Salimos de Los Reartes", 2 * 3600 + 600, 0),
            m1,
            msg(g, ME, "Dale, avisá cuando cruces el vado", 48 * 60, 2, mine = true, status = MsgStatus.RELAYED, reply = m1.id),
            msg(g, SOFI, "Viento fuerte arriba, bajamos en 1 h", 20 * 60, 4),
            msg(g, ANA, "Copiado", 18 * 60, 0),
            msg(pub, VGB, "Repetidor VGB1 activo, slot 20", 5 * 3600, 1),
        )

        fun line(a: Pair<Double, Double>, b: Pair<Double, Double>, n: Int, endAgo: Long, stepS: Long) =
            (0..n).map { i ->
                val t = i / n.toDouble()
                // un poco de zigzag para que parezca un camino
                val wob = if (i % 2 == 0) 0.0012 else -0.0012
                TrackPoint(a.first + (b.first - a.first) * t + wob, a.second + (b.second - a.second) * t, now - endAgo - (n - i) * stepS)
            }
        val tracks = mapOf(
            JUAN to line(-31.9310 to -64.6230, -31.8968 to -64.7685, 14, 120, 300),
            ANA to line(-31.9500 to -64.5700, -31.9195 to -64.5762, 6, 40, 240),
            SOFI to line(-31.9300 to -64.7400, -31.9538 to -64.8165, 8, 300, 400),
        )
        fun wpt(wid: Int, lat: Double, lon: Double, name: String, desc: String, icon: Int) =
            Wpt(wid, lat, lon, name, desc, icon, 0L, 0, ME, now - 3600)
        val waypoints = listOf(
            wpt(1, -31.9050, -64.7450, "Campamento", "Agua a 50 m", 0x26FA),
            wpt(2, -31.9255, -64.6980, "Vado", "Crecido después de lluvia", 0x26A0),
            wpt(3, -31.9790, -64.5560, "Auto", "Estacionado", 0x1F697),
        ).associateBy { it.id }

        val fix = Location("demo").apply {
            latitude = MY_LAT; longitude = MY_LON; accuracy = 4f; time = now * 1000
        }
        return Data(
            nodes, nodes.values.filter { it.group }.map { it.num }.toSet(), channels, lora, owner, device, position,
            messages, tracks, waypoints,
            antennas = mapOf(BASE to Antenna(gainDbi = 6.0, heightM = 12.0)),
            fix = fix,
        )
    }
}
