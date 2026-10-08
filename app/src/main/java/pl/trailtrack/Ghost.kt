package pl.trailtrack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// =====================================================================================================
//  Geometria odcinka i profil czasowy „ducha”
// =====================================================================================================

/** Łamana odcinka z dystansem narastającym. Musi mieć co najmniej 2 punkty. */
class SegGeom(val lat: DoubleArray, val lon: DoubleArray) {
    val n: Int = lat.size
    val cum = DoubleArray(n)

    init {
        for (i in 1 until n) cum[i] = cum[i - 1] + haversine(lat[i - 1], lon[i - 1], lat[i], lon[i])
    }

    val length: Double get() = cum[n - 1]
}

class Proj(val along: Double, val lateral: Double, val seg: Int)

fun encodeGeom(pts: List<Pair<Double, Double>>): String =
    pts.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.first, it.second) }

fun decodeGeom(s: String): SegGeom? {
    val parts = s.split(';').filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val la = DoubleArray(parts.size)
    val lo = DoubleArray(parts.size)
    for ((i, p) in parts.withIndex()) {
        val ll = p.split(',')
        if (ll.size != 2) return null
        la[i] = ll[0].toDoubleOrNull() ?: return null
        lo[i] = ll[1].toDoubleOrNull() ?: return null
    }
    return SegGeom(la, lo)
}

/** Przerzedza ślad GPS: punkty co najmniej [minSpacing] m, maksymalnie [maxPts] punktów. */
fun thinGeom(pts: List<Pair<Double, Double>>, minSpacing: Double = 8.0, maxPts: Int = 900): List<Pair<Double, Double>> {
    if (pts.size <= 2) return pts
    var spacing = minSpacing
    while (true) {
        val out = ArrayList<Pair<Double, Double>>()
        out.add(pts[0])
        for (i in 1 until pts.size - 1) {
            val l = out.last()
            if (haversine(l.first, l.second, pts[i].first, pts[i].second) >= spacing) out.add(pts[i])
        }
        out.add(pts.last())
        if (out.size <= maxPts) return out
        spacing *= 1.5
    }
}

/** Rzutuje punkt na łamaną (szukając tylko w odcinkach [from]..[to]); zwraca dystans wzdłuż i odchylenie boczne. */
fun projectOnto(g: SegGeom, lat: Double, lon: Double, from: Int, to: Int): Proj {
    val k = cos(Math.toRadians(lat))
    val lo = from.coerceIn(0, g.n - 2)
    val hi = to.coerceIn(lo, g.n - 2)
    var bestD = Double.MAX_VALUE
    var bestAlong = 0.0
    var bestSeg = lo
    for (i in lo..hi) {
        val ax = (g.lon[i] - lon) * k * 111320.0
        val ay = (g.lat[i] - lat) * 110540.0
        val bx = (g.lon[i + 1] - lon) * k * 111320.0
        val by = (g.lat[i + 1] - lat) * 110540.0
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        val d = sqrt(px * px + py * py)
        if (d < bestD) {
            bestD = d
            bestAlong = g.cum[i] + t * (g.cum[i + 1] - g.cum[i])
            bestSeg = i
        }
    }
    return Proj(bestAlong, bestD, bestSeg)
}

/** Pozycja na odcinku po przejechaniu [s] metrów. */
fun geoAt(g: SegGeom, s: Double): Pair<Double, Double> {
    val x = s.coerceIn(0.0, g.length)
    var i = 0
    while (i < g.n - 2 && g.cum[i + 1] < x) i++
    val seg = g.cum[i + 1] - g.cum[i]
    val f = if (seg <= 0.0) 0.0 else ((x - g.cum[i]) / seg).coerceIn(0.0, 1.0)
    return (g.lat[i] + f * (g.lat[i + 1] - g.lat[i])) to (g.lon[i] + f * (g.lon[i + 1] - g.lon[i]))
}

const val PROFILE_N = 100

/**
 * Z próbek (przejechany dystans, czas) robi profil: czasy w 101 punktach co 1% długości odcinka.
 * Próbki o niemalejącym dystansie; czas w profilu jest niemalejący.
 */
fun buildProfile(samples: List<Pair<Double, Double>>, length: Double): DoubleArray {
    val s = ArrayList<Double>()
    val t = ArrayList<Double>()
    for ((a, b) in samples) {
        if (s.isEmpty() || a > s.last() + 1e-6) {
            s.add(a)
            t.add(b)
        }
    }
    val out = DoubleArray(PROFILE_N + 1)
    var j = 0
    for (k in 0..PROFILE_N) {
        val x = length * k / PROFILE_N
        if (s.size < 2) {
            out[k] = t.lastOrNull() ?: 0.0
        } else {
            while (j < s.size - 2 && s[j + 1] < x) j++
            val s0 = s[j]
            val s1 = s[j + 1]
            val f = if (s1 > s0) ((x - s0) / (s1 - s0)).coerceIn(0.0, 1.0) else 0.0
            out[k] = t[j] + f * (t[j + 1] - t[j])
        }
        if (k > 0 && out[k] < out[k - 1]) out[k] = out[k - 1]
    }
    return out
}

