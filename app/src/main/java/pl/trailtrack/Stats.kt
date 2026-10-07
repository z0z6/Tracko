package pl.trailtrack

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RideStats(
    val distanceM: Double,
    val movingSec: Double,
    val elapsedSec: Double,
    val ascentM: Double,
    val maxSpeedMs: Double,
    val terrainDist: Map<Terrain, Double>,
    val terrainTime: Map<Terrain, Double>
) {
    val avgSpeedMs: Double get() = if (movingSec > 0) distanceM / movingSec else 0.0
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

fun computeStats(points: List<TrackPoint>): RideStats {
    var dist = 0.0
    var moving = 0.0
    var maxV = 0.0
    var ascent = 0.0
    val tDist = mutableMapOf<Terrain, Double>()
    val tTime = mutableMapOf<Terrain, Double>()
    if (points.size < 2) {
        return RideStats(0.0, 0.0, 0.0, 0.0, 0.0, tDist, tTime)
    }
    var anchor = points[0].ele
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val dt = (b.time - a.time) / 1000.0
        if (dt <= 0) continue
        val d = haversine(a.lat, a.lon, b.lat, b.lon)
        val v = d / dt
        dist += d
        tDist[b.terrain] = (tDist[b.terrain] ?: 0.0) + d
        if (v > 0.8 && dt < 60) {
            moving += dt
            tTime[b.terrain] = (tTime[b.terrain] ?: 0.0) + dt
        }
        if (v > maxV && v < 30) maxV = v
        // Histereza 3 m: ogranicza szum wysokości z GPS
        val e = b.ele
        if (e - anchor >= 3.0) {
            ascent += e - anchor
            anchor = e
        } else if (anchor - e >= 3.0) {
            anchor = e
        }
    }
    val elapsed = (points.last().time - points.first().time) / 1000.0
    return RideStats(dist, moving, elapsed, ascent, maxV, tDist, tTime)
}
