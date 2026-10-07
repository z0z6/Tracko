package pl.trailtrack

import kotlin.math.exp
import kotlin.math.pow

/** Progi zawodnika używane do stref i TSS. */
data class Thresholds(val ftp: Int, val lthr: Int, val maxHr: Int, val restHr: Int)

val HR_ZONE_NAMES = listOf("Z1 Regeneracja", "Z2 Wytrzymałość", "Z3 Tempo", "Z4 Próg", "Z5 Maks.")
val POWER_ZONE_NAMES = listOf(
    "Z1 Regeneracja", "Z2 Wytrzymałość", "Z3 Tempo", "Z4 Próg", "Z5 VO2max", "Z6 Beztlenowa", "Z7 Sprint"
)
val HR_ZONE_COLORS = intArrayOf(
    0xFF8E8E93.toInt(), 0xFF34C759.toInt(), 0xFFFFCC00.toInt(), 0xFFFF9500.toInt(), 0xFFFF3B30.toInt()
)
val POWER_ZONE_COLORS = intArrayOf(
    0xFF8E8E93.toInt(), 0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFFCC00.toInt(),
    0xFFFF9500.toInt(), 0xFFFF3B30.toInt(), 0xFFAF52DE.toInt()
)

/** Strefy tętna wg % LTHR (Friel, 5 stref). */
fun hrZoneIndex(hr: Int, lthr: Int): Int {
    if (lthr <= 0) return 0
    val r = hr.toDouble() / lthr
    return when {
        r < 0.81 -> 0
        r < 0.90 -> 1
        r < 0.94 -> 2
        r < 1.00 -> 3
        else -> 4
    }
}

/** Strefy mocy wg % FTP (Coggan, 7 stref). */
fun powerZoneIndex(p: Int, ftp: Int): Int {
    if (ftp <= 0) return 0
    val r = p.toDouble() / ftp
    return when {
        r < 0.55 -> 0
        r < 0.76 -> 1
        r < 0.91 -> 2
        r < 1.06 -> 3
        r < 1.21 -> 4
        r < 1.51 -> 5
        else -> 6
    }
}

class RideAnalytics(
    val hasHr: Boolean,
    val hasPower: Boolean,
    val avgHr: Int,
    val maxHr: Int,
    val avgPower: Int,
    val maxPower: Int,
    val np: Int,
    val intensity: Double,
    val vi: Double,
    val tss: Double,
    /** 0 = brak, 1 = z mocy, 2 = z tętna (hrTSS) */
    val tssSource: Int,
    val ef: Double,
    val decouplingPct: Double?,
    val kj: Double,
    val hrZoneSec: DoubleArray,
    val powerZoneSec: DoubleArray,
    val powerCurve: List<Pair<Int, Int>>
)

fun emptyAnalytics() = RideAnalytics(
    false, false, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0.0, null, 0.0,
    DoubleArray(5), DoubleArray(7), emptyList()
)

private fun normPower(a: IntArray, from: Int, to: Int): Double {
    val n = to - from
    if (n <= 0) return 0.0
    if (n < 30) {
        var s = 0.0
        for (i in from until to) s += a[i]
        return s / n
    }
    var window = 0L
    for (i in from until from + 30) window += a[i]
    var acc = (window / 30.0).pow(4)
    var cnt = 1
    for (k in from + 30 until to) {
        window += (a[k] - a[k - 30]).toLong()
        acc += (window / 30.0).pow(4)
        cnt++
    }
    return (acc / cnt).pow(0.25)
}

private fun avgPositive(a: IntArray, from: Int, to: Int): Double {
    var s = 0L
    var c = 0
    for (i in from until to) if (a[i] > 0) { s += a[i]; c++ }
    return if (c > 0) s.toDouble() / c else 0.0
}

/**
 * Analityka przejazdu: NP, IF, TSS (z mocy lub hrTSS z TRIMP), strefy, krzywa mocy, EF, rozprzężenie.
 * Dane są sprowadzane do próbek 1 Hz (przerwy/pauzy pomijane).
 */
