package pl.trailtrack

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("trailtrack", Context.MODE_PRIVATE)
    }

    var weightKg: Float
        get() = sp.getFloat("weight", 75f)
        set(v) = sp.edit().putFloat("weight", v).apply()

    var autoPause: Boolean
        get() = sp.getBoolean("autoPause", true)
        set(v) = sp.edit().putBoolean("autoPause", v).apply()

    /** 0 = wyłączone */
    var autoLapKm: Int
        get() = sp.getInt("autoLapKm", 0)
        set(v) = sp.edit().putInt("autoLapKm", v).apply()

    /** 0 = OSM, 1 = OpenTopoMap, 2 = własny serwer */
    var mapMode: Int
        get() = sp.getInt("mapMode", 1)
        set(v) = sp.edit().putInt("mapMode", v).apply()

    var customTileUrl: String
        get() = sp.getString("customTileUrl", "") ?: ""
        set(v) = sp.edit().putString("customTileUrl", v).apply()

    var selectedRouteId: Long
        get() = sp.getLong("selectedRoute", -1L)
        set(v) = sp.edit().putLong("selectedRoute", v).apply()

    // ----- progi do analityki -----
    var ftp: Int
        get() = sp.getInt("ftp", 200)
        set(v) = sp.edit().putInt("ftp", v).apply()

    var lthr: Int
        get() = sp.getInt("lthr", 165)
        set(v) = sp.edit().putInt("lthr", v).apply()

    var maxHr: Int
        get() = sp.getInt("maxHr", 190)
        set(v) = sp.edit().putInt("maxHr", v).apply()

    var restHr: Int
        get() = sp.getInt("restHr", 55)
        set(v) = sp.edit().putInt("restHr", v).apply()

    // ----- czujniki -----
    /** obwód koła w mm (do prędkości z czujnika CSC) */
    var wheelMm: Int
        get() = sp.getInt("wheelMm", 2105)
        set(v) = sp.edit().putInt("wheelMm", v).apply()

    /** zapisane czujniki: linie "adres|nazwa" */
    var savedSensors: String
        get() = sp.getString("savedSensors", "") ?: ""
        set(v) = sp.edit().putString("savedSensors", v).apply()

    // ----- wygląd -----
    /** 0 = auto, 1 = jasny, 2 = ciemny, 3 = AMOLED */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) = sp.edit().putInt("themeMode", v).apply()

    /** 0 = iOS, 1 = KDE Breeze, 2 = Windows 11 */
    var themeStyle: Int
        get() = sp.getInt("themeStyle", 0)
        set(v) = sp.edit().putInt("themeStyle", v).apply()

    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    // ----- zaplecze online (rywalizacja, strona www) -----
    var cloudEnabled: Boolean
        get() = sp.getBoolean("cloudEnabled", false)
        set(v) = sp.edit().putBoolean("cloudEnabled", v).apply()

    /** zgoda na wysyłanie danych (okno z opisem pokazywane przy pierwszym włączeniu) */
    var cloudConsent: Boolean
        get() = sp.getBoolean("cloudConsent", false)
        set(v) = sp.edit().putBoolean("cloudConsent", v).apply()

    var cloudUrl: String
        get() = sp.getString("cloudUrl", null) ?: BuildConfig.CLOUD_URL
        set(v) = sp.edit().putString("cloudUrl", v.trim()).apply()

    var cloudKey: String
        get() = sp.getString("cloudKey", null) ?: BuildConfig.CLOUD_ANON_KEY
        set(v) = sp.edit().putString("cloudKey", v.trim()).apply()

    var cloudAccess: String
        get() = sp.getString("cloudAccess", "") ?: ""
        set(v) = sp.edit().putString("cloudAccess", v).apply()

    var cloudRefresh: String
        get() = sp.getString("cloudRefresh", "") ?: ""
        set(v) = sp.edit().putString("cloudRefresh", v).apply()

    /** wygaśnięcie tokenu dostępu (sekundy od 1970) */
    var cloudExpiresAt: Long
        get() = sp.getLong("cloudExpiresAt", 0L)
        set(v) = sp.edit().putLong("cloudExpiresAt", v).apply()

    var cloudUserId: String
        get() = sp.getString("cloudUserId", "") ?: ""
        set(v) = sp.edit().putString("cloudUserId", v).apply()

    var cloudEmail: String
        get() = sp.getString("cloudEmail", "") ?: ""
        set(v) = sp.edit().putString("cloudEmail", v).apply()

    var shareSegments: Boolean
        get() = sp.getBoolean("shareSegments", true)
        set(v) = sp.edit().putBoolean("shareSegments", v).apply()

    var shareRides: Boolean
        get() = sp.getBoolean("shareRides", false)
        set(v) = sp.edit().putBoolean("shareRides", v).apply()

    var shareRoutes: Boolean
        get() = sp.getBoolean("shareRoutes", false)
        set(v) = sp.edit().putBoolean("shareRoutes", v).apply()

    /** wysyłane aktywności i trasy są widoczne dla innych (false = tylko dla właściciela konta na stronie) */
    var sharePublic: Boolean
        get() = sp.getBoolean("sharePublic", false)
        set(v) = sp.edit().putBoolean("sharePublic", v).apply()

    /** ile metrów od początku i końca śladu wycinamy przed wysłaniem (ochrona miejsca zamieszkania) */
    var privacyTrimM: Int
        get() = sp.getInt("privacyTrimM", 200)
        set(v) = sp.edit().putInt("privacyTrimM", v).apply()

    var cloudWifiOnly: Boolean
        get() = sp.getBoolean("cloudWifiOnly", true)
        set(v) = sp.edit().putBoolean("cloudWifiOnly", v).apply()

    var cloudLastSync: Long
        get() = sp.getLong("cloudLastSync", 0L)
        set(v) = sp.edit().putLong("cloudLastSync", v).apply()

    /** zbiory już wysłanych / pobranych elementów (żeby nie powtarzać), osobno dla rodzaju: segments, efforts, rides, routes */
    fun syncedSet(kind: String): Set<String> = sp.getStringSet("cloudSynced_$kind", emptySet()) ?: emptySet()

    fun markSynced(kind: String, v: String) {
        val s = HashSet(syncedSet(kind))
        s.add(v)
        sp.edit().putStringSet("cloudSynced_$kind", s).apply()
    }

    fun clearSynced() {
        val e = sp.edit()
        for (k in listOf("segments", "efforts", "rides", "routes")) e.remove("cloudSynced_$k")
        e.apply()
    }

    // ----- odcinki i duchy -----
    /** uid odcinka, z którym się ścigamy (pusty = brak) */
    var ghostSegmentUid: String
        get() = sp.getString("ghostSegmentUid", "") ?: ""
        set(v) = sp.edit().putString("ghostSegmentUid", v).apply()

    /** id wyniku-ducha (0 = najlepszy wynik na odcinku) */
    var ghostEffortId: Long
        get() = sp.getLong("ghostEffortId", 0L)
        set(v) = sp.edit().putLong("ghostEffortId", v).apply()

    /** imię w rankingach odcinków (trafia do udostępnianych plików) */
    var athleteName: String
        get() = sp.getString("athleteName", null) ?: android.os.Build.MODEL
        set(v) = sp.edit().putString("athleteName", v.trim().ifBlank { android.os.Build.MODEL }).apply()

    var evGhost: Boolean
        get() = sp.getBoolean("evGhost", true)
        set(v) = sp.edit().putBoolean("evGhost", v).apply()

    var lastNoGpsSport: Int
        get() = sp.getInt("lastNoGpsSport", Sport.TREADMILL.id)
        set(v) = sp.edit().putInt("lastNoGpsSport", v).apply()

    // ----- aktywności -----
    /** id aktywności ukrytych na ekranie wyboru (kafelki, których użytkownik nie potrzebuje) */
    var hiddenSports: Set<Int>
        get() = (sp.getString("hiddenSports", "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        set(v) = sp.edit().putString("hiddenSports", v.joinToString(",")).apply()

    /** ostatnio wybrana aktywność ([Sport.id]) */
    var sport: Int
        get() = sp.getInt("sport", 0)
        set(v) = sp.edit().putInt("sport", v).apply()

    /** długość basenu w metrach */
    var poolM: Int
        get() = sp.getInt("poolM", 25)
        set(v) = sp.edit().putInt("poolM", v).apply()

    var treadKmh: Float
        get() = sp.getFloat("treadKmh", 8f)
        set(v) = sp.edit().putFloat("treadKmh", v).apply()

    var treadIncline: Float
        get() = sp.getFloat("treadIncline", 0f)
        set(v) = sp.edit().putFloat("treadIncline", v).apply()

    // ----- dźwięki i komunikaty głosowe -----
    var soundOn: Boolean
        get() = sp.getBoolean("soundOn", true)
        set(v) = sp.edit().putBoolean("soundOn", v).apply()

    var voiceOn: Boolean
        get() = sp.getBoolean("voiceOn", true)
        set(v) = sp.edit().putBoolean("voiceOn", v).apply()

    /** 0 = cicho, 1 = średnio, 2 = głośno */
    var cueVolume: Int
        get() = sp.getInt("cueVolume", 1)
        set(v) = sp.edit().putInt("cueVolume", v).apply()

    /** start / pauza / wznowienie / okrążenie / koniec */
    var evStart: Boolean
        get() = sp.getBoolean("evStart", true)
        set(v) = sp.edit().putBoolean("evStart", v).apply()

    var evGps: Boolean
        get() = sp.getBoolean("evGps", true)
        set(v) = sp.edit().putBoolean("evGps", v).apply()

    var evOffRoute: Boolean
        get() = sp.getBoolean("evOffRoute", true)
        set(v) = sp.edit().putBoolean("evOffRoute", v).apply()

    var evKm: Boolean
        get() = sp.getBoolean("evKm", false)
        set(v) = sp.edit().putBoolean("evKm", v).apply()

    var evGoal: Boolean
        get() = sp.getBoolean("evGoal", true)
        set(v) = sp.edit().putBoolean("evGoal", v).apply()

    var evHalf: Boolean
        get() = sp.getBoolean("evHalf", false)
        set(v) = sp.edit().putBoolean("evHalf", v).apply()

    var evRecord: Boolean
        get() = sp.getBoolean("evRecord", true)
        set(v) = sp.edit().putBoolean("evRecord", v).apply()

    var evPace: Boolean
        get() = sp.getBoolean("evPace", true)
        set(v) = sp.edit().putBoolean("evPace", v).apply()

    var evEffort: Boolean
        get() = sp.getBoolean("evEffort", true)
        set(v) = sp.edit().putBoolean("evEffort", v).apply()

    /** 0 = brak celu, 1 = dystans (km), 2 = czas w ruchu (min), 3 = przewyższenie (m) */
    var goalType: Int
        get() = sp.getInt("goalType", 0)
        set(v) = sp.edit().putInt("goalType", v).apply()

    var goalValue: Float
        get() = sp.getFloat("goalValue", 0f)
        set(v) = sp.edit().putFloat("goalValue", v).apply()

    /** o ile % tempo musi odbiegać od typowego, żeby zgłosić „lepsze/słabsze” */
    var paceMarginPct: Int
        get() = sp.getInt("paceMarginPct", 10)
        set(v) = sp.edit().putInt("paceMarginPct", v).apply()

    /** od której strefy (tętna/mocy, 3–5) uznajemy wysiłek za zwiększony */
    var effortZone: Int
        get() = sp.getInt("effortZone", 4)
        set(v) = sp.edit().putInt("effortZone", v).apply()

    fun thresholds() = Thresholds(ftp, lthr, maxHr, restHr)
}
