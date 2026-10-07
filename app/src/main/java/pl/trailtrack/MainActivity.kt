package pl.trailtrack

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initOsmdroid(applicationContext)
        val repo = RideRepository(applicationContext)
        if (!Live.state.value.recording) repo.recoverDraft()
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme()
            else lightColorScheme(primary = Color(0xFF2E7D32))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App(repo) }
            }
        }
    }
}

fun initOsmdroid(ctx: Context) {
    val c = Configuration.getInstance()
    c.userAgentValue = ctx.packageName
    val base = File(ctx.cacheDir, "osmdroid").also { it.mkdirs() }
    c.osmdroidBasePath = base
    c.osmdroidTileCache = File(base, "tiles").also { it.mkdirs() }
}
