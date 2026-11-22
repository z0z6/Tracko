package pl.trailtrack

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometria na elipsoidzie WGS84 (tej samej, w której GPS podaje współrzędne).
 *
 * Wcześniej dystans liczyliśmy na kuli o promieniu 6371 km. W Polsce (φ ≈ 50–54°) kula zaniża odległości:
 * południkowo o ~0,07 %, równoleżnikowo o ~0,32 % – na typowej trasie to ~0,2 % (≈ 50–60 m na 27 km).
 */
object Wgs84 {
    const val A = 6_378_137.0
    const val E2 = 6.694_379_990_14e-3

    /** promień krzywizny w płaszczyźnie południka (M) dla szerokości [latRad] */
    fun meridional(latRad: Double): Double {
        val s = sin(latRad)
        val w = 1.0 - E2 * s * s
        return A * (1.0 - E2) / (w * sqrt(w))
    }

    /** promień krzywizny w pierwszym wertykale (N) dla szerokości [latRad] */
    fun primeVertical(latRad: Double): Double {
        val s = sin(latRad)
        return A / sqrt(1.0 - E2 * s * s)
    }

    /** metry na stopień szerokości / długości geograficznej w okolicy [latDeg] */
    fun mPerDegLat(latDeg: Double): Double = Math.toRadians(1.0) * meridional(Math.toRadians(latDeg))
    fun mPerDegLon(latDeg: Double): Double {
        val r = Math.toRadians(latDeg)
        return Math.toRadians(1.0) * primeVertical(r) * cos(r)
    }
}

/**
 * Odległość w metrach między dwoma punktami na elipsoidzie WGS84.
 * Do ~20 km: płaszczyzna styczna z lokalnymi promieniami krzywizny (błąd względny < 1e-6, zgodne z Vincentym
 * do milimetrów na odcinkach GPS). Dłuższe: wzór haversine z promieniem zależnym od azymutu (wzór Eulera).
 */
fun geoDist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi = Math.toRadians((lat1 + lat2) / 2.0)
    val m = Wgs84.meridional(phi)
    val n = Wgs84.primeVertical(phi)
    var dLonDeg = lon2 - lon1
    if (dLonDeg > 180.0) dLonDeg -= 360.0 else if (dLonDeg < -180.0) dLonDeg += 360.0
    val dy = Math.toRadians(lat2 - lat1) * m
    val dx = Math.toRadians(dLonDeg) * n * cos(phi)
    val d = hypot(dx, dy)
    if (d < 20_000.0 || d == 0.0) return d
    // długie odcinki: kula o promieniu krzywizny w kierunku ruchu
    val c2 = (dy / d) * (dy / d)
    val s2 = (dx / d) * (dx / d)
    val r = 1.0 / (c2 / m + s2 / n)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(dLonDeg)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Długość odcinka między dwoma kolejnymi fixami: fuzja prędkości Dopplera i pozycji.
 *
 * Prędkość z efektu Dopplera jest ~10× dokładniejsza niż różnica pozycji (±0,1–0,3 m/s), nie „ścina” zakrętów
 * między fixami i nie dolicza bocznego szumu pozycji. Dlatego przy ruchu (≥ 1 m/s) i regularnych fixach (≤ 4 s)
 * długość odcinka to średnia prędkość × czas. Pozycji używamy, gdy:
 *  - któraś prędkość jest nieznana albo niewiarygodna ([v0]/[v1] < 0),
 *  - jesteśmy prawie w miejscu (Doppler ma tu dodatnie obciążenie, a filtr i tak blokuje dryf pozycji),
 *  - była przerwa w fixach (np. tunel) – wtedy cięciwa jest najlepszym, co wiemy,
 *  - oba pomiary wyraźnie się rozjeżdżają (błąd prędkości albo skok pozycji).
 */
fun fusedSegmentDist(chordM: Double, v0: Double, v1: Double, dtSec: Double): Double {
    if (v0 < 0.0 || v1 < 0.0 || dtSec <= 0.0 || dtSec > MAX_DOPPLER_GAP_S) return chordM
    val vAvg = (v0 + v1) / 2.0
    if (vAvg < MIN_DOPPLER_SPEED) return chordM
    val dop = vAvg * dtSec
    // łuk jest zawsze ≥ cięciwy; Doppler mniejszy od cięciwy o więcej niż szum pozycji albo dużo większy = coś nie gra
    if (dop < chordM * 0.8 - 2.0 || dop > chordM * 1.5 + 3.0) return chordM
    return dop
}

/** Czy wartość [dist] zapisana w punkcie ma sens względem cięciwy (ochrona przed danymi z poprzednich wersji). */
fun plausibleStoredDist(stored: Float, chordM: Double): Boolean =
    stored >= 0f && !stored.isNaN() && abs(stored - chordM) <= chordM * 0.6 + 5.0

private const val MAX_DOPPLER_GAP_S = 4.0
private const val MIN_DOPPLER_SPEED = 1.0
