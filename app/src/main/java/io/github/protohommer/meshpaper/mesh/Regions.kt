package io.github.protohommer.meshpaper.mesh

import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode
import kotlin.math.roundToInt

/**
 * Bandas LoRa de cada región, como las define el firmware (RadioInterface.cpp: tabla RDEF y
 * perfiles). Sirve para mostrar slots y frecuencias, los presets válidos y la potencia máxima
 * legal en cualquier región, no solo en ANZ.
 */
object Regions {
    /** Perfil regulatorio: separación y relleno entre slots (MHz) y presets permitidos. */
    enum class Profile(val spacing: Double, val padding: Double, val presets: List<ModemPreset>) {
        STD(0.0, 0.0, listOf(
            ModemPreset.LONG_FAST, ModemPreset.LONG_MODERATE, ModemPreset.LONG_SLOW, ModemPreset.LONG_TURBO,
            ModemPreset.MEDIUM_FAST, ModemPreset.MEDIUM_SLOW, ModemPreset.MEDIUM_TURBO,
            ModemPreset.SHORT_FAST, ModemPreset.SHORT_SLOW, ModemPreset.SHORT_TURBO,
        )),
        EU868(0.0, 0.0, listOf(
            ModemPreset.LONG_FAST, ModemPreset.LONG_SLOW, ModemPreset.MEDIUM_SLOW, ModemPreset.MEDIUM_FAST,
            ModemPreset.SHORT_SLOW, ModemPreset.SHORT_FAST, ModemPreset.LONG_MODERATE,
        )),
        LITE(0.4, 0.0375, listOf(ModemPreset.LITE_FAST, ModemPreset.LITE_SLOW)),
        NARROW(0.0, 0.0104, listOf(ModemPreset.NARROW_FAST, ModemPreset.NARROW_SLOW)),
        HAM_20KHZ(0.0, 0.0022, listOf(ModemPreset.TINY_FAST, ModemPreset.TINY_SLOW)),
        HAM_100KHZ(0.0, 0.01875, listOf(ModemPreset.NARROW_FAST, ModemPreset.NARROW_SLOW)),
        UNDEF(0.0, 0.0, listOf(ModemPreset.LONG_FAST)),
    }

    /** Banda de una región: MHz de inicio y fin, potencia máxima legal (dBm) y perfil. */
    data class Band(val start: Double, val end: Double, val maxDbm: Int, val profile: Profile, val wide: Boolean = false)

    private val table: Map<String, Band> = mapOf(
        "US" to Band(902.0, 928.0, 30, Profile.STD),
        "EU_433" to Band(433.0, 434.0, 10, Profile.STD),
        "EU_868" to Band(869.4, 869.65, 27, Profile.EU868),
        "EU_866" to Band(865.6, 867.6, 27, Profile.LITE),
        "EU_N_868" to Band(869.4, 869.65, 27, Profile.NARROW),
        "CN" to Band(470.0, 510.0, 19, Profile.STD),
        "JP" to Band(920.5, 923.5, 13, Profile.STD),
        "ANZ" to Band(915.0, 928.0, 30, Profile.STD),
        "ANZ_433" to Band(433.05, 434.79, 14, Profile.STD),
        "RU" to Band(868.7, 869.2, 20, Profile.STD),
        "KR" to Band(920.0, 923.0, 23, Profile.STD),
        "TW" to Band(920.0, 925.0, 27, Profile.STD),
        "IN" to Band(865.0, 867.0, 30, Profile.STD),
        "NZ_865" to Band(864.0, 868.0, 36, Profile.STD),
        "TH" to Band(920.0, 925.0, 27, Profile.STD),
        "UA_433" to Band(433.0, 434.7, 10, Profile.STD),
        "MY_433" to Band(433.0, 435.0, 20, Profile.STD),
        "MY_919" to Band(919.0, 924.0, 27, Profile.STD),
        "SG_923" to Band(917.0, 925.0, 20, Profile.STD),
        "PH_433" to Band(433.0, 434.7, 10, Profile.STD),
        "PH_868" to Band(868.0, 869.4, 14, Profile.STD),
        "PH_915" to Band(915.0, 918.0, 24, Profile.STD),
        "KZ_433" to Band(433.075, 434.775, 10, Profile.STD),
        "KZ_863" to Band(863.0, 868.0, 30, Profile.STD),
        "NP_865" to Band(865.0, 868.0, 30, Profile.STD),
        "BR_902" to Band(902.0, 907.5, 30, Profile.STD),
        "ITU1_2M" to Band(144.0, 146.0, 30, Profile.HAM_20KHZ),
        "ITU2_2M" to Band(144.0, 148.0, 30, Profile.HAM_20KHZ),
        "ITU3_2M" to Band(144.0, 148.0, 30, Profile.HAM_20KHZ),
        "ITU2_125CM" to Band(220.0, 225.0, 30, Profile.HAM_100KHZ),
        "ITU1_70CM" to Band(430.0, 440.0, 30, Profile.HAM_100KHZ),
        "ITU2_70CM" to Band(420.0, 450.0, 30, Profile.HAM_100KHZ),
        "ITU3_70CM" to Band(430.0, 450.0, 30, Profile.HAM_100KHZ),
        "LORA_24" to Band(2400.0, 2483.5, 10, Profile.STD, wide = true),
    )

    fun band(region: RegionCode): Band? = table[region.name]

    /** Presets que el firmware acepta en la región. */
    fun presets(region: RegionCode): List<ModemPreset> = band(region)?.profile?.presets ?: Profile.STD.presets

    /** Ancho de banda (kHz) del preset; [wide] = radios de 2,4 GHz (MeshRadio.h). */
    fun bandwidthKHz(p: ModemPreset, wide: Boolean = false): Double? = when (p) {
        ModemPreset.SHORT_TURBO, ModemPreset.MEDIUM_TURBO, ModemPreset.LONG_TURBO -> if (wide) 1625.0 else 500.0
        ModemPreset.SHORT_FAST, ModemPreset.SHORT_SLOW, ModemPreset.MEDIUM_FAST,
        ModemPreset.MEDIUM_SLOW, ModemPreset.LONG_FAST -> if (wide) 812.5 else 250.0
        ModemPreset.LONG_MODERATE, ModemPreset.LONG_SLOW -> if (wide) 406.25 else 125.0
        ModemPreset.LITE_FAST, ModemPreset.LITE_SLOW -> 125.0
        ModemPreset.NARROW_FAST, ModemPreset.NARROW_SLOW -> 62.5
        ModemPreset.TINY_FAST, ModemPreset.TINY_SLOW -> 15.6
        else -> null
    }

    /** Cantidad de slots: round((fin − inicio + separación) / (separación + 2·relleno + ancho)). */
    fun slotCount(region: RegionCode, p: ModemPreset): Int? {
        val b = band(region) ?: return null
        val bw = (bandwidthKHz(p, b.wide) ?: return null) / 1000
        val width = b.profile.spacing + 2 * b.profile.padding + bw
        return ((b.end - b.start + b.profile.spacing) / width).roundToInt().takeIf { it > 0 }
    }

    /** Frecuencia central (MHz) del slot 1..N: inicio + ancho/2 + relleno + (slot−1)·ancho de slot. */
    fun frequencyMHz(region: RegionCode, p: ModemPreset, slot: Int): Double? {
        if (slot < 1) return null
        val b = band(region) ?: return null
        val bw = (bandwidthKHz(p, b.wide) ?: return null) / 1000
        val width = b.profile.spacing + 2 * b.profile.padding + bw
        return b.start + bw / 2 + b.profile.padding + (slot - 1) * width
    }
}
