package pl.trailtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

/**
 * Narastające podsumowanie trasy na potrzeby powiadomienia (bez przeliczania całej trasy co sekundę).
 * Liczy tak samo jak [computeStats]: dystans przez [segmentDist], czas ruchu wg progów aktywności.
 */
class LiveSummary {
    private var sport: Sport? = null
    private var start = -1L
    private var idx = 0
    private var smooth = Double.NaN
    private var anchor = Double.NaN

    var distM = 0.0; private set
    var movingSec = 0.0; private set
    var ascentM = 0.0; private set

    fun update(s: LiveState) {
        val pts = s.points
        if (s.sport != sport || s.startTime != start || pts.size < idx) {
            sport = s.sport; start = s.startTime; idx = 0
            distM = 0.0; movingSec = 0.0; ascentM = 0.0; smooth = Double.NaN; anchor = Double.NaN
        }
        val sp = s.sport
        val hyst = if (sp.gps) 5.0 else 3.0
        if (idx == 0 && pts.isNotEmpty()) {
            smooth = pts[0].ele; anchor = smooth; idx = 1
        }
        while (idx < pts.size) {
            val a = pts[idx - 1]
            val b = pts[idx]
            idx++
            smooth += 0.35 * (b.ele - smooth)
            if (smooth - anchor >= hyst) { ascentM += smooth - anchor; anchor = smooth }
            else if (anchor - smooth >= hyst) anchor = smooth
            val dt = (b.time - a.time) / 1000.0
            if (dt <= 0.0 || b.brk) continue
            val d = segmentDist(sp, a, b)
            distM += d
            if (sp.timeIsMoving || (d / dt > sp.minMoveSpeed && dt < sp.moveGapSec)) movingSec += dt
        }
    }
}

/**
 * Powiadomienie nagrywania widoczne także na ekranie blokady: czas trwania zapisu (co do sekundy), dystans,
 * średnia i bieżąca prędkość/tempo, przewyższenie, tętno ze strefą, moc i kadencja z czujników, okrążenia, duch.
 * Przyciski: Pauza/Wznów i Okrążenie.
 */
class LiveNotification(private val ctx: Context) {
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val summary = LiveSummary()

    init {
        // kanał musi mieć ważność ≥ DEFAULT, inaczej Android chowa „ciche” powiadomienia z ekranu blokady;
        // dźwięk i wibracje wyłączone – ma tylko być widać dane
        val ch = NotificationChannel(CHANNEL, "Nagrywanie trasy (na żywo)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Czas, dystans, prędkość i tętno podczas nagrywania – także na ekranie blokady"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }   // dawny kanał „cichy” (niewidoczny na blokadzie)
    }

