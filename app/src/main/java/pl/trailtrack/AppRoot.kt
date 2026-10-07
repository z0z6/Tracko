package pl.trailtrack

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.osmdroid.util.BoundingBox

sealed interface Screen {
    data class Ride(val id: Long) : Screen
    data class Route(val id: Long) : Screen
    data class Offline(val box: BoundingBox?) : Screen
}

@Composable
fun AppRoot(repo: Repo) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stack by remember { mutableStateOf(listOf<Screen>()) }
    val top = stack.lastOrNull()

    if (top != null) BackHandler { stack = stack.dropLast(1) }

    when (top) {
        is Screen.Ride -> RideDetailScreen(repo, top.id) { stack = stack.dropLast(1) }
        is Screen.Route -> RouteDetailScreen(
            repo, top.id,
            onBack = { stack = stack.dropLast(1) },
            onOffline = { box -> stack = stack + Screen.Offline(box) }
        )
        is Screen.Offline -> OfflineScreen(top.box) { stack = stack.dropLast(1) }
        null -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> RecordScreen(repo)
                    1 -> RidesScreen(repo) { stack = stack + Screen.Ride(it) }
                    2 -> RoutesScreen(repo) { stack = stack + Screen.Route(it) }
                    else -> SettingsScreen { stack = stack + Screen.Offline(null) }
                }
            }
            IosTabBar(
                listOf("🚴" to "Nagrywaj", "📋" to "Przejazdy", "🧭" to "Trasy", "⚙️" to "Ustawienia"),
                tab
            ) { tab = it }
        }
    }
}
