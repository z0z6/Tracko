package pl.trailtrack

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

// ------------------------------------------------------------------ polszczyzna dla syntezatora mowy

/** Odmiana rzeczownika po liczebniku: 1 kilometr, 2 kilometry, 5 kilometrów. */
fun plPlural(n: Long, one: String, few: String, many: String): String {
    val a = abs(n)
    val m10 = a % 10
    val m100 = a % 100
    return when {
        a == 1L -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

private val PL = Locale("pl", "PL")

/** „12 kilometrów”, „1 kilometr”, „12,5 kilometra”. */
fun spokenKm(m: Double): String {
    val km = m / 1000.0
    val r = km.roundToLong()
    return if (abs(km - r) < 0.05) "$r ${plPlural(r, "kilometr", "kilometry", "kilometrów")}"
    else String.format(PL, "%.1f kilometra", km)
}

/** „1 godzina 12 minut”, „3 minuty 20 sekund”, „45 sekund”. */
fun spokenDuration(totalSec: Double): String {
    val t = totalSec.roundToLong().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    val parts = ArrayList<String>()
    if (h > 0) parts.add("$h ${plPlural(h, "godzina", "godziny", "godzin")}")
    if (m > 0) parts.add("$m ${plPlural(m, "minuta", "minuty", "minut")}")
    if (h == 0L && (s > 0 || parts.isEmpty())) parts.add("$s ${plPlural(s, "sekunda", "sekundy", "sekund")}")
    return parts.joinToString(" ")
}

private fun spokenMeters(v: Double): String {
    val n = v.roundToLong()
    return "$n ${plPlural(n, "metr", "metry", "metrów")}"
}

/** Cel w formie do przeczytania, np. „20 kilometrów”, „45 minut w ruchu”, „500 metrów przewyższenia”. */
fun spokenGoal(type: Int, value: Double): String = when (type) {
    1 -> spokenKm(value * 1000.0)
    2 -> spokenDuration(value * 60.0) + " w ruchu"
    3 -> spokenMeters(value) + " przewyższenia"
    else -> ""
}

// ------------------------------------------------------------------ silnik zdarzeń

/**
 * Obserwuje stan nagrywania (Live) i wywołuje komunikaty: start/pauza/okrążenia, kilometry,
 * cel treningowy, rekordy, tempo względem zwykłego, zwiększony wysiłek, zjazd z trasy.
 * Działa w serwisie, więc komunikaty słychać też przy wygaszonym ekranie.
 *
 * Liczy własne, lekkie metryki przyrostowo (dystans, czas w ruchu, przewyższenie), żeby nie
 * przeliczać całej trasy co sekundę. Przewyższenie jest przybliżone (inne wygładzanie niż w
 * końcowych statystykach), więc cel „przewyższenie” może się o kilka metrów różnić od podsumowania.
 */
class CueEngine(private val repo: Repo, private val sport: Sport) {
    private var active = false
    private var idx = 0
    private var dist = 0.0
    private var moving = 0.0
    private var ascent = 0.0
    private var altEma = Double.NaN
    private var anchor = Double.NaN
    private var lastPause = PauseKind.NONE
    private var lastLaps = 0

    // kilometry / rekordy / tempo
    private var nextKm = 1
    private var movingAtKm = 0.0
    private var baselineMs = 0.0
    private var bestKmSec = 0.0
    private var maxPrevDist = 0.0
    private var distRecordDone = false
    private var lastPaceCat = 0

    // cel
    private var goalType = 0
    private var goalTarget = 0.0
    private var goalDone = false
    private var halfDone = false

    // wysiłek
    private var effortSince = 0L
    private var belowSince = 0L
    private var lastEffortAt = 0L
    private var effortArmed = true

    // trasa
    private var route: List<GeoPoint> = emptyList()
    private var offCount = 0
    private var offAnnounced = false

    /** Wczytuje historię i trasę, po czym zapowiada start. Wołać przed [onState]. */
    suspend fun prepare() {
        val h = runCatching { repo.history(sport) }.getOrNull()
        val rt = if (sport.gps && Prefs.evOffRoute && Prefs.selectedRouteId > 0)
            runCatching { repo.loadRoute(Prefs.selectedRouteId) }.getOrNull() else null
        // runCatching połyka anulowanie – sprawdzamy je jawnie (np. Stop tuż po Start)
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            baselineMs = h?.baselineSpeedMs ?: 0.0
            // „najszybszy km” w pływaniu to najlepsze 100 m – nie porównujemy z kilometrem
            bestKmSec = if (sport == Sport.SWIMMING) 0.0 else (h?.bestKmSec ?: 0.0)
            maxPrevDist = h?.maxDistanceM ?: 0.0
            route = rt?.points?.map { GeoPoint(it.lat, it.lon) } ?: emptyList()
            goalType = Prefs.goalType
            goalTarget = Prefs.goalValue.toDouble()
            if (goalTarget <= 0.0) goalType = 0
            active = true
        }
        if (Prefs.evStart) {
            val goal = if (goalType != 0) " Cel: ${spokenGoal(goalType, goalTarget)}." else ""
            Sound.cue(Beep.START, "Nagrywanie rozpoczęte.$goal")
        }
    }

    @Synchronized
    fun onState(s: LiveState) {
        if (!active || !s.recording) return

        if (s.pause != lastPause) {
            if (Prefs.evStart) {
                when {
                    s.pause == PauseKind.MANUAL -> Sound.cue(Beep.PAUSE, "Pauza")
                    // auto-pauza zdarza się często (światła, skrzyżowania) – tylko sygnał, bez gadania
                    s.pause == PauseKind.AUTO -> Sound.cue(Beep.PAUSE)
                    lastPause == PauseKind.AUTO -> Sound.cue(Beep.RESUME)
                    lastPause == PauseKind.MANUAL -> Sound.cue(Beep.RESUME, "Wznowiono")
                }
            }
            lastPause = s.pause
        }

        if (s.laps.size > lastLaps) {
            lastLaps = s.laps.size
            if (Prefs.evStart) {
                // przy okrążeniach automatycznych sam sygnał – nie dublujemy komunikatu z podsumowaniem km
                val say = if (Prefs.autoLapKm > 0) null else "Okrążenie $lastLaps"
                Sound.cue(Beep.LAP, say)
            }
        }

        val pts = s.points
        while (idx < pts.size) {
            process(pts, idx)
            idx++
        }
    }

    /** Wołać przy zakończeniu przejazdu. */
    @Synchronized
    fun finish() {
        if (!active) return
        active = false
        if (!Prefs.evStart) return
        if (dist >= 50.0) {
            Sound.cue(
                Beep.STOP,
                "Przejazd zakończony. Dystans: ${spokenKm(dist)}. Czas w ruchu: ${spokenDuration(moving)}."
            )
        } else {
            Sound.cue(Beep.STOP)
        }
    }

    // ---------------------------------------------------------------- przetwarzanie punktów

    private fun process(pts: List<TrackPoint>, i: Int) {
        val p = pts[i]
        if (i == 0) {
            altEma = p.ele
            anchor = p.ele
            return
        }
        if (p.brk) return                       // pierwszy punkt po pauzie/utracie GPS – bez odcinka
        val a = pts[i - 1]
        val dt = (p.time - a.time) / 1000.0
        if (dt <= 0.0) return
        val d = segmentDist(sport, a, p)
        dist += d
        if (sport.timeIsMoving || (d / dt > sport.minMoveSpeed && dt < sport.moveGapSec)) moving += dt

        updateAscent(p.ele)
        checkKm()
        checkGoal()
        checkDistanceRecord()
        checkEffort(p)
        if (i % 5 == 0) checkRoute(p)
    }

    private fun updateAscent(ele: Double) {
        altEma = if (altEma.isNaN()) ele else altEma * 0.8 + ele * 0.2
        if (anchor.isNaN()) anchor = altEma
        if (altEma - anchor >= 4.0) {
            ascent += altEma - anchor
            anchor = altEma
        } else if (anchor - altEma >= 4.0) {
            anchor = altEma
        }
    }

    private fun checkKm() {
        while (dist >= nextKm * 1000.0) {
            val sec = moving - movingAtKm
            movingAtKm = moving
            val k = nextKm
            nextKm++
            if (sec >= 20.0) onKm(k, sec)
        }
    }

    private fun onKm(k: Int, sec: Double) {
        val speed = 1000.0 / sec
        val phrases = ArrayList<String>()
        var beep: Beep? = null

        if (Prefs.evKm) {
            phrases.add(
                "Kilometr $k. Czas ${spokenDuration(sec)}. " +
                    "Średnia ${String.format(PL, "%.1f", speed * 3.6)} kilometra na godzinę."
            )
        }

        var recordSpoken = false
        if (Prefs.evRecord && bestKmSec > 0.0 && sec < bestKmSec - 0.5) {
            bestKmSec = sec
            recordSpoken = true
            beep = Beep.RECORD
            phrases.add("Najlepszy wynik! Najszybszy kilometr w historii: ${spokenDuration(sec)}.")
        }

        // pierwszy kilometr to rozgrzewka – nie porównujemy
        if (Prefs.evPace && baselineMs > 0.0 && k >= 2 && !recordSpoken) {
            val m = Prefs.paceMarginPct / 100.0
            val ratio = speed / baselineMs
            val cat = when {
                ratio >= 1.0 + m -> 1
                ratio <= 1.0 - m -> -1
                else -> 0
            }
            if (cat != lastPaceCat) {
                lastPaceCat = cat
                if (cat == 1) { beep = Beep.GOOD; phrases.add("Tempo lepsze niż zazwyczaj.") }
                if (cat == -1) { beep = Beep.BAD; phrases.add("Tempo słabsze niż zazwyczaj.") }
            }
        }

        if (phrases.isNotEmpty()) Sound.cue(beep, phrases.joinToString(" "))
    }

    private fun checkGoal() {
        if (goalType == 0 || goalDone) return
        val cur = when (goalType) {
            1 -> dist / 1000.0
            2 -> moving / 60.0
            else -> ascent
        }
        if (cur >= goalTarget) {
            goalDone = true
            if (Prefs.evGoal) Sound.cue(Beep.GOAL, "Cel osiągnięty. ${spokenGoal(goalType, goalTarget)}.")
        } else if (!halfDone && Prefs.evHalf && cur >= goalTarget / 2.0) {
            halfDone = true
            Sound.cue(Beep.OK, "Połowa celu.")
        }
    }

    private fun checkDistanceRecord() {
        if (distRecordDone || !Prefs.evRecord || maxPrevDist <= 0.0) return
        if (dist > maxPrevDist) {
            distRecordDone = true
            Sound.cue(Beep.RECORD, "Najlepszy wynik! Najdłuższy przejazd w historii: ${spokenKm(dist)}.")
        }
    }

    /** Wysiłek: tętno lub moc w wybranej strefie (lub wyżej) przez co najmniej 20 s. */
    private fun checkEffort(p: TrackPoint) {
        if (!Prefs.evEffort) return
        val z = Prefs.effortZone - 1
        val hrZ = if (p.hr > 0) hrZoneIndex(p.hr, Prefs.lthr) else -1
        val pwZ = if (p.power > 0) powerZoneIndex(p.power, Prefs.ftp) else -1
        val hot = hrZ >= z || pwZ >= z
        val t = p.time
        if (hot) {
            belowSince = 0L
            if (effortSince == 0L) effortSince = t
            if (effortArmed && t - effortSince >= 20_000L && t - lastEffortAt >= 90_000L) {
                effortArmed = false
                lastEffortAt = t
                val txt = if (hrZ >= z) "Zwiększony wysiłek. Tętno w strefie ${hrZ + 1}."
                else "Zwiększony wysiłek. Moc w strefie ${pwZ + 1}."
                Sound.cue(Beep.EFFORT, txt)
            }
        } else {
            effortSince = 0L
            if (belowSince == 0L) belowSince = t
            if (!effortArmed && t - belowSince >= 30_000L) effortArmed = true
        }
    }

    /** Zjazd z trasy do podążania: > 50 m przez 2 kolejne sprawdzenia (ok. 10 s). */
    private fun checkRoute(p: TrackPoint) {
        if (!Prefs.evOffRoute || route.size < 2) return
        val d = distToRoute(p.lat, p.lon)
        if (d > 50.0) {
            offCount++
            if (offCount >= 2 && !offAnnounced) {
                offAnnounced = true
                Sound.cue(Beep.ALARM, "Uwaga, zjechałeś z trasy.", urgent = true)
            }
        } else if (d < 35.0) {
            offCount = 0
            if (offAnnounced) {
                offAnnounced = false
                Sound.cue(Beep.OK, "Wróciłeś na trasę.")
            }
        }
    }

    private fun distToRoute(lat: Double, lon: Double): Double {
        val k = cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val ax = (route[i].longitude - lon) * k * 111320.0
            val ay = (route[i].latitude - lat) * 110540.0
            val bx = (route[i + 1].longitude - lon) * k * 111320.0
            val by = (route[i + 1].latitude - lat) * 110540.0
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) best = d
        }
        return best
    }
}
