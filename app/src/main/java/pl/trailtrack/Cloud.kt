package pl.trailtrack

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class CloudResult(val ok: Boolean, val code: Int, val body: String, val error: String? = null)

data class RemoteSegment(
    val uid: String, val name: String, val sport: Int, val lengthM: Double,
    val author: String, val efforts: Int, val bestSec: Double?
)

data class RemoteRoute(
    val id: String, val name: String, val sport: Int, val distanceM: Double, val ascentM: Double,
    val author: String, val source: String, val mine: Boolean, val geom: String
)

/**
 * Moduł wymiany danych z zapleczem online (Supabase: PostgREST + Auth). Strona www (np. na GitHub Pages) czyta i zapisuje
 * te same tabele, więc rekordy, duchy, aktywności i trasy trafiają w obie strony. Wszystko jest opt-in (Ustawienia →
 * Rywalizacja online); bez adresu serwera moduł nic nie robi. Kontrakt tabel: docs/CLOUD_API.md, schemat: backend/supabase.
 */
object Cloud {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** ostatni komunikat synchronizacji i znacznik pracy – do pokazania w ustawieniach */
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    val configured: Boolean get() = Prefs.cloudUrl.isNotBlank() && Prefs.cloudKey.isNotBlank()
    val active: Boolean get() = Prefs.cloudEnabled && Prefs.cloudConsent && configured
    val signedIn: Boolean get() = Prefs.cloudAccess.isNotBlank()
    val hasEmail: Boolean get() = Prefs.cloudEmail.isNotBlank()

    private fun base() = Prefs.cloudUrl.trim().trimEnd('/')

    // ------------------------------------------------------------------ HTTP

    private fun fail(msg: String) = CloudResult(false, -1, "", msg)

    private fun extractError(body: String, code: Int): String {
        val j = runCatching { JSONObject(body) }.getOrNull()
        val m = j?.optString("message")?.takeIf { it.isNotBlank() }
            ?: j?.optString("msg")?.takeIf { it.isNotBlank() }
            ?: j?.optString("error_description")?.takeIf { it.isNotBlank() }
            ?: j?.optString("hint")?.takeIf { it.isNotBlank() }
        return m ?: "Błąd serwera ($code)"
    }

