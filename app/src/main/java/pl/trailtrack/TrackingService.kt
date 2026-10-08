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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Foreground service nagrywający trasę przez GPS (bez Google Play Services). */
class TrackingService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private lateinit var repo: Repo

    // wszystkie operacje na bazie wykonujemy po kolei (kolejka), żeby zachować kolejność i klucze obce
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private lateinit var cues: CueEngine
    private var cueJob: Job? = null
    private var gpsLost = false

    // aktywności bez GPS (bieżnia, siłownia, basen): zegar co sekundę
    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000L)
        }
    }

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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(ch)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Ostrzeżenia o GPS", NotificationManager.IMPORTANCE_HIGH)
        )
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
        val sport = Live.state.value.sport
        // aktywności bez GPS nie wymagają zgody na lokalizację – od Androida 14 działają jako „special use”
        val fgType = if (sport.gps || Build.VERSION.SDK_INT < 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(sport), fgType)
        if (Live.state.value.recording) return
        if (sport.gps) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            } catch (e: Exception) {
                stopSelf()
                return
            }
        }
        SensorHub.connectSaved()
        if (sport.gps && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) showGpsAlert()
        val start = System.currentTimeMillis()
        flushedPoints = 0
        flushedLaps = 0
        stationary = 0
        totalDist = 0.0
        nextAutoLap = Prefs.autoLapKm * 1000.0
        gpsLost = sport.gps && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        cues = CueEngine(repo, sport)
        val prev = Live.state.value
        Live.state.value = prev.copy(
            recording = true, startTime = start,
            // bez GPS: punkt startowy (dla pływania – początek pierwszej długości)
            points = if (sport.gps) emptyList() else listOf(TrackPoint(0.0, 0.0, 0.0, start, 0.0, Terrain.ASPHALT)),
            laps = emptyList(), pause = PauseKind.NONE, needBreak = false, lengths = 0, activeMs = 0L
        )
        if (!sport.gps) {
            lastTick = start
            handler.postDelayed(ticker, 1000L)
        }
        cueJob = scope.launch {
            cues.prepare()
            Live.state.collect { cues.onState(it) }
        }
        enqueue { repo.dao.insertRide(RideEntity(id = start, sport = sport.id)) }
    }

    /** Co sekundę (tylko aktywności bez GPS): czas aktywny + punkt danych (bieżnia, siłownia). */
    private fun onTick() {
        val now = System.currentTimeMillis()
        val dtMs = now - lastTick
        lastTick = now
        val s = Live.state.value
        if (!s.recording || s.pause != PauseKind.NONE) return

        var updated = s.copy(activeMs = s.activeMs + dtMs)
        if (s.sport == Sport.TREADMILL || s.sport == Sport.STRENGTH) {
            val last = s.points.lastOrNull()
            val brk = s.needBreak && last != null
            val snap = SensorHub.snapshot()
            val speed = if (s.sport == Sport.TREADMILL) s.treadSpeedMs else 0.0
            val dtSec = if (last == null) 0.0 else (now - last.time) / 1000.0
            // wysokość syntetyczna z nachylenia bieżni: przyrost = dystans × nachylenie
            val ele = (last?.ele ?: 0.0) + if (brk || s.sport != Sport.TREADMILL) 0.0 else speed * dtSec * s.inclinePct / 100.0
            val p = TrackPoint(0.0, 0.0, ele, now, speed, Terrain.ASPHALT, brk = brk, hr = snap.hr, power = snap.power, cad = snap.cad)
            updated = updated.copy(points = s.points + p, needBreak = false)
        }
        Live.state.value = updated
        // pływanie: punkty dodaje UI (długości), więc zapisujemy je od razu
        val pending = updated.points.size - flushedPoints
        if (pending >= 15 || (s.sport == Sport.SWIMMING && pending > 0)) flush(updated)
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
        handler.removeCallbacks(ticker)
        cancelGpsAlert()
        val s = Live.state.value
        if (!s.recording) return
        cueJob?.cancel()
        cues.finish()
        Live.state.value = s.copy(
            recording = false, points = emptyList(), laps = emptyList(), pause = PauseKind.NONE, lengths = 0, activeMs = 0L
        )
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
        Sound.scheduleRelease()
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

    override fun onProviderEnabled(provider: String) {
        if (provider != LocationManager.GPS_PROVIDER) return
        cancelGpsAlert()
        if (gpsLost && Live.state.value.recording) {
            gpsLost = false
            if (Prefs.evGps) Sound.cue(Beep.OK, "GPS włączony. Nagrywanie trwa.")
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider != LocationManager.GPS_PROVIDER || !Live.state.value.recording || !Live.state.value.sport.gps) return
        // po powrocie GPS nie łączymy punktów linią przez „dziurę” i nie doliczamy jej do dystansu
        Live.state.update { if (it.recording) it.copy(needBreak = true) else it }
        gpsLost = true
        showGpsAlert()
        if (Prefs.evGps) Sound.cue(Beep.ALARM, "Uwaga! GPS wyłączony. Trasa nie jest nagrywana.", urgent = true)
    }

    private fun showGpsAlert() {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("GPS wyłączony!")
            .setContentText("Trasa NIE jest nagrywana. Dotknij, aby włączyć GPS.")
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID, n) }
    }

    private fun cancelGpsAlert() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_ID)
    }

    private fun buildNotification(sport: Sport): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("TrailTrack")
            .setContentText("Nagrywanie: ${sport.label}")
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "pl.trailtrack.START"
        const val ACTION_STOP = "pl.trailtrack.STOP"
        private const val CHANNEL = "tracking"
        private const val CHANNEL_ALERT = "gps_alert"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val STOP_SPEED = 0.8f     // m/s
        private const val RESUME_SPEED = 1.5f   // m/s
        private const val STOP_FIXES = 4        // kolejne odczyty "stoi" zanim włączy się auto-pauza
    }
}
