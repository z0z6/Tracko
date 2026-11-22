package pl.trailtrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repo
    private var lastHrFill = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repo = Repo(applicationContext)
        NothingTheme.migrateOnce()
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

    override fun onResume() {
        super.onResume()
        // tętno z zegarków przez Health Connect (np. CMF Watch 3 Pro): odczyt możliwy tylko z aplikacją na pierwszym planie
        val now = System.currentTimeMillis()
        if (Prefs.healthHr && now - lastHrFill > 10 * 60 * 1000L && !Live.state.value.recording) {
            lastHrFill = now
            lifecycleScope.launch { runCatching { repo.autoFillRecentHr() } }
        }
    }
}