    private fun http(method: String, url: String, body: String?, headers: Map<String, String>): CloudResult {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = 40_000
                c.setRequestProperty("User-Agent", "Tracko/${BuildConfig.VERSION_NAME}")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) CloudResult(true, code, text) else CloudResult(false, code, text, extractError(text, code))
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            CloudResult(false, -1, "", e.message ?: "brak połączenia z serwerem")
        }
    }

    private fun headers(token: String?): Map<String, String> =
        mapOf("apikey" to Prefs.cloudKey, "Authorization" to "Bearer ${token ?: Prefs.cloudKey}")

    /** Żądanie z uwierzytelnieniem; przy 401 odświeża token i próbuje jeszcze raz. */
    private fun request(
        method: String, url: String, body: String?, extra: Map<String, String> = emptyMap(), auth: Boolean = true
    ): CloudResult {
        if (!configured) return fail("Brak adresu serwera")
        if (auth && !ensureAuth()) return fail(lastAuthError ?: "Brak logowania do serwera")
        var r = http(method, url, body, headers(if (auth) Prefs.cloudAccess else null) + extra)
        if (auth && r.code == 401 && refreshSession().ok) {
            r = http(method, url, body, headers(Prefs.cloudAccess) + extra)
        }
        return r
    }

    private fun rest(method: String, path: String, body: String? = null, prefer: String? = null, auth: Boolean = true): CloudResult =
        request(method, "${base()}/rest/v1/$path", body, if (prefer != null) mapOf("Prefer" to prefer) else emptyMap(), auth)

    /** Odczyty publiczne nie wymagają konta – używamy klucza anon, jeśli nie jesteśmy zalogowani. */
    private fun restGet(path: String): CloudResult = rest("GET", path, auth = signedIn)

    // ------------------------------------------------------------------ konto

    @Volatile
    private var lastAuthError: String? = null

    private fun saveSession(j: JSONObject) {
        Prefs.cloudAccess = j.optString("access_token")
        Prefs.cloudRefresh = j.optString("refresh_token")
        val exp = j.optLong("expires_at", 0L)
        Prefs.cloudExpiresAt = if (exp > 0) exp else System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600L)
        j.optJSONObject("user")?.let { u ->
            Prefs.cloudUserId = u.optString("id")
            Prefs.cloudEmail = u.optString("email")
        }
    }

    private fun authCall(path: String, method: String, body: String, bearer: String? = null): CloudResult {
        val r = http(method, "${base()}/auth/v1/$path", body, headers(bearer))
        lastAuthError = if (r.ok) null else r.error
        return r
    }

    private fun signInAnonymouslyBlocking(): CloudResult {
        val r = authCall("signup", "POST", "{}")
        if (r.ok) {
            val j = runCatching { JSONObject(r.body) }.getOrNull()
            if (j == null || j.optString("access_token").isBlank()) {
                return CloudResult(false, r.code, r.body, "Serwer nie zwrócił sesji (włącz „Anonymous sign-ins” w Supabase)")
            }
            saveSession(j)
        }
        return r
    }

    private fun refreshSession(): CloudResult {
        val rt = Prefs.cloudRefresh
        if (rt.isBlank()) return fail("Brak sesji")
        val r = authCall("token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", rt).toString())
        if (r.ok) runCatching { saveSession(JSONObject(r.body)) }
        return r
    }

    /** Gwarantuje ważny token (tworzy konto anonimowe przy pierwszym użyciu). */
    private fun ensureAuth(): Boolean {
        if (!configured) return false
        if (Prefs.cloudAccess.isBlank()) return signInAnonymouslyBlocking().ok
        val now = System.currentTimeMillis() / 1000
        if (now < Prefs.cloudExpiresAt - 60) return true
        return refreshSession().ok
    }

    suspend fun signInWithEmail(email: String, password: String): CloudResult = withContext(Dispatchers.IO) {
        val r = authCall("token?grant_type=password", "POST", JSONObject().put("email", email).put("password", password).toString())
        if (r.ok) runCatching { saveSession(JSONObject(r.body)) }
        r
    }

    /** Rejestracja; jeśli jesteśmy zalogowani anonimowo, konto zostaje przypisane do e-maila (dane zostają). */
    suspend fun registerEmail(email: String, password: String): CloudResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("password", password).toString()
        if (signedIn && ensureAuth()) {
            val r = http("PUT", "${base()}/auth/v1/user", body, headers(Prefs.cloudAccess))
            lastAuthError = if (r.ok) null else r.error
            if (r.ok) {
                val u = runCatching { JSONObject(r.body) }.getOrNull()
                Prefs.cloudEmail = u?.optString("new_email")?.takeIf { it.isNotBlank() } ?: email
            }
            r
        } else {
            val r = authCall("signup", "POST", body)
            if (r.ok) runCatching {
                val j = JSONObject(r.body)
                if (j.optString("access_token").isNotBlank()) saveSession(j)
            }
            r
        }
    }

    fun signOut() {
        Prefs.cloudAccess = ""
        Prefs.cloudRefresh = ""
        Prefs.cloudExpiresAt = 0L
        Prefs.cloudUserId = ""
        Prefs.cloudEmail = ""
        Prefs.clearSynced()
    }

    /** Usuwa konto i wszystkie dane na serwerze (RODO), potem wylogowuje. */
    suspend fun deleteAccount(): CloudResult = withContext(Dispatchers.IO) {
        val r = rest("POST", "rpc/delete_my_account", "{}")
        if (r.ok) signOut()
        r
    }

    // ------------------------------------------------------------------ sieć

    private fun networkAllowed(manual: Boolean): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!manual && Prefs.cloudWifiOnly && cm.isActiveNetworkMetered) return false
        return true
    }

    // ------------------------------------------------------------------ odcinki i wyniki

    suspend fun uploadSegment(repo: Repo, uid: String): CloudResult = withContext(Dispatchers.IO) {
        val seg = repo.getSegment(uid) ?: return@withContext fail("Nie znaleziono odcinka")
        val g = decodeGeom(seg.geom) ?: return@withContext fail("Uszkodzona geometria odcinka")
        if (!ensureAuth()) return@withContext fail(lastAuthError ?: "Brak logowania")
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (i in 0 until g.n) {
            minLat = minOf(minLat, g.lat[i]); maxLat = maxOf(maxLat, g.lat[i])
            minLon = minOf(minLon, g.lon[i]); maxLon = maxOf(maxLon, g.lon[i])
        }
        val o = JSONObject()
            .put("uid", seg.uid).put("owner", Prefs.cloudUserId).put("name", seg.name.take(80)).put("sport", seg.sport)
            .put("length_m", seg.lengthM).put("geom", seg.geom)
            .put("min_lat", minLat).put("max_lat", maxLat).put("min_lon", minLon).put("max_lon", maxLon)
            .put("author", (seg.author.ifBlank { Prefs.athleteName }).take(40)).put("is_public", true)
        // istniejący odcinek (np. cudzy, zaimportowany z pliku) zostaje nietknięty
        val r = rest("POST", "segments?on_conflict=uid", JSONArray().put(o).toString(), "resolution=ignore-duplicates,return=minimal")
        if (r.ok) Prefs.markSynced("segments", uid)
        r
    }

    /** Wysyła własne wyniki na odcinku. Zwraca (liczba wysłanych, pierwszy błąd). */
    suspend fun uploadEfforts(repo: Repo, uid: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        if (!ensureAuth()) return@withContext 0 to (lastAuthError ?: "Brak logowania")
        val done = Prefs.syncedSet("efforts")
        var sent = 0
        var firstError: String? = null
        for (e in repo.efforts(uid).filter { it.mine == 1 }) {
            val key = "$uid|${e.startedAt}"
            if (key in done) continue
            val o = JSONObject()
                .put("segment_uid", uid).put("owner", Prefs.cloudUserId).put("athlete", e.athlete.take(40))
                .put("started_at", e.startedAt).put("time_sec", e.timeSec).put("profile", e.profile)
            val r = rest(
                "POST", "segment_efforts?on_conflict=segment_uid,owner,started_at", JSONArray().put(o).toString(),
                "resolution=ignore-duplicates,return=minimal"
            )
            when {
                r.ok -> { Prefs.markSynced("efforts", key); sent++ }
                // serwer odrzucił wynik jako niewiarygodny – nie ponawiamy
                r.error?.contains("implausible") == true || r.error?.contains("profile_mismatch") == true ->
                    Prefs.markSynced("efforts", key)
                else -> if (firstError == null) firstError = r.error
            }
        }
        sent to firstError
    }

    /** Pobiera ranking odcinka i dokłada nowe wyniki do lokalnych (to są „duchy” innych zawodników). */
    suspend fun pullEfforts(repo: Repo, uid: String): Int = withContext(Dispatchers.IO) {
        val r = restGet("segment_efforts?segment_uid=eq.${Uri.encode(uid)}&select=owner,athlete,started_at,time_sec,profile&order=time_sec.asc&limit=100")
        if (!r.ok) return@withContext 0
        val arr = runCatching { JSONArray(r.body) }.getOrNull() ?: return@withContext 0
        var added = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val profile = e.optString("profile")
            if (decodeProfile(profile) == null) continue
            val owner = e.optString("owner")
            val ok = repo.addEffort(
                uid, e.optString("athlete", "?"), owner == Prefs.cloudUserId && owner.isNotBlank(),
                e.optLong("started_at"), e.optDouble("time_sec"), profile, 0L
            )
            if (ok) added++
        }
        added
    }

    /** Pobiera odcinek z internetu razem z rankingiem. */
    suspend fun downloadSegment(repo: Repo, uid: String): Boolean = withContext(Dispatchers.IO) {
        val have = repo.getSegment(uid) != null
        if (!have) {
            val r = restGet("segments?uid=eq.${Uri.encode(uid)}&select=uid,name,sport,length_m,geom,author")
            if (!r.ok) return@withContext false
            val o = runCatching { JSONArray(r.body).optJSONObject(0) }.getOrNull() ?: return@withContext false
            val saved = repo.saveRemoteSegment(
                uid, o.optString("name", "Odcinek"), o.optInt("sport", 0), o.optDouble("length_m", 0.0),
                o.optString("geom"), o.optString("author")
            )
            if (!saved) return@withContext false
            Prefs.markSynced("segments", uid)   // pochodzi z serwera – nie wysyłamy go z powrotem
        }
        pullEfforts(repo, uid)
        true
    }

    /** Lista odcinków do przeglądania (najpopularniejsze lub wyszukane po nazwie). */
    suspend fun browseSegments(query: String, sport: Int? = null): Pair<List<RemoteSegment>, String?> = withContext(Dispatchers.IO) {
        var path = "segment_overview?select=uid,name,sport,length_m,author,efforts,best_sec&order=efforts.desc,created_at.desc&limit=40"
        if (query.isNotBlank()) path += "&name=ilike.*${Uri.encode(query.trim())}*"
        if (sport != null) path += "&sport=eq.$sport"
        val r = restGet(path)
        if (!r.ok) return@withContext emptyList<RemoteSegment>() to (r.error ?: "Błąd")
        val arr = runCatching { JSONArray(r.body) }.getOrNull() ?: return@withContext emptyList<RemoteSegment>() to "Nieprawidłowa odpowiedź"
        val out = ArrayList<RemoteSegment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                RemoteSegment(
                    o.optString("uid"), o.optString("name"), o.optInt("sport"), o.optDouble("length_m"),
                    o.optString("author"), o.optInt("efforts"), if (o.isNull("best_sec")) null else o.optDouble("best_sec")
                )
            )
        }
        out to null
    }

    // ------------------------------------------------------------------ aktywności

    /** Uproszczony, przycięty ślad (bez początku i końca w odległości z ustawień prywatności). */
    internal fun buildPolyline(points: List<TrackPoint>): String {
        val n = points.size
        if (n < 2) return ""
        val cum = DoubleArray(n)
        for (i in 1 until n) {
            cum[i] = cum[i - 1] + if (points[i].brk) 0.0 else haversine(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        val total = cum[n - 1]
        val trim = Prefs.privacyTrimM.toDouble()
        if (total < 2 * trim + 200.0) return ""
        val from = cum.indexOfFirst { it >= trim }
        val to = cum.indexOfLast { it <= total - trim }
        if (from < 0 || to - from < 2) return ""
        val pts = (from..to).map { points[it].lat to points[it].lon }
        return encodeGeom(thinGeom(pts, 15.0, 1500))
    }

    suspend fun uploadRide(repo: Repo, id: Long): CloudResult = withContext(Dispatchers.IO) {
        val d = repo.loadRide(id) ?: return@withContext fail("Nie znaleziono aktywności")
        if (!ensureAuth()) return@withContext fail(lastAuthError ?: "Brak logowania")
        val r = d.ride
        val sport = Sport.fromId(r.sport)
        val o = JSONObject()
            .put("owner", Prefs.cloudUserId).put("started_at", r.id).put("sport", r.sport)
            .put("name", r.name.take(120)).put("athlete", Prefs.athleteName.take(40))
            .put("distance_m", r.distanceM).put("moving_s", r.movingSec).put("ascent_m", r.ascentM)
            .put("kcal", r.kcal).put("avg_hr", r.avgHr).put("tss", r.tss)
            .put("terrain_enc", if (r.terrainEnc.length <= 400) r.terrainEnc else "")
            .put("polyline", if (sport.gps) buildPolyline(d.points) else "")
            .put("visibility", if (Prefs.sharePublic) "public" else "private")
        val res = rest("POST", "rides?on_conflict=owner,started_at", JSONArray().put(o).toString(), "resolution=merge-duplicates,return=minimal")
        if (res.ok) Prefs.markSynced("rides", id.toString())
        res
    }

    // ------------------------------------------------------------------ trasy

    private fun routeGeom(pts: List<GpxPoint>): String {
        var spacing = 10.0
        while (true) {
            val kept = ArrayList<GpxPoint>()
            kept.add(pts[0])
            for (i in 1 until pts.size - 1) {
                val l = kept.last()
                if (haversine(l.lat, l.lon, pts[i].lat, pts[i].lon) >= spacing) kept.add(pts[i])
            }
            kept.add(pts.last())
            if (kept.size <= 3000) {
                return kept.joinToString(";") { String.format(Locale.US, "%.6f,%.6f,%.1f", it.lat, it.lon, it.ele) }
            }
            spacing *= 1.5
        }
    }

    suspend fun uploadRoute(repo: Repo, id: Long): CloudResult = withContext(Dispatchers.IO) {
        val lr = repo.loadRoute(id) ?: return@withContext fail("Nie znaleziono trasy")
        if (lr.points.size < 2) return@withContext fail("Trasa ma za mało punktów")
        if (!ensureAuth()) return@withContext fail(lastAuthError ?: "Brak logowania")
        val o = JSONObject()
            .put("owner", Prefs.cloudUserId).put("client_uid", "r${lr.route.createdAt}")
            .put("name", lr.route.name.take(80)).put("sport", Live.state.value.sport.let { if (it.gps) it.id else 0 })
            .put("distance_m", lr.route.distanceM).put("ascent_m", lr.route.ascentM)
            .put("geom", routeGeom(lr.points)).put("is_public", Prefs.sharePublic).put("source", "app")
            .put("author", Prefs.athleteName.take(40))
        val res = rest("POST", "routes?on_conflict=owner,client_uid", JSONArray().put(o).toString(), "resolution=merge-duplicates,return=minimal")
        if (res.ok) Prefs.markSynced("routes", id.toString())
        res
    }

    /** Trasy ze strony www / od innych użytkowników: własne (także zaplanowane na stronie) i publiczne. */
    suspend fun browseRoutes(): Pair<List<RemoteRoute>, String?> = withContext(Dispatchers.IO) {
        val r = restGet("routes?select=id,owner,name,sport,distance_m,ascent_m,geom,author,source&order=created_at.desc&limit=50")
        if (!r.ok) return@withContext emptyList<RemoteRoute>() to (r.error ?: "Błąd")
        val arr = runCatching { JSONArray(r.body) }.getOrNull() ?: return@withContext emptyList<RemoteRoute>() to "Nieprawidłowa odpowiedź"
        val out = ArrayList<RemoteRoute>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val owner = o.optString("owner")
            out.add(
                RemoteRoute(
                    o.optString("id"), o.optString("name"), o.optInt("sport"), o.optDouble("distance_m"),
                    o.optDouble("ascent_m"), o.optString("author"), o.optString("source"),
                    owner.isNotBlank() && owner == Prefs.cloudUserId, o.optString("geom")
                )
            )
        }
        out to null
    }

    suspend fun importRoute(repo: Repo, r: RemoteRoute): Boolean = withContext(Dispatchers.IO) {
        val pts = r.geom.split(';').mapNotNull { s ->
            val p = s.split(',')
            val la = p.getOrNull(0)?.toDoubleOrNull()
            val lo = p.getOrNull(1)?.toDoubleOrNull()
            if (la == null || lo == null) null else GpxPoint(la, lo, p.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
        }
        val id = repo.createRoute(r.name.ifBlank { "Trasa z internetu" }, pts) ?: return@withContext false
        Prefs.markSynced("routes", "remote:${r.id}")
        id > 0
    }

    // ------------------------------------------------------------------ synchronizacja

    /** Pełna synchronizacja: wysyła nowe elementy (zgodnie z ustawieniami udostępniania) i pobiera rankingi. */
    suspend fun syncAll(repo: Repo, manual: Boolean): String = lock.withLock {
        if (!active) return@withLock "Udostępnianie online jest wyłączone"
        if (!networkAllowed(manual)) return@withLock "Brak sieci (albo włączone „tylko Wi-Fi”)"
        busy.value = true
        status.value = "Synchronizacja…"
        var sSeg = 0; var sEff = 0; var sRide = 0; var sRoute = 0; var pulled = 0
        var error: String? = null
        try {
            val segs = repo.allSegments()
            if (Prefs.shareSegments) {
                for (s in segs) {
                    if (s.uid !in Prefs.syncedSet("segments")) {
                        val r = uploadSegment(repo, s.uid)
                        if (r.ok) sSeg++ else if (error == null) error = r.error
                    }
                    if (s.uid in Prefs.syncedSet("segments")) {
                        val (n, e) = uploadEfforts(repo, s.uid)
                        sEff += n
                        if (e != null && error == null) error = e
                    }
                }
            }
            for (s in segs.take(30)) pulled += pullEfforts(repo, s.uid)
            if (Prefs.shareRides) {
                for (id in repo.finishedRideIds()) {
                    if (id.toString() in Prefs.syncedSet("rides")) continue
                    val r = uploadRide(repo, id)
                    if (r.ok) sRide++ else if (error == null) error = r.error
                }
            }
            if (Prefs.shareRoutes) {
                for (rt in repo.allRoutes()) {
                    if (rt.id.toString() in Prefs.syncedSet("routes")) continue
                    val r = uploadRoute(repo, rt.id)
                    if (r.ok) sRoute++ else if (error == null) error = r.error
                }
            }
            Prefs.cloudLastSync = System.currentTimeMillis()
        } finally {
            busy.value = false
        }
        val msg = "Wysłano: $sSeg odc., $sEff wyn., $sRide akt., $sRoute tras · pobrano $pulled nowych wyników" +
            (if (error != null) "\nBłąd: $error" else "")
        status.value = msg
        msg
    }

    // ------------------------------------------------------------------ automaty (w tle, po zdarzeniach)

    private fun launchAuto(block: suspend () -> Unit) {
        if (!active) return
        scope.launch {
            if (!networkAllowed(false)) return@launch
            runCatching { lock.withLock { block() } }
        }
    }

    /** Po utworzeniu odcinka. */
    fun autoSegment(repo: Repo, uid: String) {
        if (!Prefs.shareSegments) return
        launchAuto {
            if (uploadSegment(repo, uid).ok) uploadEfforts(repo, uid)
        }
    }

    /** Po zapisaniu nowego wyniku na odcinku. */
    fun autoEfforts(repo: Repo, uid: String) {
        if (!Prefs.shareSegments) return
        launchAuto {
            if (uid !in Prefs.syncedSet("segments")) uploadSegment(repo, uid)
            if (uid in Prefs.syncedSet("segments")) uploadEfforts(repo, uid)
        }
    }

    /** Po zapisaniu aktywności (gdy włączone udostępnianie aktywności). */
    fun autoRide(repo: Repo, id: Long) {
        if (!Prefs.shareRides || id.toString() in Prefs.syncedSet("rides")) return
        launchAuto { uploadRide(repo, id) }
    }

    /** Odświeża ranking odcinka (np. przed ściganiem), żeby mieć aktualne duchy innych. */
    fun autoPull(repo: Repo, uid: String) {
        if (uid.isBlank()) return
        launchAuto { pullEfforts(repo, uid) }
    }

    /** Po starcie aplikacji. */
    fun autoSyncOnStart(repo: Repo) {
        if (!active) return
        scope.launch {
            delay(4000)
            runCatching { syncAll(repo, manual = false) }
        }
    }
}
