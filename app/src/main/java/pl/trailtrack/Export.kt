package pl.trailtrack

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun isoUtc(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun f(v: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", v)

/** Wartości dozwolone w TCX: Running, Biking, Other. */
fun tcxSport(sport: Sport): String = when (sport) {
    Sport.CYCLING -> "Biking"
    Sport.RUNNING, Sport.TREADMILL -> "Running"
    else -> "Other"
}

/**
 * TCX (Training Center XML) – format akceptowany przez TrainingPeaks, Garmin Connect, Strava.
 * Zawiera tętno, kadencję, moc (ns3:Watts) i okrążenia.
 */
fun buildTcx(rideId: Long, points: List<TrackPoint>, laps: List<Int>, kcal: Double, sport: Sport = Sport.CYCLING): String {
    val n = points.size
    val iso = isoUtc()
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
    sb.append("xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">\n")
    sb.append("<Activities><Activity Sport=\"${tcxSport(sport)}\"><Id>${iso.format(Date(rideId))}</Id>\n")
    if (n < 2) {
        sb.append("</Activity></Activities></TrainingCenterDatabase>\n")
        return sb.toString()
    }
    val hasPower = points.any { it.power > 0 }

    val cum = DoubleArray(n)
    for (i in 1 until n) {
        cum[i] = cum[i - 1] + segmentDist(sport, points[i - 1], points[i])
    }
    val total = cum[n - 1]

    val starts = ArrayList<Int>()
    starts.add(0)
    for (l in laps.sorted()) if (l in 1 until n && l > starts.last()) starts.add(l)

    for (k in starts.indices) {
        val s = starts[k]
        val e = if (k + 1 < starts.size) starts[k + 1] else n
        var time = 0.0
        var maxV = 0.0
        var hrSum = 0L
        var hrCnt = 0
        var hrMax = 0
        for (i in s until e) {
            val p = points[i]
            if (i > s && !p.brk) time += (p.time - points[i - 1].time) / 1000.0
            if (p.speed > maxV && p.speed < 30.0) maxV = p.speed
            if (p.hr > 0) { hrSum += p.hr; hrCnt++; if (p.hr > hrMax) hrMax = p.hr }
        }
        val dist = cum[e - 1] - cum[s]
        val cal = if (total > 0) kcal * dist / total else 0.0
        sb.append("<Lap StartTime=\"${iso.format(Date(points[s].time))}\">")
        sb.append("<TotalTimeSeconds>${f(time, 1)}</TotalTimeSeconds>")
        sb.append("<DistanceMeters>${f(dist, 1)}</DistanceMeters>")
        sb.append("<MaximumSpeed>${f(maxV, 2)}</MaximumSpeed>")
        sb.append("<Calories>${Math.round(cal)}</Calories>")
        if (hrCnt > 0) {
            sb.append("<AverageHeartRateBpm><Value>${hrSum / hrCnt}</Value></AverageHeartRateBpm>")
            sb.append("<MaximumHeartRateBpm><Value>$hrMax</Value></MaximumHeartRateBpm>")
        }
        sb.append("<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod>\n<Track>\n")
        for (i in s until e) {
            val p = points[i]
            if (p.brk && i > s) sb.append("</Track>\n<Track>\n")
            sb.append("<Trackpoint><Time>${iso.format(Date(p.time))}</Time>")
            if (sport.gps) {
                sb.append("<Position><LatitudeDegrees>${f(p.lat, 7)}</LatitudeDegrees><LongitudeDegrees>${f(p.lon, 7)}</LongitudeDegrees></Position>")
            }
            if (sport.gps || sport == Sport.TREADMILL) sb.append("<AltitudeMeters>${f(p.ele, 1)}</AltitudeMeters>")
            sb.append("<DistanceMeters>${f(cum[i], 1)}</DistanceMeters>")
            if (p.hr > 0) sb.append("<HeartRateBpm><Value>${p.hr}</Value></HeartRateBpm>")
            if (p.cad in 1..254) sb.append("<Cadence>${p.cad}</Cadence>")
            sb.append("<Extensions><ns3:TPX><ns3:Speed>${f(p.speed, 2)}</ns3:Speed>")
            if (hasPower) sb.append("<ns3:Watts>${p.power}</ns3:Watts>")
            sb.append("</ns3:TPX></Extensions></Trackpoint>\n")
        }
        sb.append("</Track></Lap>\n")
    }
    sb.append("</Activity></Activities></TrainingCenterDatabase>\n")
    return sb.toString()
}

/** CSV na punkt (do arkuszy, Golden Cheetah, własnych analiz). */
fun buildCsv(points: List<TrackPoint>, sport: Sport = Sport.CYCLING): String {
    val iso = isoUtc()
    val sb = StringBuilder("time_utc,elapsed_s,lat,lon,ele_m,speed_ms,hr_bpm,power_w,cadence_rpm,surface,distance_m\n")
    if (points.isEmpty()) return sb.toString()
    val t0 = points[0].time
    var dist = 0.0
    for (i in points.indices) {
        val p = points[i]
        if (i > 0) dist += segmentDist(sport, points[i - 1], p)
        sb.append(iso.format(Date(p.time))).append(',')
            .append((p.time - t0) / 1000).append(',')
            .append(if (sport.gps) f(p.lat, 7) else "").append(',').append(if (sport.gps) f(p.lon, 7) else "").append(',')
            .append(f(p.ele, 1)).append(',').append(f(p.speed, 2)).append(',')
            .append(p.hr).append(',').append(p.power).append(',').append(p.cad).append(',')
            .append(p.terrain.name.lowercase()).append(',').append(f(dist, 1)).append('\n')
    }
    return sb.toString()
}

/** Zbiorczy CSV wszystkich przejazdów z metrykami analitycznymi. */
fun buildSummaryCsv(rides: List<RideEntity>): String {
    val iso = isoUtc()
    val sb = StringBuilder(
        "date_utc,name,sport,distance_km,moving_time_s,elapsed_time_s,ascent_m,descent_m,avg_speed_kmh,max_speed_kmh," +
            "avg_hr,max_hr,avg_power_w,norm_power_w,tss,tss_source,kcal\n"
    )
    for (r in rides) {
        val avg = if (r.movingSec > 0) r.distanceM / r.movingSec * 3.6 else 0.0
        sb.append(iso.format(Date(r.id))).append(',')
            .append('"').append(r.name.replace("\"", "'")).append('"').append(',')
            .append(Sport.fromId(r.sport).name.lowercase()).append(',')
            .append(f(r.distanceM / 1000.0, 2)).append(',').append(r.movingSec.toLong()).append(',')
            .append(r.elapsedSec.toLong()).append(',').append(f(r.ascentM, 0)).append(',').append(f(r.descentM, 0)).append(',')
            .append(f(avg, 1)).append(',').append(f(r.maxSpeedMs * 3.6, 1)).append(',')
            .append(r.avgHr).append(',').append(r.maxHr).append(',').append(r.avgPower).append(',').append(r.normPower).append(',')
            .append(f(r.tss, 1)).append(',')
            .append(when (r.tssSource) { 1 -> "power"; 2 -> "hr"; else -> "" }).append(',')
            .append(f(r.kcal, 0)).append('\n')
    }
    return sb.toString()
}
