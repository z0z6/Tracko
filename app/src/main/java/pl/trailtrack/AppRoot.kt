package pl.trailtrack

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.osmdroid.util.BoundingBox

sealed interface Screen {
    data class Ride(val id: Long) : Screen
    data class Route(val id: Long) : Screen
    data class Offline(val box: BoundingBox?) : Screen
    object Sensors : Screen
    object Audio : Screen
    object Segments : Screen
    data class Segment(val uid: String) : Screen
    data class NewSegment(val rideId: Long, val routeId: Long) : Screen
}

/**
 * Przepływ: animacja startowa → (zakładka „Nagrywaj”) wybór aktywności → ekran nagrywania →
 * po Stop podsumowanie zapisanej aktywności. GPS jest wymuszany dopiero po wybraniu aktywności z GPS.
 */
@Composable
fun AppRoot(repo: Repo) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    /** 0 = wybór aktywności, 1 = ekran nagrywania wybranej aktywności */
    var recStage by rememberSaveable { mutableIntStateOf(0) }
    var splash by rememberSaveable { mutableStateOf(true) }
    var stack by remember { mutableStateOf(listOf<Screen>()) }
    val top = stack.lastOrNull()

    // tylko flagi, żeby korzeń nie przebudowywał się co sekundę razem ze stanem nagrywania
    val flags by remember { Live.state.map { it.recording to it.sport }.distinctUntilChanged() }
        .collectAsState(initial = Live.state.value.let { it.recording to it.sport })
    val recording = flags.first
    val sport = flags.second

    // trwające nagrywanie zawsze pokazuje ekran nagrywania; po Stop zostajemy na nim do chwili zapisu
    LaunchedEffect(recording) { if (recording) recStage = 1 }
    val stage = if (recording) 1 else recStage

    if (top != null) {
        BackHandler { stack = stack.dropLast(1) }
    } else if (tab == 0 && stage == 1 && !recording) {
        BackHandler { recStage = 0 }
    }

    val gateActive = sport.gps && (recording || (top == null && tab == 0 && stage == 1))

    Box(Modifier.fillMaxSize()) {
        GpsGate(active = gateActive, onChangeActivity = { recStage = 0 }) {
            when (top) {
                is Screen.Ride -> RideDetailScreen(
                    repo, top.id,
                    onBack = { stack = stack.dropLast(1) },
                    onNewSegment = { stack = stack + Screen.NewSegment(it, 0L) }
                )
                is Screen.Route -> RouteDetailScreen(
                    repo, top.id,
                    onBack = { stack = stack.dropLast(1) },
                    onOffline = { box -> stack = stack + Screen.Offline(box) },
                    onNewSegment = { stack = stack + Screen.NewSegment(0L, it) }
                )
                is Screen.Offline -> OfflineScreen(top.box) { stack = stack.dropLast(1) }
                is Screen.Sensors -> SensorsScreen { stack = stack.dropLast(1) }
                is Screen.Audio -> AudioSettingsScreen { stack = stack.dropLast(1) }
                is Screen.Segments -> SegmentsScreen(
                    repo, onBack = { stack = stack.dropLast(1) },
                    onOpen = { stack = stack + Screen.Segment(it) }
                )
                is Screen.Segment -> SegmentDetailScreen(
                    repo, top.uid, onBack = { stack = stack.dropLast(1) },
                    onRace = { stack = emptyList(); tab = 0; recStage = 1 }
                )
                is Screen.NewSegment -> SegmentCreateScreen(
                    repo, top.rideId, top.routeId, onBack = { stack = stack.dropLast(1) },
                    onDone = { uid -> stack = stack.dropLast(1) + Screen.Segment(uid) }
                )
                null -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> if (stage == 0) {
                                ActivityPickerScreen(last = sport) { sp ->
                                    Live.setSport(sp)
                                    Prefs.sport = sp.id
                                    if (!sp.gps) Prefs.lastNoGpsSport = sp.id
                                    recStage = 1
                                }
                            } else {
                                RecordScreen(
                                    repo,
                                    onSegments = { stack = stack + Screen.Segments },
                                    onChangeActivity = { recStage = 0 },
                                    onSaved = { id -> recStage = 0; stack = stack + Screen.Ride(id) }
                                )
                            }
                            1 -> RidesScreen(repo) { stack = stack + Screen.Ride(it) }
                            2 -> RoutesScreen(repo) { stack = stack + Screen.Route(it) }
                            3 -> AnalyticsScreen(repo)
                            else -> SettingsScreen(
                                onOffline = { stack = stack + Screen.Offline(null) },
                                onSensors = { stack = stack + Screen.Sensors },
                                onAudio = { stack = stack + Screen.Audio },
                                onSegments = { stack = stack + Screen.Segments }
                            )
                        }
                    }
                    IosTabBar(
                        listOf(
                            AppIcon.Record to "Nagrywaj", AppIcon.Rides to "Aktywności", AppIcon.Routes to "Trasy",
                            AppIcon.Analytics to "Analiza", AppIcon.Settings to "Ustawienia"
                        ),
                        tab
                    ) { tab = it }
                }
            }
        }
        if (splash) SplashScreen { splash = false }
    }
}
