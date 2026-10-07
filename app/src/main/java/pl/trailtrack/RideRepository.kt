package pl.trailtrack

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Proste przechowywanie: jeden plik JSON na przejazd w filesDir/rides. */
class RideRepository(private val context: Context) {

    private val ridesDir: File get() = File(context.filesDir, "rides").also { it.mkdirs() }
    private val draftFile: File get() = File(context.filesDir, "draft.json")

    @Synchronized
    fun save(ride: Ride) {
        File(ridesDir, "${ride.id}.json").writeText(toJson(ride))
    }

    @Synchronized
    fun list(): List<Ride> =
        (ridesDir.listFiles() ?: emptyArray())
            .mapNotNull { f -> runCatching { parseRide(f.readText()) }.getOrNull() }
            .sortedByDescending { it.id }

    @Synchronized
    fun delete(id: Long) {
        File(ridesDir, "$id.json").delete()
    }

    @Synchronized
    fun saveDraft(ride: Ride) {
        draftFile.writeText(toJson(ride))
    }

    @Synchronized
    fun clearDraft() {
        draftFile.delete()
    }

    /** Jeśli aplikacja została ubita w trakcie jazdy – odzyskaj trasę z autozapisu. */
    @Synchronized
    fun recoverDraft() {
        if (!draftFile.exists()) return
        val ride = runCatching { parseRide(draftFile.readText()) }.getOrNull()
        if (ride != null && ride.points.size >= 2 && !File(ridesDir, "${ride.id}.json").exists()) {
            save(ride)
        }
        draftFile.delete()
    }

    fun exportGpx(ride: Ride): File {
        val dir = File(context.cacheDir, "gpx").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(ride.id))
        val f = File(dir, "trailtrack_$stamp.gpx")
        f.writeText(toGpx(ride))
        return f
    }

    private fun toJson(ride: Ride): String {
        val arr = JSONArray()
        for (p in ride.points) {
            arr.put(
                JSONArray()
                    .put(p.lat).put(p.lon).put(p.ele).put(p.time).put(p.speed)
                    .put(p.terrain.name)
            )
        }
        return JSONObject().put("id", ride.id).put("points", arr).toString()
    }

    private fun parseRide(text: String): Ride {
        val o = JSONObject(text)
        val arr = o.getJSONArray("points")
        val pts = ArrayList<TrackPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val terrain = runCatching { Terrain.valueOf(a.getString(5)) }.getOrDefault(Terrain.ASPHALT)
            pts.add(TrackPoint(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getLong(3), a.getDouble(4), terrain))
        }
        return Ride(o.getLong("id"), pts)
    }

    private fun toGpx(ride: Ride): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"TrailTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:tt=\"https://github.com/trailtrack/ns\">\n")
        sb.append("<metadata><time>${iso.format(Date(ride.id))}</time></metadata>\n")
        sb.append("<trk><name>TrailTrack ${iso.format(Date(ride.id))}</name><type>cycling</type><trkseg>\n")
        for (p in ride.points) {
            sb.append("<trkpt lat=\"${String.format(Locale.US, "%.7f", p.lat)}\" lon=\"${String.format(Locale.US, "%.7f", p.lon)}\">")
            sb.append("<ele>${String.format(Locale.US, "%.1f", p.ele)}</ele>")
            sb.append("<time>${iso.format(Date(p.time))}</time>")
            sb.append("<extensions><tt:surface>${p.terrain.name.lowercase()}</tt:surface></extensions>")
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }
}