fun encodeProfile(p: DoubleArray): String = p.joinToString(",") { String.format(Locale.US, "%.1f", it) }

fun decodeProfile(s: String): DoubleArray? {
    val parts = s.split(',')
    if (parts.size != PROFILE_N + 1) return null
    return DoubleArray(parts.size) { parts[it].toDoubleOrNull() ?: return null }
}

/** Czas ducha po przejechaniu [s] metrów odcinka o długości [length]. */
fun ghostTimeAt(p: DoubleArray, length: Double, s: Double): Double {
    val x = (s / length * PROFILE_N).coerceIn(0.0, PROFILE_N.toDouble())
    val i = x.toInt()
    val i1 = min(i + 1, PROFILE_N)
    return p[i] + (x - i) * (p[i1] - p[i])
}

/** Dystans przebyty przez ducha po czasie [t]. */
fun ghostDistAt(p: DoubleArray, length: Double, t: Double): Double {
    if (t <= p[0]) return 0.0
    if (t >= p[PROFILE_N]) return length
    var k = 0
    while (k < PROFILE_N - 1 && p[k + 1] <= t) k++
    val d = p[k + 1] - p[k]
    val f = if (d <= 0.0) 0.0 else (t - p[k]) / d
    return (k + f) * length / PROFILE_N
}

/**
 * Pierwszy wynik na nowym odcinku, zbudowany z już nagranej aktywności:
 * punkty [lat]/[lon] leżące na odcinku i czas aktywny [t] (s od początku odcinka, bez pauz).
 */
class NewEffort(
    val startedAt: Long,
    val timeSec: Double,
    val rideId: Long,
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    private val t: DoubleArray
) {
    /** Odtwarza przejazd tak samo jak silnik na żywo: rzut na odcinek, dystans niemalejący. */
    fun samples(g: SegGeom): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var hint = 0
        var along = 0.0
        for (i in lat.indices) {
            val pr = projectOnto(g, lat[i], lon[i], max(0, hint - 3), min(g.n - 2, hint + 80))
            along = max(along, pr.along)
            hint = max(hint, pr.seg)
            if (out.isEmpty() || along > out.last().first + 0.5) out.add(along to t[i])
        }
        return out
    }
}

// =====================================================================================================
//  Silnik ścigania w serwisie nagrywania
// =====================================================================================================

/**
 * Obserwuje nagrywaną trasę i wykrywa przejazd przez znane odcinki tej samej aktywności:
 *  - start: blisko początku odcinka (≤ 25 m) i ruch wzdłuż niego,
 *  - meta: ≥ długość odcinka − 20 m, wynik zapisuje się automatycznie (wszystkie odcinki, nie tylko wybrany),
 *  - zjazd z odcinka (> 40 m od linii przez ~8 s) przerywa próbę.
 * Dla odcinka wybranego do ścigania liczy też luką do ducha na żywo i mówi o niej głosem.
 * Działa na wątku głównym (tak jak zapisy Live w serwisie), więc nie ma wyścigów o stan.
 */
