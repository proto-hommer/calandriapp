package io.github.protohommer.meshpaper.mesh

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import android.view.WindowManager

/**
 * Brújula del teléfono (sensor de rotación: acelerómetro + magnetómetro + giróscopo), para saber
 * hacia dónde apunta el teléfono cuando uno está quieto o camina despacio: ahí el GPS no sabe el
 * rumbo. Da el rumbo respecto del norte verdadero (corrige la declinación magnética con la
 * última ubicación) y tiene en cuenta si la pantalla está girada.
 *
 * [azimuth] se lee sin pasar por Compose (cambia decenas de veces por segundo).
 */
class Compass(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Grados desde el norte verdadero, en sentido horario; null sin sensor o sin lecturas. */
    @Volatile var azimuth: Float? = null
        private set

    /** Lecturas poco confiables (hay que calibrar moviendo el teléfono en forma de 8). */
    @Volatile var unreliable = false
        private set

    private var declination = 0f
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm?.unregisterListener(this)
        azimuth = null
    }

    /** Declinación magnética del lugar (en Córdoba, unos 5° al oeste). */
    fun setLocation(l: Location) {
        declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Con la pantalla girada, los ejes del teléfono no son los de la pantalla.
        // (context tiene que ser la Activity: el de la aplicación no tiene pantalla.)
        @Suppress("DEPRECATION")
        val rotation = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) context.display?.rotation
            else context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        }.getOrNull() ?: Surface.ROTATION_0
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, x, y, remapped)
        SensorManager.getOrientation(remapped, orient)
        val deg = Math.toDegrees(orient[0].toDouble()).toFloat() + declination
        azimuth = (deg + 360f) % 360f
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
        unreliable = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }
}
