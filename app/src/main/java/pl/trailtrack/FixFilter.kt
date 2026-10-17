package pl.trailtrack

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Surowy odczyt GPS bez zależności od Androida (dzięki temu filtr da się testować na zwykłej JVM).
 * [accM] <= 0 = nieznana dokładność, [speed] < 0 = nieznana/niewiarygodna prędkość, [bearingDeg] NaN = nieznany kierunek.
 */
class RawFix(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val accM: Double,
    val speed: Double,
    val bearingDeg: Double
)

class Filtered(val lat: Double, val lon: Double, val speed: Double)

/**
 * Filtr pozycji GPS: bramka dokładności, odrzucanie skoków i filtr Kalmana (położenie + dead-reckoning z prędkości
 * Dopplera). Dodatkowo „blokuje” pozycję, gdy stoimy, żeby szum GPS nie doliczał fałszywego dystansu.
 */
class FixFilter(
    private val maxAccNormal: Double = 25.0,
    private val maxAccStart: Double = 15.0,
    private val startRelaxMs: Long = 30_000L
) {
    private var has = false
    private var everHad = false
    private var firstSeenMs = 0L

    // lokalny układ współrzędnych (m) względem punktu odniesienia
    private var oLat = 0.0
    private var oLon = 0.0
    private var mLon = M_PER_DEG
    private var x = 0.0
    private var y = 0.0
    private var p = 0.0          // wariancja pozycji (m²)
    private var lastT = 0L
    private var rejects = 0

    // ostatnia pozycja oddana na zewnątrz (do blokowania szumu w bezruchu)
    private var eLat = Double.NaN
    private var eLon = Double.NaN

    /** Po pauzie / utracie GPS: następny fix inicjuje filtr od zera (nie łączymy go z poprzednim). */
    fun reset() {
        has = false
        rejects = 0
        eLat = Double.NaN
        eLon = Double.NaN
    }

    /** Bramka dokładności: ostrzejsza na starcie (pierwsze 30 s), potem standardowa. */
    fun acceptable(f: RawFix): Boolean {
        if (firstSeenMs == 0L) firstSeenMs = f.timeMs
        val acc = if (f.accM > 0) f.accM else DEFAULT_ACC
        val limit = if (everHad || f.timeMs - firstSeenMs > startRelaxMs) maxAccNormal else maxAccStart
        return acc <= limit
    }

    /** Zwraca wygładzony punkt albo null, gdy fix uznano za odstający. */
    fun filter(f: RawFix): Filtered? {
        val acc = if (f.accM > 0) f.accM else DEFAULT_ACC
        val r = max(acc, MIN_ACC).pow(2)

        if (!has) {
            oLat = f.lat
            oLon = f.lon
            mLon = M_PER_DEG * cos(Math.toRadians(f.lat))
            x = 0.0; y = 0.0; p = r
            lastT = f.timeMs
            has = true
            everHad = true
            rejects = 0
            return emit(f, acc, 0.0)
        }

        val dt = (f.timeMs - lastT) / 1000.0
        if (dt <= 0.0) return null

        val zx = (f.lon - oLon) * mLon
        val zy = (f.lat - oLat) * M_PER_DEG

        // predykcja: przy ruchu przesuwamy estymatę o v·dt wzdłuż kierunku z Dopplera
        val dead = f.speed >= 1.0 && !f.bearingDeg.isNaN() && dt <= 3.0
        var px = x
        var py = y
        val q: Double
        if (dead) {
            val b = Math.toRadians(f.bearingDeg)
            px += f.speed * dt * sin(b)
            py += f.speed * dt * cos(b)
            q = hypot(0.7, 0.17 * f.speed)       // niepewność prędkości + błąd kierunku (~10°)
        } else {
            q = if (f.speed >= 0) 0.5 + f.speed else 2.0
        }
        val pp = p + (q * dt) * (q * dt)

        // skoki: pomiar zbyt daleko od przewidywania względem niepewności
        val innov = hypot(zx - px, zy - py)
        val gate = 3.5 * sqrt(pp + r) + 3.0
        if (innov > gate && rejects < MAX_REJECTS - 1) {
            rejects++
            x = px; y = py; p = pp; lastT = f.timeMs
            return null
        }
        if (innov > gate) {
            // kilka fixów z rzędu „odstających” = to my się przesunęliśmy, nie szum: kotwiczymy na pomiarze
            x = zx; y = zy; p = r
            eLat = Double.NaN; eLon = Double.NaN
        } else {
            val k = pp / (pp + r)
            x = px + k * (zx - px)
            y = py + k * (zy - py)
            p = max((1.0 - k) * pp, MIN_VAR)
        }
        rejects = 0
        lastT = f.timeMs
        return emit(f, acc, dt)
    }

    private fun emit(f: RawFix, acc: Double, dt: Double): Filtered {
        var la = oLat + y / M_PER_DEG
        var lo = oLon + x / mLon
        val v: Double
        if (!eLat.isNaN()) {
            val d = hypot((la - eLat) * M_PER_DEG, (lo - eLon) * mLon)
            v = if (f.speed >= 0) f.speed else if (dt > 0) d / dt else 0.0
            // prędkość Dopplera ~0 = na pewno stoimy: dryf GPS do ~1,5× błędu ignorujemy; przy 0,15–0,4 m/s ostrożniej
            val radius = if (v < VERY_STILL_SPEED) max(SNAP_MIN_M, 1.5 * acc) else max(SNAP_MIN_M, 0.6 * acc)
            if (v < STILL_SPEED && d < radius) {
                la = eLat; lo = eLon              // stoimy – nie przesuwamy punktu
            } else {
                eLat = la; eLon = lo
            }
        } else {
            v = max(f.speed, 0.0)
            eLat = la; eLon = lo
        }
        return Filtered(la, lo, v)
    }

    companion object {
        private const val M_PER_DEG = 111_194.93   // metrów na stopień szerokości (R = 6371 km, jak w haversine)
        private const val DEFAULT_ACC = 15.0
        private const val MIN_ACC = 3.0
        private const val MIN_VAR = 1.0
        private const val MAX_REJECTS = 3
        private const val STILL_SPEED = 0.4
        private const val VERY_STILL_SPEED = 0.15
        private const val SNAP_MIN_M = 2.5
    }
}