class GhostEngine(
    private val repo: Repo,
    private val sport: Sport,
    private val scope: CoroutineScope
) {
    private class Seg(val e: SegmentEntity, val g: SegGeom)

    private class Run(
        val startTime: Long,
        var hint: Int,
        var along: Double,
        var pausedSec: Double = 0.0,
        var off: Int = 0,
        val samples: ArrayList<Pair<Double, Double>>
    )

    private var segs: List<Seg> = emptyList()
    private val runs = HashMap<String, Run>()
    private val armed = HashSet<String>()
    private val cooldown = HashSet<String>()
    private val bestTimes = HashMap<String, Double>()

    private var racingUid = ""
    private var ghostProfile: DoubleArray? = null
    private var ghostName = ""
    private var ghostTotal = 0.0

    private var idx = 0
    private var active = false
    private var lastPct = 0
    private var lastSign = 0
    private var lastLeadAt = 0L

    suspend fun prepare() {
        val list = runCatching { repo.segmentsFor(sport) }.getOrDefault(emptyList())
        val built = list.mapNotNull { e ->
            val g = decodeGeom(e.geom)
            if (g != null && g.n >= 2 && g.length >= 100.0) Seg(e, g) else null
        }
        val best = HashMap<String, Double>()
        for (s in built) {
            val ef = runCatching { repo.efforts(s.e.uid) }.getOrDefault(emptyList())
            ef.firstOrNull()?.let { best[s.e.uid] = it.timeSec }
        }
        var uid = Prefs.ghostSegmentUid
        var effort: EffortEntity? = null
        if (uid.isNotBlank() && built.any { it.e.uid == uid }) {
            val all = runCatching { repo.efforts(uid) }.getOrDefault(emptyList())
            val chosen = Prefs.ghostEffortId
            effort = all.firstOrNull { it.id == chosen } ?: all.firstOrNull()
        } else {
            uid = ""
        }
        segs = built
        bestTimes.putAll(best)
        val prof = effort?.let { decodeProfile(it.profile) }
        if (uid.isNotBlank() && effort != null && prof != null) {
            racingUid = uid
            ghostProfile = prof
            ghostName = effort.athlete
            ghostTotal = effort.timeSec
            val seg = built.first { it.e.uid == uid }
            setLive(GhostLive(uid, seg.e.name, ghostName, 0, lengthM = seg.g.length, ghostTotalSec = ghostTotal))
            if (Prefs.evGhost) {
                Sound.cue(Beep.OK, "Ścigasz się z duchem: ${effort.athlete}, odcinek ${seg.e.name}. Czas ducha ${spokenDuration(ghostTotal)}.")
            }
        }
        active = true
    }

    fun onState(s: LiveState) {
        if (!active || !s.recording) return
        val pts = s.points
        while (idx < pts.size) {
            val p = pts[idx]
            val prev = if (idx > 0) pts[idx - 1] else null
            for (seg in segs) step(seg, p, prev)
            idx++
        }
    }

    fun finish() {
        active = false
        runs.clear()
    }

    // ---------------------------------------------------------------- maszyna stanów

    private fun step(seg: Seg, p: TrackPoint, prev: TrackPoint?) {
        val uid = seg.e.uid
        val g = seg.g
        val run = runs[uid]

        if (run == null) {
            val d0 = haversine(p.lat, p.lon, g.lat[0], g.lon[0])
            if (uid in cooldown) {
                if (d0 > 100.0) cooldown.remove(uid)
                if (uid == racingUid) updateWaiting(seg, d0)
                return
            }
            if (d0 <= 25.0) armed.add(uid) else if (d0 > 80.0) armed.remove(uid)
            if (uid in armed && !p.brk) {
                val pr = projectOnto(g, p.lat, p.lon, 0, min(12, g.n - 2))
                if (pr.lateral <= 30.0 && pr.along >= 3.0 && pr.along <= 40.0) {
                    // początek liczymy wstecz od pierwszego punktu za linią startu
                    val backMs = (pr.along / max(p.speed, 1.0) * 1000.0).toLong()
                    runs[uid] = Run(
                        startTime = p.time - backMs, hint = pr.seg, along = pr.along,
                        samples = arrayListOf(0.0 to 0.0, pr.along to backMs / 1000.0)
                    )
                    armed.remove(uid)
                    lastPct = 0
                    lastSign = 0
                    if (uid == racingUid) {
                        setLive(liveRacing(seg, 0.0, pr.along, 0.0))
                        if (Prefs.evGhost) Sound.cue(Beep.START, "Start odcinka")
                    }
                    return
                }
            }
            if (uid == racingUid) updateWaiting(seg, d0)
            return
        }

        // --- trwa przejazd odcinka ---
        if (p.brk && prev != null) run.pausedSec += (p.time - prev.time) / 1000.0
        val pr = projectOnto(g, p.lat, p.lon, max(0, run.hint - 3), min(g.n - 2, run.hint + 80))
        if (pr.lateral > 40.0) {
            run.off++
            if (run.off >= 8) abort(seg)
            return
        }
        run.off = 0
        run.along = max(run.along, pr.along)
        run.hint = max(run.hint, pr.seg)
        val t = (p.time - run.startTime) / 1000.0 - run.pausedSec
        if (run.along > run.samples.last().first + 0.5) run.samples.add(run.along to t)

        if (run.along >= g.length - 20.0) {
            finishRun(seg, run, p, t)
            return
        }
        if (uid == racingUid) updateRacing(seg, run, t)
    }

    private fun abort(seg: Seg) {
        val uid = seg.e.uid
        runs.remove(uid)
        armed.remove(uid)
        cooldown.add(uid)
        if (uid == racingUid) {
            val cur = Live.state.value.ghost
            setLive(
                GhostLive(uid, seg.e.name, ghostName, 3, lengthM = seg.g.length, progressM = cur?.progressM ?: 0.0, ghostTotalSec = ghostTotal)
            )
            if (Prefs.evGhost) Sound.cue(Beep.WARN, "Odcinek przerwany. Zjechałeś z trasy odcinka.")
        }
    }

    private fun finishRun(seg: Seg, run: Run, p: TrackPoint, tNow: Double) {
        val uid = seg.e.uid
        val g = seg.g
        val total = tNow + max(0.0, g.length - run.along) / max(p.speed, 1.0)
        run.samples.add(g.length to total)
        runs.remove(uid)
        cooldown.add(uid)
        if (total < 5.0) return

        val profile = encodeProfile(buildProfile(run.samples, g.length))
        val rideId = Live.state.value.startTime
        val startedAt = run.startTime
        scope.launch { repo.addEffort(uid, Prefs.athleteName, true, startedAt, total, profile, rideId) }

        val prevBest = bestTimes[uid]
        val isRecord = prevBest == null || total < prevBest
        if (isRecord) bestTimes[uid] = total

        if (uid == racingUid) {
            val diff = total - ghostTotal
            setLive(
                GhostLive(
                    uid, seg.e.name, ghostName, 2, lengthM = g.length, progressM = g.length,
                    elapsedSec = total, gapSec = diff, resultSec = total, ghostTotalSec = ghostTotal
                )
            )
            if (Prefs.evGhost) {
                val cmp = when {
                    kotlin.math.abs(diff) < 0.5 -> "Remis z duchem."
                    diff < 0 -> "Szybciej od ducha o ${spokenDuration(-diff)}."
                    else -> "Wolniej od ducha o ${spokenDuration(diff)}."
                }
                val rec = if (isRecord && prevBest != null) " Nowy rekord odcinka!" else ""
                Sound.cue(
                    if (isRecord && prevBest != null) Beep.RECORD else if (diff < 0) Beep.GOAL else Beep.BAD,
                    "Odcinek ukończony. Czas ${spokenDuration(total)}. $cmp$rec"
                )
            }
        } else if (Prefs.evGhost) {
            val rec = if (isRecord && prevBest != null) " Nowy rekord odcinka!" else ""
            Sound.cue(if (isRecord && prevBest != null) Beep.RECORD else Beep.OK, "Odcinek ${seg.e.name}. Czas ${spokenDuration(total)}.$rec")
        }
    }

    // ---------------------------------------------------------------- stan na żywo i komunikaty

    private fun liveRacing(seg: Seg, t: Double, along: Double, gap: Double): GhostLive {
        val prof = ghostProfile
        val (gl, go) = if (prof != null) geoAt(seg.g, ghostDistAt(prof, seg.g.length, t)) else (0.0 to 0.0)
        return GhostLive(
            seg.e.uid, seg.e.name, ghostName, 1, progressM = along, lengthM = seg.g.length,
            elapsedSec = t, gapSec = gap, ghostLat = gl, ghostLon = go, ghostTotalSec = ghostTotal
        )
    }

    private fun updateRacing(seg: Seg, run: Run, t: Double) {
        val prof = ghostProfile ?: return
        val g = seg.g
        val gap = t - ghostTimeAt(prof, g.length, run.along)
        setLive(liveRacing(seg, t, run.along, gap))
        if (!Prefs.evGhost) return

        // zmiana prowadzenia
        val sign = if (gap < -1.5) -1 else if (gap > 1.5) 1 else 0
        val now = System.currentTimeMillis()
        if (sign != 0) {
            if (lastSign != 0 && sign != lastSign && now - lastLeadAt > 15_000L) {
                lastLeadAt = now
                if (sign < 0) Sound.cue(Beep.GOOD, "Wyprzedzasz ducha.") else Sound.cue(Beep.BAD, "Duch cię wyprzedził.")
            }
            lastSign = sign
        }
        // co 25% odcinka – aktualna różnica
        val pct = (run.along / g.length * 4.0).toInt().coerceIn(0, 3)
        if (pct > lastPct) {
            lastPct = pct
            val txt = when {
                kotlin.math.abs(gap) < 1.0 -> "Jesteś równo z duchem."
                gap < 0 -> "Prowadzisz o ${spokenDuration(-gap)}."
                else -> "Duch prowadzi o ${spokenDuration(gap)}."
            }
            Sound.cue(null, txt)
        }
    }

    private fun updateWaiting(seg: Seg, d0: Double) {
        val cur = Live.state.value.ghost
        // wynik (2) zostaje na ekranie do kolejnego startu, komunikat o przerwaniu (3) – do powrotu pod start
        if (cur != null && (cur.status == 2 || (cur.status == 3 && d0 > 80.0))) return
        setLive(GhostLive(seg.e.uid, seg.e.name, ghostName, 0, distToStartM = d0, lengthM = seg.g.length, ghostTotalSec = ghostTotal))
    }

    private fun setLive(g: GhostLive) {
        val s = Live.state.value
        if (s.recording && s.ghost != g) Live.state.value = s.copy(ghost = g)
    }
}
