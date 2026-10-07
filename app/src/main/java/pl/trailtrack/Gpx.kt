package pl.trailtrack

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpxPoint(val lat: Double, val lon: Double, val ele: Double)
data class ParsedGpx(val name: String?, val points: List<GpxPoint>)

/** Parser GPX: bierze punkty <trkpt> (a jeśli ich brak – <rtept>). */
fun parseGpx(input: InputStream): ParsedGpx {
    val p = Xml.newPullParser()
    p.setInput(input, null)
    val trk = ArrayList<GpxPoint>()
    val rte = ArrayList<GpxPoint>()
    var name: String? = null
    var inTrk = false
    var inRte = false
    var cur: DoubleArray? = null
    var event = p.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            when (p.name) {
                "trk" -> inTrk = true
                "rte" -> inRte = true
                "trkpt", "rtept" -> {
                    val lat = p.getAttributeValue(null, "lat").toDouble()
                    val lon = p.getAttributeValue(null, "lon").toDouble()
                    cur = doubleArrayOf(lat, lon, 0.0)
                }
                "ele" -> {
                    val c = cur
                    if (c != null) c[2] = p.nextText().trim().toDoubleOrNull() ?: 0.0
                }
                "name" -> {
                    if (name == null && cur == null && (inTrk || inRte)) name = p.nextText().trim()
                }
            }
        } else if (event == XmlPullParser.END_TAG) {
            when (p.name) {
                "trkpt" -> { cur?.let { trk.add(GpxPoint(it[0], it[1], it[2])) }; cur = null }
                "rtept" -> { cur?.let { rte.add(GpxPoint(it[0], it[1], it[2])) }; cur = null }
                "trk" -> inTrk = false
                "rte" -> inRte = false
            }
        }
        event = p.next()
    }
    return ParsedGpx(name, if (trk.size >= 2) trk else rte)
}

fun buildGpx(rideId: Long, points: List<TrackPoint>): String {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"TrailTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:tt=\"https://github.com/trailtrack/ns\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
    sb.append("<metadata><time>${iso.format(Date(rideId))}</time></metadata>\n")
    sb.append("<trk><name>TrailTrack ${iso.format(Date(rideId))}</name><type>cycling</type><trkseg>\n")
    points.forEachIndexed { i, p ->
        if (p.brk && i > 0) sb.append("</trkseg><trkseg>\n")
        sb.append("<trkpt lat=\"${String.format(Locale.US, "%.7f", p.lat)}\" lon=\"${String.format(Locale.US, "%.7f", p.lon)}\">")
        sb.append("<ele>${String.format(Locale.US, "%.1f", p.ele)}</ele>")
        sb.append("<time>${iso.format(Date(p.time))}</time>")
        sb.append("<extensions><tt:surface>${p.terrain.name.lowercase()}</tt:surface>")
        if (p.power > 0) sb.append("<tt:power>${p.power}</tt:power>")
        if (p.hr > 0 || p.cad > 0) {
            sb.append("<gpxtpx:TrackPointExtension>")
            if (p.hr > 0) sb.append("<gpxtpx:hr>${p.hr}</gpxtpx:hr>")
            if (p.cad > 0) sb.append("<gpxtpx:cad>${p.cad}</gpxtpx:cad>")
            sb.append("</gpxtpx:TrackPointExtension>")
        }
        sb.append("</extensions>")
        sb.append("</trkpt>\n")
    }
    sb.append("</trkseg></trk></gpx>\n")
    return sb.toString()
}
