package pl.trailtrack

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class TrailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        initOsmdroid(this)
        SensorHub.init(this)
        Sound.init(this)
    }
}

fun initOsmdroid(ctx: Context) {
    val c = Configuration.getInstance()
    c.userAgentValue = ctx.packageName
    // filesDir (nie cacheDir) – system nie kasuje pobranych map offline
    val base = File(ctx.filesDir, "osmdroid").also { it.mkdirs() }
    c.osmdroidBasePath = base
    c.osmdroidTileCache = File(base, "tiles").also { it.mkdirs() }
    c.tileFileSystemCacheMaxBytes = 2L * 1024 * 1024 * 1024
    c.tileFileSystemCacheTrimBytes = 1800L * 1024 * 1024
}
