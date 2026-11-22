package pl.trailtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tętno w powiadomieniu po połączeniu czujnika/zegarka, gdy nic nie nagrywamy (podczas nagrywania tętno pokazuje
 * powiadomienie trasy). Widoczne na pasku i ekranie blokady; znika po rozłączeniu.
 */
object SensorNotifier {
    private const val CHANNEL = "sensor_live"
    private const val ID = 3
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var shown = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tętno z czujnika", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Bieżące tętno z połączonego pasa lub zegarka (poza nagrywaniem)"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        scope.launch {
            while (true) {
                runCatching { tick(app, nm) }
                delay(2000L)
            }
        }
    }

    /** Po zmianie ustawienia – od razu pokaż / schowaj. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { tick(app, app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) } }
    }

    private fun tick(ctx: Context, nm: NotificationManager) {
        val devs = SensorHub.devices.value.filter { it.connected }
        val peek = SensorHub.peek()
        val want = Prefs.sensorNotif && !Live.state.value.recording && devs.isNotEmpty() && peek.hr > 0
        if (!want) {
            if (shown) { nm.cancel(ID); shown = false }
            return
        }
        val src = devs.firstOrNull { it.name == peek.hrSource } ?: devs.first()
        val zone = hrZoneIndex(peek.hr, Prefs.lthr) + 1
        val extra = listOfNotNull(
            if (src.battery >= 0) "bateria ${src.battery}%" else null,
            if (peek.spo2 > 0) "SpO₂ ${peek.spo2}%" else null,
            if (peek.power > 0) "${peek.power} W" else null,
            if (peek.cad > 0) "${peek.cad} rpm" else null
        )
        val open = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setColor(0xFFD71921.toInt())
            .setContentTitle("♥ ${peek.hr} bpm  ·  strefa $zone")
            .setContentText((listOf(src.name.ifBlank { "Czujnik" }) + extra).joinToString("  ·  "))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { nm.notify(ID, n) }
        shown = true
    }
}
