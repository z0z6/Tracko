package pl.trailtrack

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LoadedRide(val ride: RideEntity, val points: List<TrackPoint>, val laps: List<Int>)
/** Dane historyczne do komunikatów głosowych. 0 = brak danych. */
data class RideHistory(val baselineSpeedMs: Double, val bestKmSec: Double, val maxDistanceM: Double)
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

    /** Liczy statystyki i analitykę (NP/TSS), oznacza przejazd jako zakończony. */
    suspend fun finalizeRide(id: Long, points: List<TrackPoint>, laps: List<Int>) {
        val st = computeStats(points, Prefs.weightKg.toDouble(), laps)
        val an = computeAnalytics(points, Prefs.thresholds())
        val existing = dao.getRide(id)
        val name = existing?.name?.takeIf { it.isNotBlank() } ?: autoName(id)
        dao.updateRide(
            RideEntity(
                id = id, finished = true, name = name,
                distanceM = st.distanceM, movingSec = st.movingSec, elapsedSec = st.elapsedSec,
                ascentM = st.ascentM, descentM = st.descentM, maxSpeedMs = st.maxSpeedMs,
                kcal = if (an.hasPower) an.kj else st.kcal,
                pointCount = points.size, terrainEnc = encodeTerrain(st.terrainDist),
                avgHr = an.avgHr, maxHr = an.maxHr, avgPower = an.avgPower, normPower = an.np,
                tss = an.tss, tssSource = an.tssSource, bestKmSec = st.bestKmSec
            )
        )
    }

    suspend fun history(): RideHistory = withContext(Dispatchers.IO) {
        val recent = dao.recentForBaseline()
        val d = recent.sumOf { it.distanceM }
        val t = recent.sumOf { it.movingSec }
        RideHistory(if (t > 0) d / t else 0.0, dao.bestKmEver() ?: 0.0, dao.maxDistance() ?: 0.0)
    }

    /** Jednorazowo uzupełnia „najszybszy km” dla przejazdów zapisanych przed wersją 0.5. */
    suspend fun backfillBestKm() = withContext(Dispatchers.IO) {
        for (id in dao.idsNeedingBestKm()) {
            val best = runCatching {
                val pts = dao.getPoints(id).map { it.toTrackPoint() }
                if (pts.size >= 2) computeStats(pts, 75.0).bestKmSec else 0.0
            }.getOrDefault(0.0)
            dao.setBestKm(id, best)
        }
    }

    suspend fun loadRide(id: Long): LoadedRide? = withContext(Dispatchers.IO) {
        val r = dao.getRide(id) ?: return@withContext null
        LoadedRide(r, dao.getPoints(id).map { it.toTrackPoint() }, dao.getLaps(id).map { it.pointIdx })
    }

    suspend fun deleteRide(id: Long) = withContext(Dispatchers.IO) { dao.deleteRide(id) }

    suspend fun renameRide(id: Long, name: String) = withContext(Dispatchers.IO) { dao.renameRide(id, name) }

    // ---------- eksport ----------

    private fun exportDir(): File = File(ctx.cacheDir, "exports").also { it.mkdirs() }

    private fun stamp(id: Long): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(id))

    /** format: "tcx", "gpx" lub "csv" */
    suspend fun exportRide(id: Long, format: String): File? = withContext(Dispatchers.IO) {
        val d = loadRide(id) ?: return@withContext null
        val f = File(exportDir(), "trailtrack_${stamp(id)}.$format")
        when (format) {
            "tcx" -> f.writeText(buildTcx(id, d.points, d.laps, d.ride.kcal))
            "gpx" -> f.writeText(buildGpx(id, d.points))
            else -> f.writeText(buildCsv(d.points))
        }
        f
    }

    /** ZIP z plikami TCX (można wgrać hurtowo do TrainingPeaks). sinceMs = 0 → wszystko. */
    suspend fun exportZip(sinceMs: Long): File? = withContext(Dispatchers.IO) {
        val list = dao.getFinished().filter { it.id >= sinceMs }
        if (list.isEmpty()) return@withContext null
        val f = File(exportDir(), "trailtrack_tcx_${stamp(System.currentTimeMillis())}.zip")
        ZipOutputStream(FileOutputStream(f)).use { zip ->
            for (r in list) {
                val pts = dao.getPoints(r.id).map { it.toTrackPoint() }
                if (pts.size < 2) continue
                val laps = dao.getLaps(r.id).map { it.pointIdx }
                zip.putNextEntry(ZipEntry("trailtrack_${stamp(r.id)}.tcx"))
                zip.write(buildTcx(r.id, pts, laps, r.kcal).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        f
    }

    suspend fun exportSummaryCsv(): File? = withContext(Dispatchers.IO) {
        val list = dao.getFinished()
        if (list.isEmpty()) return@withContext null
        val f = File(exportDir(), "trailtrack_summary_${stamp(System.currentTimeMillis())}.csv")
        f.writeText(buildSummaryCsv(list))
        f
    }

    /** Po zmianie FTP/LTHR/tętna maks. – przelicza TSS i pozostałe metryki wszystkich przejazdów. */
    suspend fun recomputeAll(): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (r in dao.getFinished()) {
            val pts = dao.getPoints(r.id).map { it.toTrackPoint() }
            if (pts.size < 2) continue
            finalizeRide(r.id, pts, dao.getLaps(r.id).map { it.pointIdx })
            n++
        }
        n
    }

    // ---------- odzyskiwanie / import ----------

    /** Przejazdy przerwane (np. ubity proces) – domknij je z tego, co zdążyło się zapisać. */
    suspend fun recoverUnfinished() = withContext(Dispatchers.IO) {
        for (r in dao.getUnfinished()) {
            val pts = dao.getPoints(r.id).map { it.toTrackPoint() }
            if (pts.size < 2) dao.deleteRide(r.id)
            else finalizeRide(r.id, pts, dao.getLaps(r.id).map { it.pointIdx })
        }
    }

    /** Import pliku FIT (np. z Garmin Edge 530). Zwraca id przejazdu, -1 = błąd, -2 = już zaimportowany. */
    suspend fun importFit(uri: Uri): Long = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) null else parseFit(bytes)
        }.getOrNull() ?: return@withContext -1L
        if (parsed.points.size < 2) return@withContext -1L
        val id = parsed.points[0].time
        if (dao.getRide(id) != null) return@withContext -2L
        dao.insertRide(RideEntity(id = id))
        dao.insertPoints(parsed.points.mapIndexed { i, p -> p.toEntity(id, i) })
        if (parsed.laps.isNotEmpty()) dao.insertLaps(parsed.laps.map { LapEntity(rideId = id, pointIdx = it) })
        finalizeRide(id, parsed.points, parsed.laps)
        id
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
