package io.github.protohommer.meshpaper.mesh

import android.util.Base64
import com.google.protobuf.ByteString
import org.meshtastic.proto.AppOnlyProtos.ChannelSet
import org.meshtastic.proto.ChannelProtos.ChannelSettings
import org.meshtastic.proto.ChannelProtos.ModuleSettings
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import java.security.SecureRandom

/**
 * Grupo cerrado = ChannelSet con:
 *  - índice 0 (primario): canal privado, PSK AES-256 aleatoria, posición con precisión completa.
 *  - índice 1 (secundario): canal público LongFast (clave por defecto), SIN posición.
 *  - LoRa: región ANZ (Argentina), preset LongFast y slot fijo = el de LongFast, para que los
 *    nodos públicos sigan oyéndonos y repitiendo aunque el primario tenga otro nombre.
 *
 * Detalles confirmados en C:\mt\src\mesh\RadioInterface.cpp y meshtastic/web channelUrl.ts.
 */
object Group {
    /** Clave por defecto de Meshtastic (canal público): un solo byte 0x01. */
    private val DEFAULT_PSK: ByteString = ByteString.copyFrom(byteArrayOf(1))

    /** Nombre del canal: el firmware guarda hasta 11 caracteres. */
    const val MAX_NAME = 11

    /** Slot de frecuencia (1-based) que usa el firmware para un nombre: djb2(nombre) % slots + 1. */
    fun slotFor(name: String, numSlots: Int): Int {
        var h = 5381L
        for (b in name.toByteArray(Charsets.UTF_8)) h = (h * 33 + (b.toInt() and 0xff)) and 0xffffffffL
        return (h % numSlots).toInt() + 1
    }

    /** ANZ (915–928 MHz) con LongFast (250 kHz): 52 slots; "LongFast" → slot 20 (919,875 MHz). */
    private const val ANZ_LONGFAST_SLOTS = 52
    val ANZ_LONGFAST_SLOT: Int = slotFor("LongFast", ANZ_LONGFAST_SLOTS)

    fun create(name: String, current: LoRaConfig?): ChannelSet {
        val psk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val private = ChannelSettings.newBuilder()
            .setName(name.take(MAX_NAME))
            .setPsk(ByteString.copyFrom(psk))
            .setUplinkEnabled(false)
            .setDownlinkEnabled(false)
            .setModuleSettings(ModuleSettings.newBuilder().setPositionPrecision(32))
            .build()
        val public = ChannelSettings.newBuilder()
            .setName("") // vacío = nombre del preset ("LongFast")
            .setPsk(DEFAULT_PSK)
            .setModuleSettings(ModuleSettings.newBuilder().setPositionPrecision(0)) // 0 = nunca manda posición
            .build()
        val lora = (current?.toBuilder() ?: LoRaConfig.newBuilder().setTxEnabled(true).setHopLimit(3))
            .setRegion(LoRaConfig.RegionCode.ANZ)
            .setUsePreset(true)
            .setModemPreset(LoRaConfig.ModemPreset.LONG_FAST)
            .setChannelNum(ANZ_LONGFAST_SLOT)
            .setOverrideFrequency(0f)
            .build()
        return ChannelSet.newBuilder().addSettings(private).addSettings(public).setLoraConfig(lora).build()
    }

    /** El canal público LongFast, como lo arma [create]. */
    fun publicSettings(): ChannelSettings = ChannelSettings.newBuilder()
        .setName("").setPsk(DEFAULT_PSK)
        .setModuleSettings(ModuleSettings.newBuilder().setPositionPrecision(0))
        .build()

    fun toUrl(set: ChannelSet): String {
        val b64 = Base64.encodeToString(set.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "https://meshtastic.org/e/#$b64"
    }

    /** Acepta el link de la app oficial (con o sin ?add=true). Devuelve null si no es válido. */
    fun fromUrl(text: String): ChannelSet? {
        val t = text.trim()
        val i = t.indexOf('#')
        if (i < 0 || !t.lowercase().contains("meshtastic.org/e/")) return null
        return try {
            val bytes = Base64.decode(t.substring(i + 1), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            ChannelSet.parseFrom(bytes).takeIf { it.settingsCount > 0 }
        } catch (e: Exception) {
            null
        }
    }

    /** Un canal es "privado" si tiene una clave propia de 16 o 32 bytes. */
    fun isPrivate(s: ChannelSettings): Boolean = s.psk.size() == 16 || s.psk.size() == 32

    /**
     * Huella del grupo (nombre + clave del primario), para saber si el grupo cambió por
     * cualquier vía: esta app, la oficial, el QR o la pantalla del nodo. Solo se guarda en
     * el teléfono; no se puede sacar la clave a partir de ella.
     */
    fun fingerprint(s: ChannelSettings): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(s.name.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(s.psk.toByteArray())
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** Fusiona la LoRa del grupo con la del nodo: no tocamos potencia ni si transmite. */
    fun mergeLora(group: LoRaConfig, current: LoRaConfig?): LoRaConfig {
        val base = current?.toBuilder() ?: return group
        return base
            .setRegion(group.region)
            .setUsePreset(group.usePreset)
            .setModemPreset(group.modemPreset)
            .setBandwidth(group.bandwidth)
            .setSpreadFactor(group.spreadFactor)
            .setCodingRate(group.codingRate)
            .setChannelNum(group.channelNum)
            .setOverrideFrequency(group.overrideFrequency)
            .setHopLimit(if (group.hopLimit != 0) group.hopLimit else base.hopLimit)
            .build()
    }
}
