package pl.trailtrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- formatowanie ----------

fun fmtKm(m: Double): String = String.format(Locale.getDefault(), "%.2f km", m / 1000.0)
fun fmtKmh(ms: Double): String = String.format(Locale.getDefault(), "%.1f km/h", ms * 3.6)
fun fmtM(m: Double): String = String.format(Locale.getDefault(), "%.0f m", m)
fun fmtTime(sec: Double): String {
    val s = sec.toLong()
    return String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
fun fmtDate(t: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(t))

// ---------- root ----------

@Composable
fun App(repo: RideRepository) {
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Ride?>(null) }
    var rides by remember { mutableStateOf(emptyList<Ride>()) }
    var refresh by remember { mutableIntStateOf(0) }
    val live by Live.state.collectAsState()

    LaunchedEffect(live.recording, refresh) {
        rides = withContext(Dispatchers.IO) { repo.list() }
    }

    val ride = selected
    if (ride != null) {
        BackHandler { selected = null }
        RideDetail(
            ride = ride,
            repo = repo,
            onDelete = {
                repo.delete(ride.id)
                selected = null
                refresh++
            },
            onBack = { selected = null }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Text("🚴") }, label = { Text("Nagrywaj") }
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Text("📋") }, label = { Text("Przejazdy") }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (tab == 0) RecordScreen() else RidesScreen(rides) { selected = it }
            }
        }
    }
}

// ---------- nagrywanie ----------

private fun startRecording(ctx: Context) {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        Toast.makeText(ctx, "Włącz GPS (lokalizację) w telefonie", Toast.LENGTH_LONG).show()
    }
    val i = Intent(ctx, TrackingService::class.java).setAction(TrackingService.ACTION_START)
    ContextCompat.startForegroundService(ctx, i)
}

private fun stopRecording(ctx: Context) {
    val i = Intent(ctx, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)
    ctx.startService(i)
}

@Composable
fun RecordScreen() {
    val ctx = LocalContext.current
    val live by Live.state.collectAsState()
    val stats = remember(live.points) { computeStats(live.points) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording(ctx)
        else Toast.makeText(ctx, "Bez zgody na lokalizację nie nagram trasy", Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize()) {
        TrackMap(
            points = live.points, follow = true, fit = false,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Stat("Dystans", fmtKm(stats.distanceM))
            Stat("Czas", fmtTime(stats.movingSec))
            Stat("Prędkość", fmtKmh(live.points.lastOrNull()?.speed ?: 0.0))
            Stat("Podjazd", fmtM(stats.ascentM))
        }

        Text(
            "Nawierzchnia (przełączaj w trakcie jazdy):",
            Modifier.padding(horizontal = 12.dp), fontSize = 12.sp
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Terrain.values().forEach { t ->
                FilterChip(
                    selected = live.terrain == t,
                    onClick = { Live.setTerrain(t) },
                    label = { Text(t.label) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(Color(t.color), CircleShape)) }
                )
            }
        }

        Button(
            onClick = {
                if (live.recording) {
                    stopRecording(ctx)
                } else {
                    val fine = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (fine) {
                        startRecording(ctx)
                    } else {
                        val perms = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        permLauncher.launch(perms.toTypedArray())
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(56.dp)
        ) {
            Text(if (live.recording) "Zakończ i zapisz" else "Start", fontSize = 18.sp)
        }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp)
    }
}

// ---------- lista przejazdów ----------

@Composable
fun RidesScreen(rides: List<Ride>, onOpen: (Ride) -> Unit) {
    if (rides.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Brak zapisanych przejazdów.\nNagraj pierwszy!", Modifier.padding(24.dp))
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rides, key = { it.id }) { r ->
            val s = remember(r) { computeStats(r.points) }
            Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmtDate(r.id), style = MaterialTheme.typography.titleMedium)
                    Text("${fmtKm(s.distanceM)}  •  ${fmtTime(s.movingSec)}  •  ↑ ${fmtM(s.ascentM)}")
                    Spacer(Modifier.height(8.dp))
                    TerrainBar(s)
                }
            }
        }
    }
}