fun computeAnalytics(points: List<TrackPoint>, th: Thresholds): RideAnalytics {
    if (points.size < 2) return emptyAnalytics()

    val pwL = ArrayList<Int>()
    val hrL = ArrayList<Int>()
    pwL.add(points[0].power)
    hrL.add(points[0].hr)
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val gap = Math.round((b.time - a.time) / 1000.0).toInt()
        if (gap <= 0) continue
        if (b.brk || gap > 5) {
            pwL.add(b.power); hrL.add(b.hr)
        } else {
            repeat(gap) { pwL.add(b.power); hrL.add(b.hr) }
        }
    }
    val pw = pwL.toIntArray()
    val hr = hrL.toIntArray()
    val n = pw.size
    val hasPower = pw.any { it > 0 }
    val hasHr = hr.any { it > 0 }
    if (!hasPower && !hasHr) return emptyAnalytics()

    // moc
    var avgP = 0
    var maxP = 0
    var np = 0.0
    var kj = 0.0
    if (hasPower) {
        var s = 0L
        for (v in pw) { s += v; if (v > maxP) maxP = v }
        avgP = (s.toDouble() / n).toInt()
        np = normPower(pw, 0, n)
        kj = s / 1000.0
    }
    val intensity = if (hasPower && th.ftp > 0) np / th.ftp else 0.0
    val vi = if (hasPower && avgP > 0) np / avgP else 0.0

    // tętno
    var avgH = 0
    var maxH = 0
    if (hasHr) {
        avgH = avgPositive(hr, 0, n).toInt()
        for (v in hr) if (v > maxH) maxH = v
    }

    // strefy
    val hrZ = DoubleArray(5)
    val pwZ = DoubleArray(7)
    if (hasHr) for (v in hr) if (v > 0) hrZ[hrZoneIndex(v, th.lthr)] += 1.0
    if (hasPower) for (v in pw) pwZ[powerZoneIndex(v, th.ftp)] += 1.0

    // TSS
    var tss = 0.0
    var src = 0
    if (hasPower && th.ftp > 0) {
        tss = n * np * intensity / (th.ftp * 3600.0) * 100.0
        src = 1
    } else if (hasHr && th.maxHr > th.restHr && th.lthr > th.restHr) {
        val span = (th.maxHr - th.restHr).toDouble()
        var trimp = 0.0
        for (v in hr) {
            if (v <= 0) continue
            val r = ((v - th.restHr) / span).coerceIn(0.0, 1.2)
            trimp += (1.0 / 60.0) * r * 0.64 * exp(1.92 * r)
        }
        val rl = (th.lthr - th.restHr) / span
        val oneHour = 60.0 * rl * 0.64 * exp(1.92 * rl)
        if (oneHour > 0) { tss = trimp / oneHour * 100.0; src = 2 }
    }

    // EF i rozprzężenie Pa:Hr
    val ef = if (hasPower && hasHr && avgH > 0) np / avgH else 0.0
    var decoupling: Double? = null
    if (hasPower && hasHr && n >= 1200) {
        val mid = n / 2
        val h1 = avgPositive(hr, 0, mid)
        val h2 = avgPositive(hr, mid, n)
        if (h1 > 0 && h2 > 0) {
            val e1 = normPower(pw, 0, mid) / h1
            val e2 = normPower(pw, mid, n) / h2
            if (e1 > 0) decoupling = (e1 - e2) / e1 * 100.0
        }
    }

    // krzywa mocy
    val curve = ArrayList<Pair<Int, Int>>()
    if (hasPower) {
        val pre = LongArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + pw[i]
        for (d in intArrayOf(5, 15, 60, 300, 1200, 3600)) {
            if (n < d) continue
            var best = 0L
            for (i in d..n) {
                val v = pre[i] - pre[i - d]
                if (v > best) best = v
            }
            curve.add(d to (best / d).toInt())
        }
    }

    return RideAnalytics(
        hasHr, hasPower, avgH, maxH, avgP, maxP, np.toInt(), intensity, vi, tss, src, ef, decoupling, kj,
        hrZ, pwZ, curve
    )
}

// ---------- Performance Management Chart ----------

data class PmcDay(val day: Long, val tss: Double, val ctl: Double, val atl: Double, val tsb: Double)

fun dayOf(ms: Long): Long = (ms + java.util.TimeZone.getDefault().getOffset(ms)) / 86400000L

/** CTL (42 dni), ATL (7 dni), TSB = CTL - ATL. */
fun buildPmc(rides: List<RideEntity>, keepDays: Int): List<PmcDay> {
    val withLoad = rides.filter { it.tss > 0 }
    if (withLoad.isEmpty()) return emptyList()
    val perDay = HashMap<Long, Double>()
    for (r in withLoad) {
        val d = dayOf(r.id)
        perDay[d] = (perDay[d] ?: 0.0) + r.tss
    }
    val first = perDay.keys.minOrNull() ?: return emptyList()
    val today = maxOf(dayOf(System.currentTimeMillis()), first)
    var ctl = 0.0
    var atl = 0.0
    val out = ArrayList<PmcDay>()
    var d = first
    while (d <= today) {
        val t = perDay[d] ?: 0.0
        ctl += (t - ctl) / 42.0
        atl += (t - atl) / 7.0
        out.add(PmcDay(d, t, ctl, atl, ctl - atl))
        d++
    }
    return out.takeLast(keepDays)
}

data class WeekSum(val weeksAgo: Int, val tss: Double, val hours: Double, val km: Double)

fun weeklySums(rides: List<RideEntity>, weeks: Int): List<WeekSum> {
    val today = dayOf(System.currentTimeMillis())
    val tss = DoubleArray(weeks)
    val hours = DoubleArray(weeks)
    val km = DoubleArray(weeks)
    for (r in rides) {
        val w = ((today - dayOf(r.id)) / 7).toInt()
        if (w < 0 || w >= weeks) continue
        tss[w] += r.tss
        hours[w] += r.movingSec / 3600.0
        km[w] += r.distanceM / 1000.0
    }
    return (0 until weeks).map { WeekSum(it, tss[it], hours[it], km[it]) }
}