/**
 * Wysokość: barometr (stabilny, ale dryfuje z pogodą) zakotwiczony wolno uśrednianą wysokością z GPS
 * (zaszumiona, ale bez dryfu). Bez barometru – tylko lekko wygładzone GPS.
 */
class AltitudeFusion {
    private var baro = Double.NaN      // wysokość z ciśnienia wg atmosfery standardowej
    private var offset = Double.NaN    // GPS − baro
    private var n = 0
    private var lastT = 0L
    private var fb = Double.NaN        // wygładzone GPS (gdy brak barometru)

    /**
     * [pressureHpa] – średnie ciśnienie od poprzedniego fixa (null = brak barometru/odczytu),
     * [gpsAlt] – wysokość z GPS (najlepiej MSL), [vaccM] – dokładność pionowa (<= 0 = nieznana).
     */
    fun update(pressureHpa: Double?, gpsAlt: Double?, vaccM: Double, timeMs: Long, prev: Double?): Double {
        val vacc = if (vaccM > 0) vaccM else 12.0
        if (pressureHpa != null && pressureHpa in 300.0..1100.0) {
            val b = 44330.0 * (1.0 - (pressureHpa / 1013.25).pow(1.0 / 5.255))
            baro = if (baro.isNaN()) b else baro + 0.5 * (b - baro)
        }
        if (!baro.isNaN()) {
            if (gpsAlt != null) {
                val m = gpsAlt - baro
                n++
                if (offset.isNaN()) {
                    offset = m
                } else {
                    val dt = ((timeMs - lastT) / 1000.0).coerceIn(0.0, 10.0)
                    val q = (8.0 / max(vacc, 8.0))   // słabe fixy pionowe liczą się mniej
                    // pierwsze ~30 próbek: średnia kroczaca (szybkie zakotwiczenie), potem stała czasowa ~90 s
                    val a = if (n <= 30) (1.0 / n) * q else (dt / 90.0) * q
                    offset += a.coerceIn(0.0, 1.0) * (m - offset)
                }
                lastT = timeMs
            }
            if (!offset.isNaN()) return baro + offset
        }
        if (gpsAlt != null) {
            val a = (6.0 / max(vacc, 6.0)).coerceIn(0.1, 1.0) * 0.3
            fb = if (fb.isNaN()) gpsAlt else fb + a * (gpsAlt - fb)
            return fb
        }
        return prev ?: 0.0
    }
}
