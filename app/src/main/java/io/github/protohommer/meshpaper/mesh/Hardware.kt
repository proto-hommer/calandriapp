package io.github.protohommer.meshpaper.mesh

import org.meshtastic.proto.MeshProtos.HardwareModel

/**
 * Nombre legible de un modelo de placa Meshtastic (enum HardwareModel de mesh.proto).
 * Sirve para cualquier modelo, también los que se agreguen: arma el nombre desde el enum
 * ("HELTEC_WIRELESS_TRACKER" → "Heltec Wireless Tracker") con algunas marcas bien escritas.
 */
object Hardware {
    private val special = mapOf(
        "UNSET" to "desconocida",
        "PRIVATE_HW" to "placa propia",
        "PORTDUINO" to "Linux (meshtasticd)",
        "ANDROID_SIM" to "simulador Android",
        "NRF52_UNKNOWN" to "nRF52 genérica",
    )
    private val words = mapOf(
        "HELTEC" to "Heltec", "LILYGO" to "LilyGO", "TBEAM" to "T-Beam", "TLORA" to "T-LoRa", "TDECK" to "T-Deck",
        "SEEED" to "Seeed", "XIAO" to "XIAO", "WIO" to "Wio", "RAK" to "RAK", "NRF52" to "nRF52", "NRF52840" to "nRF52840",
        "ESP32" to "ESP32", "RP2040" to "RP2040", "RPI" to "Raspberry Pi", "PICO" to "Pico", "M5STACK" to "M5Stack",
        "THINKNODE" to "ThinkNode", "WISMESH" to "WisMesh", "MESHTASTIC" to "Meshtastic", "DIY" to "DIY",
        "LORA" to "LoRa", "TFT" to "TFT", "OLED" to "OLED", "EINK" to "e-ink", "EPAPER" to "e-paper", "GPS" to "GPS",
        "USB" to "USB", "SX1262" to "SX1262", "SX1280" to "SX1280", "LR1110" to "LR1110", "LR1121" to "LR1121",
    )

    fun name(hw: HardwareModel?): String {
        if (hw == null || hw == HardwareModel.UNRECOGNIZED) return "desconocida"
        special[hw.name]?.let { return it }
        val parts = hw.name.split('_').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            // "T_ECHO" → "T-Echo", "T_WATCH" → "T-Watch" (los modelos de LilyGO)
            if (p == "T" && i + 1 < parts.size) {
                out += "T-" + word(parts[i + 1]); i += 2; continue
            }
            out += word(p); i++
        }
        return out.joinToString(" ")
    }

    private fun word(p: String): String = words[p] ?: when {
        p.any(Char::isDigit) -> p                                    // V3, RAK4631, S3, 1P6
        p.length <= 2 -> p                                           // siglas cortas
        else -> p.lowercase().replaceFirstChar(Char::uppercase)
    }
}
