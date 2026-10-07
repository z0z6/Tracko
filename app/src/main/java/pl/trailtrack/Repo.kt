package pl.trailtrack

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar

data class LoadedRide(val ride: RideEntity, val points: List<TrackPoint>, val laps: List<Int>)
data class LoadedRoute(val route: RouteEntity, val points: List<GpxPoint>)

class Repo(private val ctx: Context) {
    val dao: RideDao = AppDb.get(ctx).dao()

    val rides: Flow<List<RideEntity>> get() = dao.observeRides()
    val routes: Flow<List<RouteEntity>> get() = dao.observeRoutes()

    private fun autoName(startMs: Long): String {
        val h = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "Nocny przejazd"
            h < 11 -> "Poranny przejazd"
            h < 17 -> "Popołudniowy przejazd"
            h < 22 -> "Wieczorny przejazd"
            else -> "Nocny przejazd"
        }
    }

    /** Liczy statystyki i oznacza przejazd jako zakończony. */
    suspend fun finalizeRide(id: Long, points: List<TrackPoint>, laps: List<Int>) {
        val st = computeStats(points, Prefs.weightKg.toDouble(), laps)
        val existing = dao.getRide(id)
        val name = existing?.name?.takeIf { it.isNotBlank() } ?: autoName(id)
        dao.updateRide(
            RideEntity(
                id = id, finished = true, name = name,
                distanceM = st.distanceM, movingSec = st.movingSec, elapsedSec = st.elapsedSec,
                ascentM = st.ascentM, descentM = st.descentM, maxSpeedMs = st.maxSpeedMs,
                kcal = st.kcal, pointCount = points.size, terrainEnc = encodeTerrain(st.terrainDist)
            )
        )
    }

    suspend fun loadRide(id: Long): LoadedRide? = withContext(Dispatchers.IO) {
        val r = dao.getRide(id) ?: return@withContext null
        LoadedRide(r, dao.getPoints(id).map { it.toTrackPoint() }, dao.getLaps(id).map { it.pointIdx })
    }

    suspend fun deleteRide(id: Long) = withContext(Dispatchers.IO) { dao.deleteRide(id) }

    suspend fun renameRide(id: Long, name: String) = withContext(Dispatchers.IO) { dao.renameRide(id, name) }

    suspend fun exportGpx(id: Long): File? = withContext(Dispatchers.IO) {
        val data = loadRide(id) ?: return@withContext null
        val dir = File(ctx.cacheDir, "gpx").also { it.mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date(id))
        val f = File(dir, "trailtrack_$stamp.gpx")
        f.writeText(buildGpx(id, data.points))
        f
    }

    /** Przejazdy przerwane (np. ubity proces) – domknij je z tego, co zdążyło się zapisać. */
    suspend fun recoverUnfinished() = withContext(Dispatchers.IO) {
        for (r in dao.getUnfinished()) {
            val pts = dao.getPoints(r.id).map { it.toTrackPoint() }
            if (pts.size < 2) dao.deleteRide(r.id)
            else finalizeRide(r.id, pts, dao.getLaps(r.id).map { it.pointIdx })
        }
    }

    // ----- trasy -----

    suspend fun importRoute(uri: Uri): Long? = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { parseGpx(it) }
        }.getOrNull() ?: return@withContext null
        if (parsed.points.size < 2) return@withContext null
        val name = parsed.name?.takeIf { it.isNotBlank() } ?: displayName(uri) ?: "Trasa"
        val tps = parsed.points.mapIndexed { i, p ->
            TrackPoint(p.lat, p.lon, p.ele, i * 1000L, 0.0, Terrain.ASPHALT)
        }
        val st = computeStats(tps, 75.0)
        val id = dao.insertRoute(
            RouteEntity(name = name, createdAt = System.currentTimeMillis(), distanceM = st.distanceM, ascentM = st.ascentM, pointCount = tps.size)
        )
        dao.insertRoutePoints(parsed.points.mapIndexed { i, p -> RoutePointEntity(routeId = id, idx = i, lat = p.lat, lon = p.lon, ele = p.ele) })
        id
    }

    suspend fun loadRoute(id: Long): LoadedRoute? = withContext(Dispatchers.IO) {
        val r = dao.getRoute(id) ?: return@withContext null
        LoadedRoute(r, dao.getRoutePoints(id).map { GpxPoint(it.lat, it.lon, it.ele) })
    }

    suspend fun deleteRoute(id: Long) = withContext(Dispatchers.IO) { dao.deleteRoute(id) }

    private fun displayName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i)?.removeSuffix(".gpx") else null
        }
    }.getOrNull()

    /** Jednorazowa migracja przejazdów zapisanych w wersji 0.1 (pliki JSON). */
    suspend fun importLegacyJson() = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "rides")
        val files = dir.listFiles() ?: return@withContext
        for (f in files) {
            runCatching {
                val o = JSONObject(f.readText())
                val id = o.getLong("id")
                val arr = o.getJSONArray("points")
                val pts = ArrayList<TrackPoint>()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    val t = runCatching { Terrain.valueOf(a.getString(5)) }.getOrDefault(Terrain.ASPHALT)
                    pts.add(TrackPoint(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getLong(3), a.getDouble(4), t))
                }
                if (pts.size >= 2 && dao.getRide(id) == null) {
                    dao.insertRide(RideEntity(id = id))
                    dao.insertPoints(pts.mapIndexed { i, p -> p.toEntity(id, i) })
                    finalizeRide(id, pts, emptyList())
                }
            }
            f.delete()
        }
        File(ctx.filesDir, "draft.json").delete()
    }
}
