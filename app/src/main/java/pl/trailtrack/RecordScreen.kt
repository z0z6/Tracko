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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

private fun startRecording(ctx: Context, sport: Sport) {
    if (sport.gps) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(ctx, "Włącz GPS (lokalizację) w telefonie", Toast.LENGTH_LONG).show()
        }
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


private fun fmtStep(x: Double): String =
    if (x >= 1.0) String.format(Locale.getDefault(), "%.0f", x) else String.format(Locale.getDefault(), "%.1f", x)

/** [−duży][−mały] wartość [+mały][+duży] – ręczna zmiana wartości (prędkość i nachylenie bieżni). */
@Composable
private fun StepControl(title: String, value: String, small: Double, big: Double, onDelta: (Double) -> Unit) {
    val c = ios()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, color = c.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IosButton("−" + fmtStep(big), c.blue, Modifier.weight(1f), filled = false) { onDelta(-big) }
            IosButton("−" + fmtStep(small), c.blue, Modifier.weight(1f), filled = false) { onDelta(-small) }
            Box(Modifier.weight(1.3f), contentAlignment = Alignment.Center) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, maxLines = 1)
            }
            IosButton("+" + fmtStep(small), c.blue, Modifier.weight(1f), filled = false) { onDelta(small) }
            IosButton("+" + fmtStep(big), c.blue, Modifier.weight(1f), filled = false) { onDelta(big) }
        }
    }
}

private fun sportHint(sport: Sport): String = when (sport) {
    Sport.TREADMILL -> "Bez GPS. Ustaw prędkość i nachylenie tak jak na bieżni – dystans liczy się z prędkości, a przewyższenie z nachylenia."
    Sport.SWIMMING -> "Basen, bez GPS. Dotykaj „+ Długość” po każdej przepłyniętej długości. Telefon trzymaj poza wodą."
    Sport.STRENGTH -> "Bez GPS. Czas, tętno (z paska BLE) i kalorie. „Seria” zaznacza koniec serii ćwiczenia."
    else -> ""
}

