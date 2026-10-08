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
