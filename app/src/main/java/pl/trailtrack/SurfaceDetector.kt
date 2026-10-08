package pl.trailtrack

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Automatyczne wykrywanie nawierzchni na podstawie danych mapy OpenStreetMap (Overpass API):
 * każdy punkt zapisanej trasy jest dopasowywany do najbliższej drogi/ścieżki (≤ 30 m), a nawierzchnia
 * wynika z tagów `surface`, `tracktype` i `highway`. Wymaga internetu; działa po zakończeniu aktywności.
 */
object SurfaceDetector {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
    private const val MATCH_M = 30.0
    private const val VERTEX_SPACING_M = 40.0
    private const val CHUNK = 250
    private const val MIN_RUN = 4

    class Detection(val terrains: Array<Terrain>, val matchedRatio: Double)

    class OsmWay(val terrain: Terrain, val lat: DoubleArray, val lon: DoubleArray)

    // ------------------------------------------------------------------ tagi OSM → nawierzchnia

    fun terrainOf(t: Map<String, String>): Terrain {
        val surface = t["surface"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        when (surface) {
            "asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes", "metal", "chipseal", "bitumen",
            "tartan", "rubber", "wood", "paving_stones:lanes_smooth" -> return Terrain.ASPHALT
            "paving_stones", "sett", "cobblestone", "unhewn_cobblestone", "bricks", "brick", "stone",
            "cobblestone:flattened", "paving_stones:lanes" -> return Terrain.COBBLES
            "gravel", "fine_gravel", "compacted", "pebblestone", "crushed_limestone" -> return Terrain.GRAVEL
            "dirt", "ground", "earth", "grass", "grass_paver", "unpaved", "clay", "rock", "rocks", "woodchips" -> return Terrain.DIRT
            "sand" -> return Terrain.SAND
            "mud" -> return Terrain.MUD
            "snow", "ice" -> return Terrain.SNOW
        }
        when (t["tracktype"]) {
            "grade1", "grade2" -> return Terrain.GRAVEL
            "grade3", "grade4", "grade5" -> return Terrain.DIRT
        }
        return when (t["highway"]) {
            "track", "bridleway" -> Terrain.DIRT
            "path" -> Terrain.SINGLETRACK
            "steps" -> Terrain.COBBLES
            else -> Terrain.ASPHALT
        }
    }

    // ------------------------------------------------------------------ pobieranie danych

    private fun buildQuery(coords: List<Pair<Double, Double>>): String {
        val pts = coords.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.first, it.second) }
        return "[out:json][timeout:40];way[\"highway\"][\"highway\"!~\"^(proposed|construction|bus_guideway|raceway|elevator)$\"]" +
            "(around:${MATCH_M.toInt()},$pts);out tags geom;"
    }

    private fun post(endpoint: String, query: String): String? = runCatching {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 70_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "Tracko/0.8 (Android)")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Zapytanie z ponowieniem na zapasowym serwerze. */
    private fun fetch(query: String): String? {
        for (round in 0 until 2) {
            for (ep in ENDPOINTS) {
                val r = post(ep, query)
                if (r != null) return r
            }
            Thread.sleep(1500)
        }
        return null
    }

    private fun parseWays(text: String, seen: HashSet<Long>, out: MutableList<OsmWay>): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val arr = root.optJSONArray("elements") ?: return true
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("type") != "way") continue
            val id = e.optLong("id")
            if (id != 0L && !seen.add(id)) continue
            val g = e.optJSONArray("geometry") ?: continue
            if (g.length() < 2) continue
            val tags = HashMap<String, String>()
            e.optJSONObject("tags")?.let { to ->
                val keys = to.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    tags[k] = to.optString(k)
                }
            }
            val la = DoubleArray(g.length())
            val lo = DoubleArray(g.length())
            for (j in 0 until g.length()) {
                val p = g.getJSONObject(j)
                la[j] = p.getDouble("lat")
                lo[j] = p.getDouble("lon")
            }
            out.add(OsmWay(terrainOf(tags), la, lo))
        }
        return true
    }

    // ------------------------------------------------------------------ dopasowanie punktów do dróg

    /** Siatka komórek ~65 m, żeby szukać najbliższego odcinka drogi bez porównywania ze wszystkimi. */
    class SegIndex(private val ways: List<OsmWay>) {
        private val cell = 0.0006
        private val map = HashMap<Long, ArrayList<Long>>()

        private fun key(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xffffffffL)

        init {
            for ((wi, w) in ways.withIndex()) {
                for (si in 0 until w.lat.size - 1) {
                    val x0 = floor(min(w.lat[si], w.lat[si + 1]) / cell).toLong()
                    val x1 = floor(max(w.lat[si], w.lat[si + 1]) / cell).toLong()
                    val y0 = floor(min(w.lon[si], w.lon[si + 1]) / cell).toLong()
                    val y1 = floor(max(w.lon[si], w.lon[si + 1]) / cell).toLong()
                    val ref = wi.toLong() * 100_000L + si
                    for (x in x0..x1) for (y in y0..y1) map.getOrPut(key(x, y)) { ArrayList() }.add(ref)
                }
            }
        }

        /** Nawierzchnia najbliższej drogi w promieniu [MATCH_M] albo null. */
        fun nearest(lat: Double, lon: Double): Terrain? {
            val cx = floor(lat / cell).toLong()
            val cy = floor(lon / cell).toLong()
            val k = cos(Math.toRadians(lat))
            var best = Double.MAX_VALUE
            var bestWay = -1
            for (dx in -1L..1L) for (dy in -1L..1L) {
                val list = map[key(cx + dx, cy + dy)] ?: continue
                for (ref in list) {
                    val wi = (ref / 100_000L).toInt()
                    val si = (ref % 100_000L).toInt()
                    val w = ways[wi]
                    val ax = (w.lon[si] - lon) * k * 111320.0
                    val ay = (w.lat[si] - lat) * 110540.0
                    val bx = (w.lon[si + 1] - lon) * k * 111320.0
                    val by = (w.lat[si + 1] - lat) * 110540.0
                    val ddx = bx - ax
                    val ddy = by - ay
                    val l2 = ddx * ddx + ddy * ddy
                    val t = if (l2 == 0.0) 0.0 else ((-ax * ddx - ay * ddy) / l2).coerceIn(0.0, 1.0)
                    val px = ax + t * ddx
                    val py = ay + t * ddy
                    val d = sqrt(px * px + py * py)
                    if (d < best) {
                        best = d
                        bestWay = wi
                    }
                }
            }
            return if (bestWay >= 0 && best <= MATCH_M) ways[bestWay].terrain else null
        }
    }

    /** Uzupełnia braki, odfiltrowuje pojedyncze „migotanie” (modalny filtr ±2) i scala bardzo krótkie odcinki. */
    fun smooth(raw: Array<Terrain?>): Array<Terrain> {
        val n = raw.size
        val filled = arrayOfNulls<Terrain>(n)
        var cur: Terrain? = null
        for (i in 0 until n) {
            if (raw[i] != null) cur = raw[i]
            filled[i] = cur
        }
        var nxt: Terrain? = null
        for (i in n - 1 downTo 0) {
            if (filled[i] != null) nxt = filled[i] else filled[i] = nxt
        }
        val base = Array(n) { filled[it] ?: Terrain.ASPHALT }

        val mode = Array(n) { i ->
            val counts = HashMap<Terrain, Int>()
            for (j in max(0, i - 2)..min(n - 1, i + 2)) counts[base[j]] = (counts[base[j]] ?: 0) + 1
            val top = counts.maxByOrNull { it.value }!!
            if ((counts[base[i]] ?: 0) == top.value) base[i] else top.key
        }

        // krótkie odcinki (< MIN_RUN punktów) przejmują nawierzchnię sąsiada
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && mode[j + 1] == mode[i]) j++
            if (j - i + 1 < MIN_RUN) {
                val repl = if (i > 0) mode[i - 1] else if (j + 1 < n) mode[j + 1] else mode[i]
                for (x in i..j) mode[x] = repl
            }
            i = j + 1
        }
        return mode
    }

    // ------------------------------------------------------------------ wejście

    /**
     * Wykrywa nawierzchnię dla każdego punktu aktywności. Zwraca null, gdy nie udało się pobrać danych mapy.
     * [onProgress] dostaje (ukończone zapytania, wszystkie zapytania). Wołać z wątku roboczego.
     */
    fun detect(points: List<TrackPoint>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Detection? {
        if (points.size < 2) return null

        // wierzchołki zapytania: co ~40 m wzdłuż śladu
        val verts = ArrayList<Pair<Double, Double>>()
        verts.add(points[0].lat to points[0].lon)
        var acc = 0.0
        for (i in 1 until points.size) {
            val p = points[i]
            val q = points[i - 1]
            if (!p.brk) acc += haversine(q.lat, q.lon, p.lat, p.lon)
            if (acc >= VERTEX_SPACING_M || p.brk) {
                verts.add(p.lat to p.lon)
                acc = 0.0
            }
        }
        verts.add(points.last().lat to points.last().lon)

        val chunks = ArrayList<List<Pair<Double, Double>>>()
        var s = 0
        while (s < verts.size - 1) {
            val e = min(verts.size, s + CHUNK)
            chunks.add(verts.subList(s, e))
            s = e - 1
        }
        if (chunks.isEmpty()) chunks.add(verts)

        val ways = ArrayList<OsmWay>()
        val seen = HashSet<Long>()
        onProgress(0, chunks.size)
        for ((ci, ch) in chunks.withIndex()) {
            val text = fetch(buildQuery(ch)) ?: return null
            if (!parseWays(text, seen, ways)) return null
            onProgress(ci + 1, chunks.size)
        }

        val index = SegIndex(ways)
        val raw = arrayOfNulls<Terrain>(points.size)
        var matched = 0
        for ((i, p) in points.withIndex()) {
            val t = index.nearest(p.lat, p.lon)
            raw[i] = t
            if (t != null) matched++
        }
        return Detection(smooth(raw), matched.toDouble() / points.size)
    }
}