@Composable
fun RecordScreen(repo: Repo) {
    val ctx = LocalContext.current
    val c = ios()
    val haptic = LocalHapticFeedback.current
    val live by Live.state.collectAsState()
    val sport = live.sport
    val weight = Prefs.weightKg.toDouble()
    val stats = remember(live.points, live.laps, sport) { computeStats(live.points, weight, live.laps, sport) }
    val last = live.points.lastOrNull()
    val activeSec = live.activeMs / 1000.0
    var confirmStop by remember { mutableStateOf(false) }
    var showPoolInput by remember { mutableStateOf(false) }
    var poolM by remember { mutableIntStateOf(Prefs.poolM) }
    val onAccent = if (c.blue.luminance() > 0.5f) Color.Black else Color.White

    // zapamiętane ustawienia bieżni
    LaunchedEffect(Unit) {
        if (!Live.state.value.recording) {
            Live.setTreadSpeed(Prefs.treadKmh / 3.6)
            Live.setIncline(Prefs.treadIncline.toDouble())
        }
    }

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

    // trasa do podążania (tylko aktywności z GPS)
    val routeId = Prefs.selectedRouteId
    var route by remember(routeId) { mutableStateOf<LoadedRoute?>(null) }
    LaunchedEffect(routeId) { route = if (routeId > 0) repo.loadRoute(routeId) else null }
    val routeGeo = remember(route) { route?.points?.map { GeoPoint(it.lat, it.lon) } ?: emptyList() }
    val routeCum = remember(routeGeo) { cumulative(routeGeo) }
    val nav = remember(last?.lat, last?.lon, routeGeo, sport) {
        if (sport.gps && last != null && routeGeo.size >= 2) nearestOnRoute(routeGeo, routeCum, last.lat, last.lon) else null
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording(ctx, Live.state.value.sport)
        else Toast.makeText(ctx, "Bez zgody na lokalizację nie nagram trasy", Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SportPicker(sport, enabled = !live.recording) { sp ->
                Live.setSport(sp)
                Prefs.sport = sp.id
            }

            if (sport.gps) {
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    TrackMap(
                        live.points, Modifier.fillMaxSize(), route = routeGeo, follow = live.recording, fit = true,
                        activeTerrain = if (live.recording) live.terrain else null
                    )
                    if (live.recording && live.pause != PauseKind.NONE) {
                        Box(
                            Modifier.align(Alignment.BottomStart).padding(bottom = 18.dp, start = 10.dp)
                                .clip(RoundedCornerShape(50)).background(c.orange).padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIconView(AppIcon.Pause, Color.White, Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (live.pause == PauseKind.AUTO) "Auto-pauza" else "Wstrzymano",
                                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!sport.gps) {
                    IosCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIconView(sport.icon, c.blue, Modifier.size(34.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(sport.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.label)
                                Text(sportHint(sport), color = c.secondary, fontSize = 13.sp)
                                if (live.recording && live.pause != PauseKind.NONE) {
                                    Text("Wstrzymano", color = c.orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                if (sport.gps && route != null) {
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

                val tiles: List<Pair<String, String>> = when (sport) {
                    Sport.CYCLING, Sport.RUNNING, Sport.SKIING -> listOf(
                        "Dystans" to fmtKm(stats.distanceM),
                        "Czas w ruchu" to fmtTime(stats.movingSec),
                        speedLabel(sport) to fmtSpeedFor(sport, if (live.pause == PauseKind.NONE) (last?.speed ?: 0.0) else 0.0),
                        (if (sport.pace == 0) "Średnia" else "Śr. tempo") to fmtSpeedFor(sport, stats.avgSpeedMs),
                        "Podjazd" to fmtM(stats.ascentM),
                        "Kalorie (szac.)" to fmtKcal(stats.kcal)
                    )
                    Sport.TREADMILL -> listOf(
                        "Czas" to fmtTime(activeSec),
                        "Dystans" to fmtKm(stats.distanceM),
                        "Tempo" to fmtSpeedFor(sport, live.treadSpeedMs),
                        "Śr. tempo" to fmtSpeedFor(sport, stats.avgSpeedMs),
                        "Podjazd" to fmtM(stats.ascentM),
                        "Kalorie (szac.)" to fmtKcal(stats.kcal)
                    )
                    Sport.SWIMMING -> {
                        val lastLen = if (last != null && !last.brk && live.points.size >= 2)
                            (last.time - live.points[live.points.size - 2].time) / 1000.0 else 0.0
                        listOf(
                            "Długości" to live.lengths.toString(),
                            "Dystans" to fmtM(stats.distanceM),
                            "Czas" to fmtTime(activeSec),
                            "Śr. tempo" to fmtSpeedFor(sport, if (activeSec > 0) stats.distanceM / activeSec else 0.0),
                            "Ostatnia dł." to (if (lastLen > 0) fmtTime(lastLen) else "–"),
                            "Kalorie (szac.)" to fmtKcal(stats.kcal)
                        )
                    }
                    Sport.STRENGTH -> listOf(
                        "Czas" to fmtTime(activeSec),
                        "Kalorie (szac.)" to fmtKcal(stats.kcal),
                        "Serie" to live.laps.size.toString()
                    )
                }
                TileGrid(tiles)

                // ----- bieżnia: ręczne ustawienie prędkości i nachylenia -----
                if (sport == Sport.TREADMILL) {
                    val kmh = Math.round(live.treadSpeedMs * 36.0) / 10.0
                    val inc = Math.round(live.inclinePct * 10.0) / 10.0
                    IosCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StepControl("Prędkość bieżni (km/h)", String.format(Locale.getDefault(), "%.1f", kmh), 0.1, 1.0) { d ->
                                val v = (Math.round((kmh + d) * 10.0) / 10.0).coerceIn(0.0, 30.0)
                                Live.setTreadSpeed(v / 3.6)
                                Prefs.treadKmh = v.toFloat()
                            }
                            StepControl("Nachylenie (%)", String.format(Locale.getDefault(), "%.1f", inc), 0.5, 1.0) { d ->
                                val v = (Math.round((inc + d) * 10.0) / 10.0).coerceIn(-3.0, 15.0)
                                Live.setIncline(v)
                                Prefs.treadIncline = v.toFloat()
                            }
                        }
                    }
                }

                // ----- basen: długość basenu i przycisk „+ Długość” -----
                if (sport == Sport.SWIMMING) {
                    IosCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (live.recording) {
                                Text("Basen: ${Prefs.poolM} m", color = c.label, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("Długość basenu", fontSize = 13.sp, color = c.secondary)
                                IosSegmented(
                                    listOf("25 m", "50 m", if (poolM == 25 || poolM == 50) "Inny…" else "Inny ($poolM m)"),
                                    when (poolM) { 25 -> 0; 50 -> 1; else -> 2 },
                                    { i ->
                                        when (i) {
                                            0 -> { poolM = 25; Prefs.poolM = 25 }
                                            1 -> { poolM = 50; Prefs.poolM = 50 }
                                            else -> showPoolInput = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                    val canTap = live.recording && live.pause == PauseKind.NONE
                    Box(
                        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(22.dp))
                            .background(if (canTap) c.blue else c.fill)
                            .clickable(enabled = canTap) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Live.addLength(Prefs.poolM)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("+ DŁUGOŚĆ", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (canTap) onAccent else c.secondary)
                            Text(
                                if (live.recording) "${live.lengths} dł. · ${live.lengths * Prefs.poolM} m"
                                else "Naciśnij Start, potem dotykaj po każdej długości",
                                fontSize = 14.sp, textAlign = TextAlign.Center,
                                color = if (canTap) onAccent else c.secondary
                            )
                        }
                    }
                }

                if (live.recording && Prefs.goalType != 0 && Prefs.goalValue > 0f) {
                    val gt = Prefs.goalType
                    val goal = Prefs.goalValue.toDouble()
                    val cur = when (gt) {
                        1 -> stats.distanceM / 1000.0
                        2 -> stats.movingSec / 60.0
                        else -> stats.ascentM
                    }
                    val unit = when (gt) { 1 -> "km"; 2 -> "min"; else -> "m" }
                    val frac = (cur / goal).toFloat().coerceIn(0f, 1f)
                    IosCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(if (cur >= goal) "Cel osiągnięty ✓" else "Cel", fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                                Text("${fmt1(cur)} / ${fmt1(goal)} $unit", color = c.secondary, fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.fill)) {
                                Box(Modifier.fillMaxWidth(frac).height(8.dp).background(if (cur >= goal) c.green else c.blue))
                            }
                        }
                    }
                }

                if (sensorsOn) {
                    val hrColor = if (hrNow > 0) Color(HR_ZONE_COLORS[hrZoneIndex(hrNow, Prefs.lthr)]) else null
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

                if (sport.gps) {
                    Text("Nawierzchnia – przełączaj w trakcie aktywności", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 4.dp))
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
                }

                if (stats.laps.isNotEmpty()) {
                    IosGroup(header = if (sport == Sport.STRENGTH || sport == Sport.SWIMMING) "Serie" else "Okrążenia") {
                        stats.laps.reversed().take(4).forEachIndexed { i, s ->
                            if (i > 0) IosDivider()
                            IosRow(
                                s.label,
                                if (sport.hasDistance) "${fmtKm(s.distanceM)} · ${fmtSpeedFor(sport, s.avgSpeedMs)}" else fmtTime(s.activeSec)
                            )
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
                IosButton("Start", c.green, Modifier.weight(1f), icon = AppIcon.Play) {
                    val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (!sport.gps || fine) {
                        startRecording(ctx, sport)
                    } else {
                        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        permLauncher.launch(perms.toTypedArray())
                    }
                }
            } else {
                if (live.pause == PauseKind.NONE) {
                    IosButton("Pauza", c.orange, Modifier.weight(1f), icon = AppIcon.Pause) { Live.pause() }
                } else {
                    IosButton("Wznów", c.green, Modifier.weight(1f), icon = AppIcon.Play) { Live.resume() }
                }
                IosButton(
                    if (sport == Sport.STRENGTH || sport == Sport.SWIMMING) "Seria" else "Okrążenie",
                    c.blue, Modifier.weight(1f), filled = false, icon = AppIcon.Lap
                ) { Live.lap() }
                IosButton("Stop", c.red, Modifier.weight(1f), icon = AppIcon.Stop) { confirmStop = true }
            }
        }
    }

    if (confirmStop) {
        IosAlert(
            title = "Zakończyć aktywność?",
            message = "${sport.label} zostanie zapisana w historii.",
            confirmText = "Zapisz", onConfirm = { confirmStop = false; stopRecording(ctx) },
            dismissText = "Wróć", onDismiss = { confirmStop = false }
        )
    }
    if (showPoolInput) {
        IosInputDialog(
            title = "Długość basenu (m)", initial = poolM.toString(),
            onConfirm = { txt ->
                val v = txt.replace(',', '.').toDoubleOrNull()?.toInt()
                if (v != null && v in 10..100) { poolM = v; Prefs.poolM = v }
                showPoolInput = false
            },
            onDismiss = { showPoolInput = false }
        )
    }
}