    fun build(s: LiveState = Live.state.value): Notification {
        val sport = s.sport
        val now = System.currentTimeMillis()
        if (s.recording) summary.update(s)
        val elapsedSec = if (s.recording && s.startTime > 0) (now - s.startTime) / 1000.0 else 0.0
        val moving = if (sport.gps) summary.movingSec else s.activeMs / 1000.0
        val dist = summary.distM
        val last = s.points.lastOrNull()
        val avg = if (moving > 0) dist / moving else 0.0
        val cur = when {
            !s.recording || s.pause != PauseKind.NONE -> 0.0
            sport.gps -> if (last != null && now - last.time < 6000) last.speed.coerceAtLeast(0.0) else 0.0
            sport == Sport.TREADMILL -> s.treadSpeedMs
            else -> 0.0
        }
        val sens = SensorHub.peek()
        val hrTxt = if (sens.hr > 0) "${sens.hr}" else "–"
        val zone = if (sens.hr > 0) " Z${hrZoneIndex(sens.hr, Prefs.lthr) + 1}" else ""

        val timeTxt = fmtTime(elapsedSec)
        val distTxt = when {
            !sport.hasDistance -> "–"
            sport == Sport.SWIMMING -> fmtM(dist)
            else -> fmt2(dist / 1000.0) + " km"
        }
        val state = when {
            s.pause == PauseKind.MANUAL -> "PAUZA"
            s.pause == PauseKind.AUTO -> "AUTO-PAUZA"
            else -> "NAGRYWANIE"
        }

        // kafelki: rząd 1 – czas, dystans, tętno; rząd 2 – średnia, teraz, przewyższenie / czas ruchu
        val cells = listOf(
            "CZAS" to timeTxt,
            (if (sport.hasDistance) "DYSTANS" else "RUCH") to (if (sport.hasDistance) distTxt else fmtTime(moving)),
            "TĘTNO$zone" to hrTxt,
            (if (sport.pace == 0) "ŚR. KM/H" else "ŚR. TEMPO") to speedShort(sport, avg),
            (if (sport.pace == 0) "TERAZ" else "TEMPO TERAZ") to speedShort(sport, cur),
            (if (sport.gps || sport == Sport.TREADMILL) "↑ PRZEWYŻSZ." else "CZAS RUCHU") to
                (if (sport.gps || sport == Sport.TREADMILL) fmtM(summary.ascentM) else fmtTime(moving))
        )

        val extra = ArrayList<String>()
        extra.add(state)
        if (sport.gps || sport == Sport.TREADMILL) extra.add("ruch ${fmtTime(moving)}")
        if (sens.power > 0) extra.add("${sens.power} W")
        if (sens.cad > 0) extra.add("${sens.cad} rpm")
        if (sens.spo2 > 0) extra.add("SpO₂ ${sens.spo2}%")
        if (sport == Sport.SWIMMING) extra.add("długości ${s.lengths}")
        if (s.laps.isNotEmpty()) extra.add("okr. ${s.laps.size + 1}")
        s.ghost?.let { g -> if (g.status == 1) extra.add("duch " + (if (g.gapSec > 0) "−" else "+") + "${kotlin.math.abs(g.gapSec).toInt()} s") }
        val statusTxt = extra.joinToString("  ·  ")

        val small = RemoteViews(ctx.packageName, R.layout.notif_live_small)
        val big = RemoteViews(ctx.packageName, R.layout.notif_live_big)
        val ids = listOf(
            R.id.n_a_label to R.id.n_a_value, R.id.n_b_label to R.id.n_b_value, R.id.n_c_label to R.id.n_c_value,
            R.id.n_d_label to R.id.n_d_value, R.id.n_e_label to R.id.n_e_value, R.id.n_f_label to R.id.n_f_value
        )
        cells.forEachIndexed { i, (label, value) ->
            val (l, v) = ids[i]
            if (i < 3) { small.setTextViewText(l, label); small.setTextViewText(v, value) }
            big.setTextViewText(l, label)
            big.setTextViewText(v, value)
        }
        big.setTextViewText(R.id.n_status, statusTxt)

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setColor(NOTHING_RED)
            // zwykły tekst – dla ekranów bez własnego układu (zegarki, Android Auto, czytniki ekranu)
            .setContentTitle("$timeTxt  ·  $distTxt")
            .setContentText(
                listOfNotNull(
                    if (sport.hasDistance) "śr. ${speedShort(sport, avg)}" else null,
                    if (sens.hr > 0) "♥ ${sens.hr}$zone" else null,
                    if (s.pause != PauseKind.NONE) state.lowercase() else null
                ).joinToString("  ·  ")
            )
            .setSubText(sport.label)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory("workout")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (s.recording) {
            if (s.pause == PauseKind.NONE) b.addAction(R.drawable.ic_notif_pause, "Pauza", service(TrackingService.ACTION_PAUSE, 10))
            else b.addAction(R.drawable.ic_notif_play, "Wznów", service(TrackingService.ACTION_RESUME, 11))
            if (sport.gps || sport == Sport.TREADMILL) b.addAction(R.drawable.ic_notif_lap, "Okrążenie", service(TrackingService.ACTION_LAP, 12))
        }
        return b.build()
    }

    fun update() {
        runCatching { nm.notify(ID, build()) }
    }

    private fun service(action: String, req: Int): PendingIntent = PendingIntent.getService(
        ctx, req, Intent(ctx, TrackingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun speedShort(sport: Sport, ms: Double): String = when (sport.pace) {
        1 -> if (ms < 0.3) "–" else fmtPace(1000.0 / ms)
        2 -> if (ms < 0.1) "–" else fmtPace(100.0 / ms)
        else -> fmt1(ms * 3.6)
    }

    private fun fmt2(v: Double): String = String.format(java.util.Locale.getDefault(), "%.2f", v)

    companion object {
        const val CHANNEL = "tracking_live"
        private const val OLD_CHANNEL = "tracking"
        const val ID = 1
        private const val NOTHING_RED = 0xFFD71921.toInt()
    }
}
