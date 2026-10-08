package pl.trailtrack

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Split(
    val label: String,
    val distanceM: Double,
    val movingSec: Double,
    val activeSec: Double,
    val ascentM: Double,
    val descentM: Double
) {
    val avgSpeedMs: Double get() = if (movingSec > 0) distanceM / movingSec else 0.0
}

/** Zdownsamplowane dane do wykresów. */
class Series(
    val distKm: FloatArray,
    val ele: FloatArray,
    val speedKmh: FloatArray,
    val colors: IntArray,
    val hr: FloatArray,
    val power: FloatArray,
    val cad: FloatArray,
    /** jednostka osi X: "km" (dystans) albo "min" (aktywności bez dystansu) */
    val xUnit: String = "km"
)

data class RideStats(
    val distanceM: Double,
    val movingSec: Double,
    val elapsedSec: Double,
    val pausedSec: Double,
    val stoppedSec: Double,
    val ascentM: Double,
    val descentM: Double,
    val maxSpeedMs: Double,
    val minEle: Double,
    val maxEle: Double,
    val maxGradePct: Double,
    val minGradePct: Double,
    val kcal: Double,
    val bestKmSec: Double,
    val terrainDist: Map<Terrain, Double>,
    val terrainTime: Map<Terrain, Double>,
    val terrainMaxSpeed: Map<Terrain, Double>,
    val splits: List<Split>,
    val laps: List<Split>,
    val series: Series
) {
    val avgSpeedMs: Double get() = if (movingSec > 0) distanceM / movingSec else 0.0
    val ascentPerKm: Double get() = if (distanceM > 0) ascentM / (distanceM / 1000.0) else 0.0
}

