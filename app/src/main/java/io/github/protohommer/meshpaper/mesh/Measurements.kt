package io.github.protohommer.meshpaper.mesh

import android.content.Context
import io.github.protohommer.meshpaper.map.Dem
import org.json.JSONArray
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Una medición real de señal: un paquete que llegó DIRECTO (sin repetidores) desde [from], con
 * dónde estaba cada uno. Es lo que permite saber cuánto le erra el modelo de cobertura en la zona.
 *
 * [rxLat]/[rxLon]: el teléfono (el nodo conectado está al lado, a alcance de Bluetooth).
 * [txLat]/[txLon]: la última posición conocida del que transmitió; [txAgeS] = cuánto hacía que la
 * había mandado (una posición vieja de alguien que se mueve no sirve para comparar).
 */
data class Measurement(
    val time: Long,          // epoch s
    val from: Int,
    val rxLat: Double, val rxLon: Double, val rxAccM: Int,
    val txLat: Double, val txLon: Double, val txAgeS: Long,
    val rssi: Int, val snr: Float,
) {
    /**
     * Potencia de la señal (dBm). El RSSI del SX1262 es de todo el canal: con SNR negativa lo que
     * manda es el ruido, y la señal queda SNR dB por debajo (nota de aplicación de Semtech).
     */
    val signalDbm: Double get() = if (snr < 0) rssi + snr.toDouble() else rssi.toDouble()

    val distanceM: Double get() = Profile.distanceM(rxLat, rxLon, txLat, txLon)

    fun toJson(): String = JSONArray()
        .put(time).put(from.toLong() and 0xffffffffL)
        .put(rxLat).put(rxLon).put(rxAccM)
        .put(txLat).put(txLon).put(txAgeS)
        .put(rssi).put(snr.toDouble())
        .toString()

    companion object {
        fun fromJson(s: String): Measurement? = runCatching {
            val a = JSONArray(s)
            Measurement(
                a.getLong(0), a.getLong(1).toInt(),
                a.getDouble(2), a.getDouble(3), a.getInt(4),
                a.getDouble(5), a.getDouble(6), a.getLong(7),
                a.getInt(8), a.getDouble(9).toFloat(),
            )
        }.getOrNull()
    }
}

/** Las mediciones de cada nodo local, una por línea, en el almacenamiento privado de la app. */
class MeasureStore(private val dir: File) {
    private fun file(node: Int) = File(dir, "meas_%08x.jsonl".format(node))

    fun load(node: Int): List<Measurement> = runCatching {
        val f = file(node)
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { Measurement.fromJson(it) }.takeLast(MAX)
    }.getOrDefault(emptyList())

    fun append(node: Int, m: Measurement) {
        runCatching { file(node).appendText(m.toJson() + "\n") }
    }

    /** Reescribe el archivo (para recortarlo cuando crece de más). */
    fun save(node: Int, list: List<Measurement>) {
        runCatching {
            val f = file(node)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(list.joinToString("") { it.toJson() + "\n" })
            tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
        }
    }

    fun clear(node: Int) { file(node).delete() }

    companion object {
        const val MAX = 20_000
        /** Una por nodo cada tanto: un nodo que manda seguido no tiene que pesar más que el resto. */
        const val MIN_GAP_S = 20L
        /** Posición del teléfono: como mucho así de vieja y así de imprecisa. */
        const val MAX_FIX_AGE_S = 60L
        const val MAX_FIX_ACC_M = 50
        /** Para comparar con el modelo: la posición del que transmitió, como mucho así de vieja. */
        const val MAX_TX_AGE_S = 15 * 60L
        /** Más cerca que esto el relieve no dice nada (y el GPS erra más que la distancia). */
        const val MIN_DIST_M = 150.0
    }
}

/**
 * Lo medido contra lo que predice el perfil (relieve + Deygout, como la pantalla Perfil).
 * error = medido − predicho: positivo = el modelo es pesimista; negativo = promete de más.
 */
object ModelCheck {
    data class Sample(val m: Measurement, val predictedDbm: Double) {
        val errorDb: Double get() = m.signalDbm - predictedDbm
    }

    data class Report(
        val samples: List<Sample>,
        val skipped: Int,          // sin relieve, muy cerca o con posición vieja
        val meanDb: Double,        // sesgo
        val stdDb: Double,         // dispersión
        val byDistance: List<Bin>, // por tramo de distancia
    )

    data class Bin(val label: String, val n: Int, val meanDb: Double)

    /**
     * Bloquea (lee el relieve): fuera del hilo principal. [antennas]: las cargadas a mano;
     * sin antena se asume una de mano (2 dBi a 1,5 m, 22 dBm). [me]: el nodo local (el que recibe).
     * Se usan como mucho [limit] mediciones, las más nuevas, una por lugar y nodo.
     */
    fun run(
        ctx: Context, me: Int, list: List<Measurement>, antennas: Map<Int, Antenna>, freqMHz: Double,
        network: Boolean, limit: Int = 400, progress: (Int, Int) -> Unit = { _, _ -> },
    ): Report {
        val handheld = Antenna(Range.RX_GAIN_DBI, Range.RX_HEIGHT_M)
        // Una por nodo y lugar (~100 m): mil paquetes desde el mismo lugar son una sola medición.
        val picked = list.asReversed()
            .filter { it.txAgeS <= MeasureStore.MAX_TX_AGE_S && it.distanceM >= MeasureStore.MIN_DIST_M }
            .distinctBy { Triple(it.from, (it.rxLat * 1000).roundToInt(), (it.rxLon * 1000).roundToInt()) }
            .take(limit)
        var skipped = list.size - picked.size
        val out = ArrayList<Sample>(picked.size)
        picked.forEachIndexed { i, m ->
            progress(i, picked.size)
            val rx = Profile.End(m.rxLat, m.rxLon, antennas[me] ?: handheld)
            val tx = Profile.End(m.txLat, m.txLon, antennas[m.from] ?: handheld)
            val pts = Profile.samplePoints(tx, rx)
            val ground = ArrayList<Double>(pts.size)
            for ((la, lo) in pts) ground += Dem.elevation(ctx, la, lo, network) ?: run { skipped++; return@forEachIndexed }
            val loss = Profile.pathLossDb(tx, rx, ground, freqMHz)
            val predicted = tx.antenna.txDbm + tx.antenna.gainDbi - tx.antenna.cableDb +
                rx.antenna.gainDbi - rx.antenna.cableDb - loss
            out += Sample(m, predicted)
        }
        progress(picked.size, picked.size)
        val errs = out.map { it.errorDb }
        val mean = if (errs.isEmpty()) 0.0 else errs.average()
        val std = if (errs.size < 2) 0.0 else sqrt(errs.sumOf { (it - mean) * (it - mean) } / (errs.size - 1))
        val bins = listOf(0.0 to 1000.0, 1000.0 to 3000.0, 3000.0 to 10_000.0, 10_000.0 to Double.MAX_VALUE)
            .mapNotNull { (a, b) ->
                val s = out.filter { it.m.distanceM >= a && it.m.distanceM < b }
                if (s.isEmpty()) null else Bin(binLabel(a, b), s.size, s.map { it.errorDb }.average())
            }
        return Report(out, max(0, skipped), mean, std, bins)
    }

    private fun binLabel(a: Double, b: Double): String = when {
        b == Double.MAX_VALUE -> "más de ${(a / 1000).toInt()} km"
        a < 1000 -> "hasta ${(b / 1000).toInt()} km"
        else -> "${(a / 1000).toInt()} a ${(b / 1000).toInt()} km"
    }
}
