package pl.trailtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service nagrywający trasę przez GPS (bez Google Play Services). */
class TrackingService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private lateinit var repo: RideRepository
    private var sinceDraft = 0

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        repo = RideRepository(applicationContext)
        val ch = NotificationChannel(CHANNEL, "Nagrywanie trasy", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        if (Live.state.value.recording) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        sinceDraft = 0
        Live.state.value = LiveState(
            recording = true,
            startTime = System.currentTimeMillis(),
            points = emptyList(),
            terrain = Live.state.value.terrain
        )
    }

    private fun finishRecording() {
        lm.removeUpdates(this)
        val s = Live.state.value
        if (s.recording && s.points.size >= 2) {
            repo.save(Ride(s.startTime, s.points))
        }
        repo.clearDraft()
        Live.state.value = s.copy(recording = false, points = emptyList())
    }

    private fun stopRecording() {
        finishRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (Live.state.value.recording) finishRecording()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        val s = Live.state.value
        if (!s.recording) return
        if (location.hasAccuracy() && location.accuracy > 25f) return
        val last = s.points.lastOrNull()
        val p = TrackPoint(
            lat = location.latitude,
            lon = location.longitude,
            ele = if (location.hasAltitude()) location.altitude else (last?.ele ?: 0.0),
            time = location.time,
            speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            terrain = s.terrain
        )
        val updated = s.copy(points = s.points + p)
        Live.state.value = updated

        // autozapis co 30 punktów, żeby nie stracić trasy po ubiciu procesu
        if (++sinceDraft >= 30) {
            sinceDraft = 0
            val ride = Ride(updated.startTime, updated.points)
            Thread { repo.saveDraft(ride) }.start()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("TrailTrack")
            .setContentText("Nagrywanie trasy…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "pl.trailtrack.START"
        const val ACTION_STOP = "pl.trailtrack.STOP"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
    }
}
