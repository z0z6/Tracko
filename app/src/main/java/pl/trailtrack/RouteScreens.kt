package pl.trailtrack

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

@Composable
fun RoutesScreen(repo: Repo, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    val routes by repo.routes.collectAsState(initial = emptyList())
    var selected by remember { mutableLongStateOf(Prefs.selectedRouteId) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val id = repo.importRoute(uri)
                Toast.makeText(
                    ctx, if (id != null) "Zaimportowano trasę" else "Nie udało się wczytać pliku GPX", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { LargeTitle("Trasy") }
        item {
            IosButton("Importuj plik GPX", c.blue, Modifier.fillMaxWidth()) { picker.launch(arrayOf("*/*")) }
        }
        if (routes.isEmpty()) {
            item {
                IosCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Zaimportuj trasę z pliku GPX (np. z Komoot, Strava, RideWithGPS), a potem wybierz „Jedź tą trasą”. " +
                            "Na mapie podczas jazdy zobaczysz ją na niebiesko wraz z odległością do końca.",
                        color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        items(routes, key = { it.id }) { r ->
            IosCard(Modifier.fillMaxWidth(), onClick = { onOpen(r.id) }) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                        if (selected == r.id) Text("● aktywna", fontSize = 12.sp, color = c.green)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${fmtKm(r.distanceM)}  •  ↑ ${fmtM(r.ascentM)}  •  ${r.pointCount} pkt", fontSize = 14.sp, color = c.secondary)
                }
            }
        }
    }
    LaunchedEffect(routes) { selected = Prefs.selectedRouteId }
}

@Composable
fun RouteDetailScreen(
    repo: Repo, id: Long, onBack: () -> Unit, onOffline: (BoundingBox) -> Unit, onNewSegment: (Long) -> Unit
) {
    val c = ios()
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<LoadedRoute?>(null) }
    var stats by remember { mutableStateOf<RideStats?>(null) }
    var active by remember { mutableStateOf(Prefs.selectedRouteId == id) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val r = repo.loadRoute(id)
        data = r
        if (r != null) {
            stats = withContext(Dispatchers.Default) {
                computeStats(r.points.mapIndexed { i, p -> TrackPoint(p.lat, p.lon, p.ele, i * 1000L, 0.0, Terrain.ASPHALT) }, 75.0)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Trasa", onBack)
        val r = data
        val st = stats
        if (r == null || st == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Wczytywanie…", color = c.secondary) }
        } else {
            val geo = r.points.map { GeoPoint(it.lat, it.lon) }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(r.route.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label)
                TrackMap(emptyList(), Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)), route = geo, fit = true)
                TileGrid(
                    listOf(
                        "Dystans" to fmtKm(st.distanceM),
                        "Podjazd" to fmtM(st.ascentM),
                        "Zjazd" to fmtM(st.descentM),
                        "Maks. nachylenie" to fmtPct(st.maxGradePct),
                        "Wysokość min / maks." to "${fmtM(st.minEle)} / ${fmtM(st.maxEle)}",
                        "Przewyższenie na km" to fmtM(st.ascentPerKm)
                    )
                )
                ElevationChartCard(st.series, overrideColor = 0xFF007AFF.toInt())

                if (active) {
                    IosButton("Zakończ podążanie za trasą", c.orange, Modifier.fillMaxWidth()) {
                        Prefs.selectedRouteId = -1L; active = false
                    }
                } else {
                    IosButton("Jedź tą trasą", c.green, Modifier.fillMaxWidth()) {
                        Prefs.selectedRouteId = id; active = true
                    }
                }
                if (Cloud.active) {
                    var routeMsg by remember { mutableStateOf("") }
                    IosButton(
                        if (routeMsg.isBlank()) "Wyślij trasę online" else routeMsg,
                        c.green, Modifier.fillMaxWidth(), filled = false
                    ) {
                        scope.launch {
                            routeMsg = "Wysyłam…"
                            val r = Cloud.uploadRoute(repo, id)
                            routeMsg = if (r.ok) "Wysłano ✓" else "Błąd: ${r.error}"
                        }
                    }
                }
                IosButton("Utwórz odcinek do ścigania z duchem", c.green, Modifier.fillMaxWidth(), filled = false, icon = AppIcon.Ghost) {
                    onNewSegment(id)
                }
                IosButton("Pobierz mapę offline dla trasy", c.blue, Modifier.fillMaxWidth(), filled = false) {
                    val n = geo.maxOf { it.latitude }
                    val s = geo.minOf { it.latitude }
                    val e = geo.maxOf { it.longitude }
                    val w = geo.minOf { it.longitude }
                    val dLat = (n - s) * 0.1 + 0.002
                    val dLon = (e - w) * 0.1 + 0.002
                    onOffline(BoundingBox(n + dLat, e + dLon, s - dLat, w - dLon))
                }
                IosButton("Usuń trasę", c.red, Modifier.fillMaxWidth(), filled = false) { showDelete = true }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDelete) {
        IosAlert(
            title = "Usunąć trasę?", confirmText = "Usuń", confirmColor = c.red,
            onConfirm = {
                showDelete = false
                if (Prefs.selectedRouteId == id) Prefs.selectedRouteId = -1L
                scope.launch { repo.deleteRoute(id); onBack() }
            },
            dismissText = "Anuluj", onDismiss = { showDelete = false }
        )
    }
}