fun emptyStats() = RideStats(
    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(),
    Series(FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
)

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

private fun avgField(points: List<TrackPoint>, i: Int, r: Int, sel: (TrackPoint) -> Int): Float {
    val lo = maxOf(0, i - r)
    val hi = minOf(points.size - 1, i + r)
    var s = 0.0
    for (j in lo..hi) s += sel(points[j])
    return (s / (hi - lo + 1)).toFloat()
}

/** MET dla jazdy rowerem wg przedziałów prędkości (Compendium of Physical Activities). */
private fun metFor(vMs: Double): Double {
    val k = vMs * 3.6
    return when {
        k < 16.0 -> 4.0
        k < 19.2 -> 6.8
        k < 22.4 -> 8.0
        k < 25.6 -> 10.0
        k < 30.6 -> 12.0
        else -> 15.8
    }
}

/** MET biegu wg prędkości (km/h), Compendium of Physical Activities – uproszczone. */
private fun runMet(k: Double): Double = when {
    k < 6.4 -> 4.5
    k < 8.0 -> 7.0
    k < 9.7 -> 9.0
    k < 11.3 -> 10.5
    k < 12.9 -> 11.8
    k < 14.5 -> 12.8
    else -> 14.5
}

private fun metFor(sport: Sport, vMs: Double): Double = when (sport) {
    Sport.CYCLING -> metFor(vMs)
    Sport.RUNNING, Sport.TREADMILL -> runMet(vMs * 3.6)
    Sport.SWIMMING -> when {
        vMs < 0.7 -> 5.8
        vMs < 1.1 -> 8.3
        else -> 9.8
    }
    Sport.STRENGTH -> 5.0
    Sport.WALKING -> {
        val k = vMs * 3.6
        when {
            k < 3.2 -> 2.5
            k < 4.8 -> 3.5
            k < 5.6 -> 4.3
            k < 6.4 -> 5.0
            else -> 7.0
        }
    }
    Sport.KAYAKING -> {
        val k = vMs * 3.6
        when {
            k < 4.0 -> 3.0
            k < 6.5 -> 5.0
            k < 9.0 -> 6.5
            else -> 8.0
        }
    }
    Sport.INLINE -> {
        val k = vMs * 3.6
        when {
            k < 10.0 -> 6.0
            k < 16.0 -> 7.5
            k < 22.0 -> 9.8
            else -> 12.5
        }
    }
    Sport.SKIING -> {
        val k = vMs * 3.6
        when {
            k < 6.4 -> 6.8
            k < 7.9 -> 9.0
            k < 12.7 -> 12.5
            else -> 15.0
        }
    }
}

/**
 * Dystans odcinka a→b. GPS: haversine; bez GPS: prędkość × czas (bieżnia, basen).
 * Pierwszy punkt po pauzie (brk) nie dolicza dystansu.
 */
fun segmentDist(sport: Sport, a: TrackPoint, b: TrackPoint): Double = when {
    b.brk -> 0.0
    sport.gps -> haversine(a.lat, a.lon, b.lat, b.lon)
    else -> b.speed * ((b.time - a.time) / 1000.0).coerceAtLeast(0.0)
}

fun computeStats(
    points: List<TrackPoint>, weightKg: Double = 75.0, laps: List<Int> = emptyList(),
    sport: Sport = Sport.CYCLING
): RideStats {
    val n = points.size
    if (n < 2) return emptyStats()

    // wygładzona wysokość (okno ±2)
    val sm = DoubleArray(n)
    for (i in 0 until n) {
        val lo = maxOf(0, i - 2)
        val hi = minOf(n - 1, i + 2)
        var s = 0.0
        for (j in lo..hi) s += points[j].ele
        sm[i] = s / (hi - lo + 1)
    }

    val cumDist = DoubleArray(n)
    val cumActive = DoubleArray(n)
    val cumMoving = DoubleArray(n)
    val cumAsc = DoubleArray(n)
    val cumDesc = DoubleArray(n)
    val segSpeed = DoubleArray(n)

    val tDist = LinkedHashMap<Terrain, Double>()
    val tTime = LinkedHashMap<Terrain, Double>()
    val tMax = LinkedHashMap<Terrain, Double>()

    var paused = 0.0
    var maxV = 0.0
    var kcal = 0.0
    var anchor = sm[0]
    var asc = 0.0
    var desc = 0.0

    val splits = ArrayList<Split>()
    var nextKm = sport.splitM
    var pDist = 0.0
    var pActive = 0.0
    var pMoving = 0.0
    var pAsc = 0.0
    var pDesc = 0.0

    for (i in 1 until n) {
        cumDist[i] = cumDist[i - 1]
        cumActive[i] = cumActive[i - 1]
        cumMoving[i] = cumMoving[i - 1]

        // podjazdy/zjazdy z histerezą 3 m (filtr szumu GPS)
        val e = sm[i]
        if (e - anchor >= 3.0) { asc += e - anchor; anchor = e }
        else if (anchor - e >= 3.0) { desc += anchor - e; anchor = e }
        cumAsc[i] = asc
        cumDesc[i] = desc

        val a = points[i - 1]
        val b = points[i]
        val dt = (b.time - a.time) / 1000.0
        if (dt <= 0.0) continue
        if (b.brk) { paused += dt; continue }

        val d = segmentDist(sport, a, b)
        val v = d / dt
        cumDist[i] += d
        cumActive[i] += dt
        tDist[b.terrain] = (tDist[b.terrain] ?: 0.0) + d

        if (sport.timeIsMoving || (v > sport.minMoveSpeed && dt < sport.moveGapSec)) {
            cumMoving[i] += dt
            tTime[b.terrain] = (tTime[b.terrain] ?: 0.0) + dt
            segSpeed[i] = v
            kcal += metFor(sport, v) * b.terrain.effort * weightKg * dt / 3600.0
        }
        val vm = if (b.speed > 0.0) b.speed else v
        if (vm < 30.0) {
            if (vm > maxV) maxV = vm
            if (vm > (tMax[b.terrain] ?: 0.0)) tMax[b.terrain] = vm
        }

        // podziały co 1 km
        while (cumDist[i] >= nextKm && cumDist[i] > cumDist[i - 1]) {
            val f = (nextKm - cumDist[i - 1]) / (cumDist[i] - cumDist[i - 1])
            val bAct = cumActive[i - 1] + f * (cumActive[i] - cumActive[i - 1])
            val bMov = cumMoving[i - 1] + f * (cumMoving[i] - cumMoving[i - 1])
            val bAsc = cumAsc[i - 1] + f * (cumAsc[i] - cumAsc[i - 1])
            val bDesc = cumDesc[i - 1] + f * (cumDesc[i] - cumDesc[i - 1])
            splits.add(
                Split(
                    if (sport.splitM >= 1000.0) "km ${(nextKm / 1000.0).toInt()}" else "${nextKm.toInt()} m",
                    nextKm - pDist, bMov - pMoving, bAct - pActive, bAsc - pAsc, bDesc - pDesc)
            )
            pDist = nextKm; pActive = bAct; pMoving = bMov; pAsc = bAsc; pDesc = bDesc
            nextKm += sport.splitM
        }
    }

    val totalDist = cumDist[n - 1]
    if (totalDist - pDist >= sport.splitM / 10.0) {
        splits.add(
            Split(
                if (sport.splitM >= 1000.0) String.format("+%.1f km", (totalDist - pDist) / 1000.0)
                else String.format("+%.0f m", totalDist - pDist),
                totalDist - pDist, cumMoving[n - 1] - pMoving, cumActive[n - 1] - pActive,
                cumAsc[n - 1] - pAsc, cumDesc[n - 1] - pDesc
            )
        )
    }

    // maks. nachylenie (okno >= 50 m)
    var maxG = 0.0
    var minG = 0.0
    var j = 0
    for (i in 0 until n) {
        if (j < i) j = i
        while (j < n - 1 && cumDist[j] - cumDist[i] < 50.0) j++
        val dd = cumDist[j] - cumDist[i]
        if (dd >= 50.0) {
            val g = (sm[j] - sm[i]) / dd * 100.0
            if (g in -30.0..30.0) {
                if (g > maxG) maxG = g
                if (g < minG) minG = g
            }
        }
    }

    // okrążenia
    val lapList = ArrayList<Split>()
    if (laps.isNotEmpty()) {
        val bounds = ArrayList<Int>()
        bounds.add(0)
        for (l in laps.filter { it in 1 until n }.sorted()) if (l > bounds.last()) bounds.add(l)
        if (bounds.last() < n - 1) bounds.add(n - 1)
        for (k in 1 until bounds.size) {
            val s = bounds[k - 1]
            val e = bounds[k]
            lapList.add(
                Split(
                    "Okr. $k", cumDist[e] - cumDist[s], cumMoving[e] - cumMoving[s], cumActive[e] - cumActive[s],
                    cumAsc[e] - cumAsc[s], cumDesc[e] - cumDesc[s]
                )
            )
        }
    }

    val elapsed = (points[n - 1].time - points[0].time) / 1000.0
    val moving = cumMoving[n - 1]
    val stopped = maxOf(0.0, elapsed - paused - moving)
    val bestKm = splits.filter { it.distanceM >= sport.splitM - 1.0 && it.activeSec > 0 }.minOfOrNull { it.activeSec } ?: 0.0

    // dane do wykresów
    val stride = maxOf(1, (n + 299) / 300)
    val idxs = (0 until n step stride).toMutableList()
    if (idxs.last() != n - 1) idxs.add(n - 1)
    val spd = DoubleArray(n)
    for (i in 0 until n) {
        val lo = maxOf(0, i - 2)
        val hi = minOf(n - 1, i + 2)
        var s = 0.0
        for (k in lo..hi) s += segSpeed[k]
        spd[i] = s / (hi - lo + 1)
    }
    val m = idxs.size
    val series = Series(
        FloatArray(m) { (if (sport.hasDistance) cumDist[idxs[it]] / 1000.0 else cumActive[idxs[it]] / 60.0).toFloat() },
        FloatArray(m) { sm[idxs[it]].toFloat() },
        FloatArray(m) { (spd[idxs[it]] * 3.6).toFloat() },
        IntArray(m) { points[idxs[it]].terrain.color },
        FloatArray(m) { avgField(points, idxs[it], 2) { p -> p.hr } },
        FloatArray(m) { avgField(points, idxs[it], 5) { p -> p.power } },
        FloatArray(m) { avgField(points, idxs[it], 2) { p -> p.cad } },
        if (sport.hasDistance) "km" else "min"
    )

    return RideStats(
        distanceM = totalDist, movingSec = moving, elapsedSec = elapsed, pausedSec = paused, stoppedSec = stopped,
        ascentM = asc, descentM = desc, maxSpeedMs = maxV, minEle = sm.min(), maxEle = sm.max(),
        maxGradePct = maxG, minGradePct = minG, kcal = kcal, bestKmSec = bestKm,
        terrainDist = tDist, terrainTime = tTime, terrainMaxSpeed = tMax,
        splits = splits, laps = lapList, series = series
    )
}