@Composable
fun TerrainBar(s: RideStats) {
    Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
        s.terrainDist.forEach { (t, d) ->
            if (d > 1.0) {
                Box(Modifier.weight(d.toFloat()).fillMaxHeight().background(Color(t.color)))
            }
        }
    }
}

// ---------- szczegóły przejazdu ----------

@Composable
fun RideDetail(ride: Ride, repo: RideRepository, onDelete: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val stats = remember(ride) { computeStats(ride.points) }
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Wróć") }
            Text(fmtDate(ride.id), style = MaterialTheme.typography.titleMedium)
        }
        TrackMap(
            points = ride.points, follow = false, fit = true,
            modifier = Modifier.fillMaxWidth().height(300.dp)
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            DetailRow("Dystans", fmtKm(stats.distanceM))
            DetailRow("Czas w ruchu", fmtTime(stats.movingSec))
            DetailRow("Czas całkowity", fmtTime(stats.elapsedSec))
            DetailRow("Średnia (w ruchu)", fmtKmh(stats.avgSpeedMs))
            DetailRow("Maks. prędkość", fmtKmh(stats.maxSpeedMs))
            DetailRow("Podjazd", fmtM(stats.ascentM))

            Spacer(Modifier.height(12.dp))
            Text("Nawierzchnie", style = MaterialTheme.typography.titleMedium)
            stats.terrainDist.entries.sortedByDescending { it.value }.forEach { (t, d) ->
                val sec = stats.terrainTime[t] ?: 0.0
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Color(t.color), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(t.label, Modifier.weight(1f))
                    Text("${fmtKm(d)} · ${if (sec > 1) fmtKmh(d / sec) else "–"}")
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val file = repo.exportGpx(ride)
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Eksportuj GPX"))
                }) { Text("Eksportuj GPX") }
                OutlinedButton(onClick = { confirm = true }) { Text("Usuń") }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Usunąć przejazd?") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

// ---------- mapa (osmdroid / OpenStreetMap) ----------

private fun splitRuns(points: List<TrackPoint>): List<Pair<Terrain, List<GeoPoint>>> {
    val out = ArrayList<Pair<Terrain, List<GeoPoint>>>()
    var cur = ArrayList<GeoPoint>()
    var curT: Terrain? = null
    for (p in points) {
        val g = GeoPoint(p.lat, p.lon)
        if (curT != null && p.terrain != curT) {
            out.add(curT to cur)
            cur = arrayListOf(cur.last())
        }
        curT = p.terrain
        cur.add(g)
    }
    if (curT != null && cur.size > 1) out.add(curT to cur)
    return out
}

@Composable
fun TrackMap(points: List<TrackPoint>, follow: Boolean, fit: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var topo by rememberSaveable { mutableStateOf(true) }
    val zoomedOnce = remember { booleanArrayOf(false) }

    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(52.0, 19.4))
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                val src = if (topo) TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK
                if (mv.tileProvider.tileSource.name() != src.name()) mv.setTileSource(src)

                mv.overlays.clear()
                for ((t, geo) in splitRuns(points)) {
                    mv.overlays.add(Polyline().apply {
                        outlinePaint.color = t.color
                        outlinePaint.strokeWidth = 9f
                        setPoints(geo)
                    })
                }
                val last = points.lastOrNull()
                if (follow && last != null) {
                    mv.overlays.add(Marker(mv).apply {
                        position = GeoPoint(last.lat, last.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                    if (!zoomedOnce[0]) {
                        mv.controller.setZoom(16.0)
                        zoomedOnce[0] = true
                    }
                    mv.controller.setCenter(GeoPoint(last.lat, last.lon))
                }
                if (fit && points.size >= 2) {
                    val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
                    mv.post { mv.zoomToBoundingBox(box, false, 80) }
                }
                mv.invalidate()
            }
        )
        Button(
            onClick = { topo = !topo },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
        ) { Text(if (topo) "Mapa: Topo" else "Mapa: OSM") }
        Text(
            if (topo) "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)"
            else "© OpenStreetMap contributors",
            modifier = Modifier.align(Alignment.BottomStart)
                .background(Color(0xAAFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 10.sp, color = Color.Black
        )
    }
}
