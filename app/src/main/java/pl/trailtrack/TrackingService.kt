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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Foreground service nagrywający trasę przez GPS (bez Google Play Services). */
class TrackingService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private lateinit var repo: Repo

    // wszystkie operacje na bazie wykonujemy po kolei (kolejka), żeby zachować kolejność i klucze obce
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private var flushedPoints = 0
    private var flushedLaps = 0
    private var stationary = 0
    private var totalDist = 0.0
    private var nextAutoLap = 0.0

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        repo = Repo(applicationContext)
        val ch = NotificationChannel(CHANNEL, "Nagrywanie trasy", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        scope.launch { for (op in ops) runCatching { op() } }
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

    private fun enqueue(op: suspend () -> Unit) {
        ops.trySend(op)
    }

    private fun startRecording() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        if (Live.state.value.recording) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        SensorHub.connectSaved()
        val start = System.currentTimeMillis()
        flushedPoints = 0
        flushedLaps = 0
        stationary = 0
        totalDist = 0.0
        nextAutoLap = Prefs.autoLapKm * 1000.0
        Live.state.value = LiveState(recording = true, startTime = start, terrain = Live.state.value.terrain)
        enqueue { repo.dao.insertRide(RideEntity(id = start)) }
    }

    private fun flush(s: LiveState) {
        val from = flushedPoints
        val pts = s.points.subList(from, s.points.size).toList()
        val laps = s.laps.drop(flushedLaps)
        flushedPoints = s.points.size
        flushedLaps = s.laps.size
        val id = s.startTime
        if (pts.isEmpty() && laps.isEmpty()) return
        enqueue {
            if (pts.isNotEmpty()) repo.dao.insertPoints(pts.mapIndexed { i, p -> p.toEntity(id, from + i) })
            if (laps.isNotEmpty()) repo.dao.insertLaps(laps.map { LapEntity(rideId = id, pointIdx = it) })
        }
    }

    private fun finish() {
        lm.removeUpdates(this)
        val s = Live.state.value
        if (!s.recording) return
        Live.state.value = s.copy(recording = false, points = emptyList(), laps = emptyList(), pause = PauseKind.NONE)
        flush(s)
        enqueue {
            if (s.points.size >= 2) repo.finalizeRide(s.startTime, s.points, s.laps)
            else repo.dao.deleteRide(s.startTime)
            if (!Live.state.value.recording) stopSelf()
        }
    }

    private fun stopRecording() {
        val was = Live.state.value.recording
        finish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!was) stopSelf()
    }

    override fun onDestroy() {
        if (Live.state.value.recording) finish()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        var s = Live.state.value
        if (!s.recording) return
        if (location.hasAccuracy() && location.accuracy > 25f) return
        if (s.pause == PauseKind.MANUAL) return

        val sp = if (location.hasSpeed()) location.speed else -1f
        if (!Prefs.autoPause) {
            if (s.pause == PauseKind.AUTO) s = s.copy(pause = PauseKind.NONE, needBreak = true)
            stationary = 0
        } else if (sp >= 0f) {
            if (s.pause == PauseKind.AUTO) {
                if (sp >= RESUME_SPEED) {
                    s = s.copy(pause = PauseKind.NONE, needBreak = true)
                    stationary = 0
                } else {
                    return
                }
            } else if (sp < STOP_SPEED) {
                stationary++
                if (stationary >= STOP_FIXES) {
                    Live.state.value = s.copy(pause = PauseKind.AUTO)
                    return
                }
            } else {
                stationary = 0
            }
        }

        val last = s.points.lastOrNull()
        val brk = s.needBreak && last != null
        val snap = SensorHub.snapshot()
        val p = TrackPoint(
            lat = location.latitude,
            lon = location.longitude,
            ele = if (location.hasAltitude()) location.altitude else (last?.ele ?: 0.0),
            time = location.time,
            speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            terrain = s.terrain,
            brk = brk,
            hr = snap.hr,
            power = snap.power,
            cad = snap.cad
        )
        if (!brk && last != null) totalDist += haversine(last.lat, last.lon, p.lat, p.lon)

        var laps = s.laps
        if (nextAutoLap > 0 && last != null && totalDist >= nextAutoLap) {
            laps = laps + s.points.size
            nextAutoLap += Prefs.autoLapKm * 1000.0
        }

        val updated = s.copy(points = s.points + p, laps = laps, needBreak = false)
        Live.state.value = updated
        if (updated.points.size - flushedPoints >= 15) flush(updated)
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
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "pl.trailtrack.START"
        const val ACTION_STOP = "pl.trailtrack.STOP"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
        private const val STOP_SPEED = 0.8f     // m/s
        private const val RESUME_SPEED = 1.5f   // m/s
        private const val STOP_FIXES = 4        // kolejne odczyty "stoi" zanim włączy się auto-pauza
    }
}
