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
    private val appDb: AppDb = AppDb.get(ctx)
    val dao: RideDao = appDb.dao()

    val rides: Flow<List<RideEntity>> get() = dao.observeRides()
    val routes: Flow<List<RouteEntity>> get() = dao.observeRoutes()
    val segments: Flow<List<SegmentEntity>> get() = dao.observeSegments()
    val segBest: Flow<List<SegBest>> get() = dao.observeSegBest()
    fun observeEfforts(uid: String): Flow<List<EffortEntity>> = dao.observeEfforts(uid)

    private fun autoName(startMs: Long, sport: Sport): String {
        val h = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.HOUR_OF_DAY)
        val part = when {
            h < 5 -> "Nocny"
            h < 11 -> "Poranny"
            h < 17 -> "Popołudniowy"
            h < 22 -> "Wieczorny"
            else -> "Nocny"
        }
        return "$part ${sport.noun}"
    }

    /** Liczy statystyki i analitykę (NP/TSS), oznacza przejazd jako zakończony. */
    suspend fun finalizeRide(id: Long, points: List<TrackPoint>, laps: List<Int>) {
        val existing = dao.getRide(id)
        val sport = Sport.fromId(existing?.sport ?: 0)
        val st = computeStats(points, Prefs.weightKg.toDouble(), laps, sport)
        val an = computeAnalytics(points, Prefs.thresholds())
        val name = existing?.name?.takeIf { it.isNotBlank() } ?: autoName(id, sport)
        dao.updateRide(
            RideEntity(
                id = id, finished = true, name = name,
                distanceM = st.distanceM, movingSec = st.movingSec, elapsedSec = st.elapsedSec,
                ascentM = st.ascentM, descentM = st.descentM, maxSpeedMs = st.maxSpeedMs,
                kcal = if (an.hasPower) an.kj else st.kcal,
                pointCount = points.size, terrainEnc = encodeTerrain(st.terrainDist),
                avgHr = an.avgHr, maxHr = an.maxHr, avgPower = an.avgPower, normPower = an.np,
                tss = an.tss, tssSource = an.tssSource, bestKmSec = st.bestKmSec,
                sport = sport.id, terrainAuto = existing?.terrainAuto ?: 0
            )
        )
    }

    /** Czy aktywność jest już zapisana (serwis kończy zapis chwilę po Stop). */
    suspend fun rideFinished(id: Long): Boolean = withContext(Dispatchers.IO) { dao.getRide(id)?.finished == true }

    /** Podsumowanie: przypisuje nawierzchnię do punktów [from]..[to] i przelicza statystyki aktywności. */
    suspend fun setTerrainRange(id: Long, from: Int, to: Int, terrain: Terrain) = withContext(Dispatchers.IO) {
        dao.setTerrainRange(id, from, to, terrain.name)
        // ręczna korekta włącza kolorowanie trasy i chroni dane przed nadpisaniem przez automat
        if ((dao.getRide(id)?.terrainAuto ?: 0) == 0) dao.setTerrainAuto(id, 2)
        val d = loadRide(id) ?: return@withContext
        finalizeRide(id, d.points, d.laps)
    }

    enum class AutoTerrain { OK, FAILED, SKIPPED }

    /**
     * Wykrywa nawierzchnie z danych mapy OSM (narty: cały ślad = śnieg, bez internetu), zapisuje je w punktach
     * i przelicza statystyki. [onProgress]: (ukończone zapytania, wszystkie).
     */
    suspend fun autoDetectTerrain(id: Long, onProgress: (Int, Int) -> Unit = { _, _ -> }): AutoTerrain =
        withContext(Dispatchers.IO) {
            val d = loadRide(id) ?: return@withContext AutoTerrain.SKIPPED
            val sport = Sport.fromId(d.ride.sport)
            if (!sport.gps || d.points.size < 2) return@withContext AutoTerrain.SKIPPED

            val terrains: Array<Terrain> = if (sport == Sport.SKIING) {
                Array(d.points.size) { Terrain.SNOW }
            } else {
                val det = SurfaceDetector.detect(d.points, onProgress)
                if (det == null || det.matchedRatio < 0.2) return@withContext AutoTerrain.FAILED
                det.terrains
            }

            // zapis seriami o tej samej nawierzchni, w jednej transakcji
            val runs = ArrayList<Triple<Int, Int, Terrain>>()
            var a = 0
            for (i in 1..terrains.size) {
                if (i == terrains.size || terrains[i] != terrains[a]) {
                    runs.add(Triple(a, i - 1, terrains[a]))
                    a = i
                }
            }
            appDb.runInTransaction(Runnable {
                for ((r0, r1, t) in runs) dao.setTerrainRangeSync(id, r0, r1, t.name)
            })
            dao.setTerrainAuto(id, 1)
            val updated = d.points.mapIndexed { i, p -> p.copy(terrain = terrains[i]) }
            finalizeRide(id, updated, d.laps)
            AutoTerrain.OK
        }

    // ----- odcinki i duchy -----

    suspend fun segmentsFor(sport: Sport): List<SegmentEntity> = withContext(Dispatchers.IO) { dao.segmentsForSport(sport.id) }
    suspend fun getSegment(uid: String): SegmentEntity? = withContext(Dispatchers.IO) { dao.getSegment(uid) }
    suspend fun efforts(uid: String): List<EffortEntity> = withContext(Dispatchers.IO) { dao.efforts(uid) }
    suspend fun getEffort(id: Long): EffortEntity? = withContext(Dispatchers.IO) { dao.getEffort(id) }
    suspend fun deleteEffort(id: Long) = withContext(Dispatchers.IO) { dao.deleteEffort(id) }

    suspend fun deleteSegment(uid: String) = withContext(Dispatchers.IO) {
        dao.deleteEfforts(uid)
        dao.deleteSegment(uid)
    }

    /** Dodaje wynik (bez duplikatów: ten sam zawodnik i czas rozpoczęcia). Zwraca false, jeśli już był. */
    suspend fun addEffort(
        uid: String, athlete: String, mine: Boolean, startedAt: Long, timeSec: Double, profile: String, rideId: Long
    ): Boolean = withContext(Dispatchers.IO) {
        if (dao.effortExists(uid, athlete, startedAt) > 0) return@withContext false
        dao.insertEffort(EffortEntity(0, uid, athlete, if (mine) 1 else 0, startedAt, timeSec, profile, rideId))
        true
    }

    /** Tworzy odcinek z geometrii (lat/lon) i opcjonalnie od razu pierwszy wynik. Zwraca uid. */
    suspend fun createSegment(
        name: String, sport: Sport, geo: List<Pair<Double, Double>>, effort: NewEffort?
    ): String? = withContext(Dispatchers.IO) {
        val thin = thinGeom(geo)
        val g = decodeGeom(encodeGeom(thin)) ?: return@withContext null
        if (g.n < 2 || g.length < 100.0) return@withContext null
        val uid = java.util.UUID.randomUUID().toString()
        dao.upsertSegment(SegmentEntity(uid, name, sport.id, g.length, encodeGeom(thin), System.currentTimeMillis(), Prefs.athleteName))
        if (effort != null) {
            val samples = effort.samples(g)
            val total = effort.timeSec
            val profile = buildProfile(samples + (g.length to total), g.length)
            addEffort(uid, Prefs.athleteName, true, effort.startedAt, total, encodeProfile(profile), effort.rideId)
        }
        uid
    }

    /** Plik .ttseg (JSON) z odcinkiem i najlepszymi wynikami – do wysłania innym użytkownikom. */
    suspend fun exportSegment(uid: String): File? = withContext(Dispatchers.IO) {
        val seg = dao.getSegment(uid) ?: return@withContext null
        val o = org.json.JSONObject()
        o.put("format", "trailtrack-segment")
        o.put("v", 1)
        o.put("uid", seg.uid)
        o.put("name", seg.name)
        o.put("sport", seg.sport)
        o.put("author", seg.author)
        o.put("geom", seg.geom)
        val arr = org.json.JSONArray()
        for (e in dao.efforts(uid).take(30)) {
            arr.put(
                org.json.JSONObject()
                    .put("athlete", e.athlete).put("startedAt", e.startedAt)
                    .put("timeSec", e.timeSec).put("profile", e.profile)
            )
        }
        o.put("efforts", arr)
        val safe = seg.name.replace(Regex("[^A-Za-z0-9ĄąĆćĘęŁłŃńÓóŚśŹźŻż_-]+"), "_").take(40)
        val f = File(exportDir(), "odcinek_$safe.ttseg")
        f.writeText(o.toString())
        f
    }

    /** Import pliku .ttseg. 1 = nowy odcinek, 2 = dołożono wyniki do istniejącego, 0 = błąd. */
    suspend fun importSegment(uri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@runCatching 0
            val o = org.json.JSONObject(text)
            if (o.optString("format") != "trailtrack-segment") return@runCatching 0
            val uid = o.getString("uid")
            val geom = o.getString("geom")
            val g = decodeGeom(geom) ?: return@runCatching 0
            val isNew = dao.getSegment(uid) == null
            if (isNew) {
                dao.upsertSegment(
                    SegmentEntity(uid, o.optString("name", "Odcinek"), o.optInt("sport", 0), g.length, geom,
                        System.currentTimeMillis(), o.optString("author", ""))
                )
            }
            val arr = o.optJSONArray("efforts")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val prof = e.getString("profile")
                    if (decodeProfile(prof) == null) continue
                    val athlete = e.optString("athlete", "?")
                    addEffort(uid, athlete, athlete == Prefs.athleteName, e.getLong("startedAt"), e.getDouble("timeSec"), prof, 0L)
                }
            }
            if (isNew) 1 else 2
        }.getOrDefault(0)
    }

    suspend fun history(sport: Sport): RideHistory = withContext(Dispatchers.IO) {
        val recent = dao.recentForBaseline(sport.id)
        val d = recent.sumOf { it.distanceM }
        val t = recent.sumOf { it.movingSec }
        RideHistory(if (t > 0) d / t else 0.0, dao.bestKmEver(sport.id) ?: 0.0, dao.maxDistance(sport.id) ?: 0.0)
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
        val f = File(exportDir(), "tracko_${stamp(id)}.$format")
        when (format) {
            "tcx" -> f.writeText(buildTcx(id, d.points, d.laps, d.ride.kcal, Sport.fromId(d.ride.sport)))
            "gpx" -> f.writeText(buildGpx(id, d.points, Sport.fromId(d.ride.sport)))
            else -> f.writeText(buildCsv(d.points, Sport.fromId(d.ride.sport)))
        }
        f
    }

    /** ZIP z plikami TCX (można wgrać hurtowo do TrainingPeaks). sinceMs = 0 → wszystko. */
    suspend fun exportZip(sinceMs: Long): File? = withContext(Dispatchers.IO) {
        val list = dao.getFinished().filter { it.id >= sinceMs }
        if (list.isEmpty()) return@withContext null
        val f = File(exportDir(), "tracko_tcx_${stamp(System.currentTimeMillis())}.zip")
        ZipOutputStream(FileOutputStream(f)).use { zip ->
            for (r in list) {
                val pts = dao.getPoints(r.id).map { it.toTrackPoint() }
                if (pts.size < 2) continue
                val laps = dao.getLaps(r.id).map { it.pointIdx }
                zip.putNextEntry(ZipEntry("tracko_${stamp(r.id)}.tcx"))
                zip.write(buildTcx(r.id, pts, laps, r.kcal).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        f
    }

    suspend fun exportSummaryCsv(): File? = withContext(Dispatchers.IO) {
        val list = dao.getFinished()
        if (list.isEmpty()) return@withContext null
        val f = File(exportDir(), "tracko_summary_${stamp(System.currentTimeMillis())}.csv")
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
        dao.insertRide(RideEntity(id = id, sport = parsed.sport.id))
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
