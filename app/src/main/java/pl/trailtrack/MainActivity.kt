package pl.trailtrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        SensorHub.connectSaved()
        Live.setSport(Sport.fromId(Prefs.sport))
        if (!Live.state.value.recording) {
            lifecycleScope.launch {
                runCatching { repo.importLegacyJson() }
                runCatching { repo.recoverUnfinished() }
                runCatching { repo.backfillBestKm() }
            }
        }
        Cloud.autoSyncOnStart(repo)
        setContent {
            IosTheme { AppRoot(repo) }
        }
    }
}
