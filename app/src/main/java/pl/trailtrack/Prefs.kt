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

    fun thresholds() = Thresholds(ftp, lthr, maxHr, restHr)
}
