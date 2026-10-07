package pl.trailtrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

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

private data class NavInfo(val idx: Int, val offM: Double, val remainingM: Double)

private fun cumulative(route: List<GeoPoint>): DoubleArray {
    val cum = DoubleArray(route.size)
    for (i in 1 until route.size) {
        cum[i] = cum[i - 1] + haversine(route[i - 1].latitude, route[i - 1].longitude, route[i].latitude, route[i].longitude)
    }
    return cum
}

private fun nearestOnRoute(route: List<GeoPoint>, cum: DoubleArray, lat: Double, lon: Double): NavInfo {
    val k = cos(Math.toRadians(lat))
    var best = Double.MAX_VALUE
    var bi = 0
    for (i in route.indices) {
        val dx = (lon - route[i].longitude) * k * 111320.0
        val dy = (lat - route[i].latitude) * 110540.0
        val d = sqrt(dx * dx + dy * dy)
        if (d < best) { best = d; bi = i }
    }
    return NavInfo(bi, best, cum.last() - cum[bi])
}

@Composable
fun RecordScreen(repo: Repo) {
    val ctx = LocalContext.current
    val c = ios()
    val live by Live.state.collectAsState()
    val weight = Prefs.weightKg.toDouble()
    val stats = remember(live.points, live.laps) { computeStats(live.points, weight, live.laps) }
    val last = live.points.lastOrNull()
    var confirmStop by remember { mutableStateOf(false) }

    // czujniki i analityka na żywo
    val sensorDevices by SensorHub.devices.collectAsState()
    val sl by SensorHub.live.collectAsState()
    val sensorsOn = sensorDevices.any { it.connected }
    val nowMs = SystemClock.elapsedRealtime()
    val hrNow = if (nowMs - sl.hrTime < 5000) sl.hr else 0
    val pwNow = if (nowMs - sl.powerTime < 5000) sl.power else 0
    val cadNow = if (nowMs - sl.cadTime < 5000) sl.cadence else 0
    val an = remember(live.points.size / 10, sensorsOn) {
        if (live.points.size >= 30 && sensorsOn) computeAnalytics(live.points, Prefs.thresholds()) else null
    }

    // trasa do podążania
    val routeId = Prefs.selectedRouteId
    var route by remember(routeId) { mutableStateOf<LoadedRoute?>(null) }
    LaunchedEffect(routeId) { route = if (routeId > 0) repo.loadRoute(routeId) else null }
    val routeGeo = remember(route) { route?.points?.map { GeoPoint(it.lat, it.lon) } ?: emptyList() }
    val routeCum = remember(routeGeo) { cumulative(routeGeo) }
    val nav = remember(last?.lat, last?.lon, routeGeo) {
        if (last != null && routeGeo.size >= 2) nearestOnRoute(routeGeo, routeCum, last.lat, last.lon) else null
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording(ctx)
        else Toast.makeText(ctx, "Bez zgody na lokalizację nie nagram trasy", Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                TrackMap(live.points, Modifier.fillMaxSize(), route = routeGeo, follow = live.recording, fit = true)
                if (live.recording && live.pause != PauseKind.NONE) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(bottom = 18.dp, start = 10.dp)
                            .clip(RoundedCornerShape(50)).background(c.orange).padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (live.pause == PauseKind.AUTO) "⏸ Auto-pauza" else "⏸ Wstrzymano",
                            color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                        )
                    }
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (route != null) {
                    val r = route
                    IosCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Trasa: ${r?.route?.name ?: ""}", fontWeight = FontWeight.SemiBold, color = c.label)
                            if (nav != null) {
                                if (nav.offM > 50.0) {
                                    Text("⚠ Poza trasą (${String.format("%.0f", nav.offM)} m)", color = c.red, fontSize = 14.sp)
                                } else {
                                    Text("Do końca: ${fmtKm(nav.remainingM)}", color = c.green, fontSize = 14.sp)
                                }
                            } else {
                                Text("Długość: ${fmtKm(r?.route?.distanceM ?: 0.0)}", color = c.secondary, fontSize = 14.sp)
                            }
                        }
                    }
                }

                TileGrid(
                    listOf(
                        "Dystans" to fmtKm(stats.distanceM),
                        "Czas w ruchu" to fmtTime(stats.movingSec),
                        "Prędkość" to fmtKmh(if (live.pause == PauseKind.NONE) (last?.speed ?: 0.0) else 0.0),
                        "Średnia" to fmtKmh(stats.avgSpeedMs),
                        "Podjazd" to fmtM(stats.ascentM),
                        "Kalorie (szac.)" to fmtKcal(stats.kcal)
                    )
                )

                if (sensorsOn) {
                    val hrColor = if (hrNow > 0) androidx.compose.ui.graphics.Color(HR_ZONE_COLORS[hrZoneIndex(hrNow, Prefs.lthr)]) else null
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Tętno", if (hrNow > 0) "$hrNow bpm" else "–", Modifier.weight(1f), accent = hrColor)
                        StatTile("Moc", if (pwNow > 0) "$pwNow W" else "–", Modifier.weight(1f))
                        StatTile("Kadencja", if (cadNow > 0) "$cadNow rpm" else "–", Modifier.weight(1f))
                    }
                    val a = an
                    if (a != null) {
                        val live2 = ArrayList<Pair<String, String>>()
                        if (a.hasPower) live2.add("NP" to "${a.np} W")
                        if (a.tssSource > 0) live2.add((if (a.tssSource == 1) "TSS" else "hrTSS") to fmt0(a.tss))
                        if (a.hasHr) live2.add("Śr. tętno" to "${a.avgHr} bpm")
                        if (a.hasPower) live2.add("Śr. moc" to "${a.avgPower} W")
                        if (live2.isNotEmpty()) TileGrid(live2)
                    }
                }

                Text("Nawierzchnia – przełączaj w trakcie jazdy", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Terrain.values().toList().chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { t ->
                                TerrainTile(t, live.terrain == t, Modifier.weight(1f)) { Live.setTerrain(t) }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                if (stats.laps.isNotEmpty()) {
                    IosGroup(header = "Okrążenia") {
                        stats.laps.reversed().take(4).forEachIndexed { i, s ->
                            if (i > 0) IosDivider()
                            IosRow(s.label, "${fmtKm(s.distanceM)} · ${fmtKmh(s.avgSpeedMs)}")
                        }
                    }
                }
            }
        }

        // przyciski przypięte do dołu
        Row(
            Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!live.recording) {
                IosButton("Start", c.green, Modifier.weight(1f)) {
                    val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (fine) {
                        startRecording(ctx)
                    } else {
                        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        permLauncher.launch(perms.toTypedArray())
                    }
                }
            } else {
                if (live.pause == PauseKind.NONE) {
                    IosButton("Pauza", c.orange, Modifier.weight(1f)) { Live.pause() }
                } else {
                    IosButton("Wznów", c.green, Modifier.weight(1f)) { Live.resume() }
                }
                IosButton("Okrążenie", c.blue, Modifier.weight(1f), filled = false) { Live.lap() }
                IosButton("Stop", c.red, Modifier.weight(1f)) { confirmStop = true }
            }
        }
    }

    if (confirmStop) {
        IosAlert(
            title = "Zakończyć przejazd?",
            message = "Trasa zostanie zapisana w historii.",
            confirmText = "Zapisz", onConfirm = { confirmStop = false; stopRecording(ctx) },
            dismissText = "Wróć", onDismiss = { confirmStop = false }
        )
    }
}
