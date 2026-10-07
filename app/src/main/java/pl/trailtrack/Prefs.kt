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
}
