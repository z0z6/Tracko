package pl.trailtrack

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/** Przekłada [Location] na [RawFix]; prędkość i kierunek o słabej dokładności uznaje za nieznane. */
fun Location.toRawFix(): RawFix {
    val speedOk = hasSpeed() &&
        !(Build.VERSION.SDK_INT >= 26 && hasSpeedAccuracy() && speedAccuracyMetersPerSecond > 2.5f)
    val bearingOk = hasBearing() &&
        !(Build.VERSION.SDK_INT >= 26 && hasBearingAccuracy() && bearingAccuracyDegrees > 25f)
    return RawFix(
        lat = latitude, lon = longitude, timeMs = time,
        accM = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        speed = if (speedOk) speed.toDouble() else -1.0,
        bearingDeg = if (bearingOk) bearing.toDouble() else Double.NaN
    )
}

/** Wysokość z GPS: nad poziomem morza (MSL), gdy system ją podaje (Android 14+), inaczej wartość systemowa. */
fun Location.gpsAltitude(): Double? = when {
    Build.VERSION.SDK_INT >= 34 && hasMslAltitude() -> getMslAltitudeMeters()
    hasAltitude() -> altitude
    else -> null
}

fun Location.verticalAccM(): Double =
    if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else 0.0

/** Barometr: zbiera próbki ciśnienia i oddaje ich średnią między kolejnymi fixami GPS. */
class BaroSensor(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var sum = 0.0
    private var cnt = 0

    fun start() {
        sensor?.let { sm.registerListener(this, it, 500_000) }   // próbka co ~0,5 s
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    @Synchronized
    fun takeMean(): Double? {
        if (cnt == 0) return null
        val m = sum / cnt
        sum = 0.0
        cnt = 0
        return m
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        sum += event.values[0]
        cnt++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * „Rozgrzewka” GPS: dopóki nie nagrywamy, ale ekran nagrywania jest otwarty, nasłuchujemy lokalizacji,
 * żeby odbiornik złapał fix zanim naciśniesz Start. [acc] = bieżąca dokładność w metrach (-1 = brak fixa).
 */
object GpsWarmup {
    val acc = MutableStateFlow(-1f)

    private var lm: LocationManager? = null
    private var on = false
    private var lastFix = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val stale = object : Runnable {
        override fun run() {
            if (!on) return
            if (SystemClock.elapsedRealtime() - lastFix > 5000L) acc.value = -1f
            handler.postDelayed(this, 2000L)
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = SystemClock.elapsedRealtime()
            acc.value = if (location.hasAccuracy()) location.accuracy else 99f
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { acc.value = -1f }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (on) return
        val m = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            return
        }
        lm = m
        on = true
        acc.value = -1f
        lastFix = 0L
        handler.postDelayed(stale, 2000L)
    }

    fun stop() {
        if (!on) return
        on = false
        handler.removeCallbacks(stale)
        runCatching { lm?.removeUpdates(listener) }
        lm = null
        acc.value = -1f
    }
}
