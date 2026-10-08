package pl.trailtrack

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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

// ---------- lista przejazdów ----------

@Composable
fun RidesScreen(repo: Repo, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    val rides by repo.rides.collectAsState(initial = emptyList())

    val fitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val id = repo.importFit(uri)
                val msg = when {
                    id >= 0 -> "Zaimportowano przejazd z pliku FIT"
                    id == -2L -> "Ten przejazd jest już zaimportowany"
                    else -> "Nie udało się wczytać pliku FIT (brak punktów GPS?)"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { LargeTitle("Przejazdy") }
        item {
            IosButton("Importuj plik FIT (Garmin Edge)", c.blue, Modifier.fillMaxWidth(), filled = false) {
                fitPicker.launch(arrayOf("*/*"))
            }
        }
        if (rides.isNotEmpty()) {
            item {
                TileGrid(
                    listOf(
                        "Przejazdy" to rides.size.toString(),
                        "Razem" to fmtKm(rides.sumOf { it.distanceM }),
                        "Podjazd" to fmtM(rides.sumOf { it.ascentM }),
                        "Czas w ruchu" to fmtTime(rides.sumOf { it.movingSec })
                    )
                )
            }
        } else {
            item {
                IosCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Brak zapisanych przejazdów.\nNagraj pierwszy w zakładce „Nagrywaj” albo zaimportuj plik FIT z Edge'a.",
                        color = c.secondary, modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }
        items(rides, key = { it.id }) { r -> RideCard(r) { onOpen(r.id) } }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    val c = ios()
    Column {
        Text(label, fontSize = 11.sp, color = c.secondary)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}

@Composable
private fun RideCard(r: RideEntity, onClick: () -> Unit) {
    val c = ios()
    IosCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            Text(r.name.ifBlank { "Przejazd" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Text(fmtDate(r.id), fontSize = 13.sp, color = c.secondary)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("Dystans", fmtKm(r.distanceM))
                MiniStat("Czas", fmtTime(r.movingSec))
                MiniStat("Podjazd", fmtM(r.ascentM))
                if (r.tss > 0) MiniStat(if (r.tssSource == 1) "TSS" else "hrTSS", fmt0(r.tss))
                else MiniStat("Śr.", fmtKmh(if (r.movingSec > 0) r.distanceM / r.movingSec else 0.0))
            }
            Spacer(Modifier.height(10.dp))
            TerrainBar(decodeTerrain(r.terrainEnc))
        }
    }
}

// ---------- szczegóły przejazdu ----------

@Composable
fun RideDetailScreen(repo: Repo, id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<LoadedRide?>(null) }
    var stats by remember { mutableStateOf<RideStats?>(null) }
    var an by remember { mutableStateOf<RideAnalytics?>(null) }
    var name by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val d = repo.loadRide(id)
        data = d
        if (d != null) {
            name = d.ride.name
            stats = withContext(Dispatchers.Default) { computeStats(d.points, Prefs.weightKg.toDouble(), d.laps) }
            an = withContext(Dispatchers.Default) { computeAnalytics(d.points, Prefs.thresholds()) }
        }
    }

    fun export(format: String, mime: String, title: String) {
        scope.launch {
            val f = repo.exportRide(id, format) ?: return@launch
            shareFile(ctx, f, mime, title)
        }
    }

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Przejazd", onBack)
        val d = data
        val st = stats
        if (d == null || st == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Wczytywanie…", color = c.secondary)
            }
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.clickable { showRename = true }) {
                    Text(name.ifBlank { "Przejazd" } + "  ✎", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label)
                    Text(fmtDate(id), fontSize = 14.sp, color = c.secondary)
                }

                TrackMap(
                    d.points, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)), fit = true
                )

                TileGrid(
                    listOf(
                        "Dystans" to fmtKm(st.distanceM),
                        "Czas w ruchu" to fmtTime(st.movingSec),
                        "Śr. prędkość" to fmtKmh(st.avgSpeedMs),
                        "Maks. prędkość" to fmtKmh(st.maxSpeedMs),
                        "Podjazd" to fmtM(st.ascentM),
                        "Zjazd" to fmtM(st.descentM),
                        "Kalorie" to fmtKcal(d.ride.kcal),
                        "Najszybszy km" to (if (st.bestKmSec > 0) fmtTime(st.bestKmSec) else "–")
                    )
                )

                // ----- analityka z czujników -----
                val a = an
                if (a != null && (a.hasHr || a.hasPower)) {
                    val items = ArrayList<Pair<String, String>>()
                    if (a.tssSource > 0) items.add((if (a.tssSource == 1) "TSS" else "hrTSS") to fmt0(a.tss))
                    if (a.hasPower) {
                        items.add("Śr. moc" to "${a.avgPower} W")
                        items.add("Moc znorm. (NP)" to "${a.np} W")
                        items.add("IF" to String.format("%.2f", a.intensity))
                        items.add("VI" to String.format("%.2f", a.vi))
                        items.add("Maks. moc" to "${a.maxPower} W")
                        items.add("Praca" to "${fmt0(a.kj)} kJ")
                    }
                    if (a.hasHr) {
                        items.add("Śr. tętno" to "${a.avgHr} bpm")
                        items.add("Maks. tętno" to "${a.maxHr} bpm")
                    }
                    if (a.ef > 0) items.add("EF (NP/HR)" to String.format("%.2f", a.ef))
                    val dec = a.decouplingPct
                    if (dec != null) items.add("Rozprzężenie Pa:Hr" to fmtPct(dec))
                    TileGrid(items)

                    val red = IntArray(st.series.distKm.size) { 0xFFFF3B30.toInt() }
                    val purple = IntArray(st.series.distKm.size) { 0xFFAF52DE.toInt() }
                    if (a.hasHr) ChartCard("Tętno") {
                        LineAreaChart(st.series.distKm, st.series.hr, red, "bpm", Modifier.fillMaxWidth().height(150.dp), fill = false)
                    }
                    if (a.hasPower) ChartCard("Moc (uśredniona ok. 10 s)") {
                        LineAreaChart(st.series.distKm, st.series.power, purple, "W", Modifier.fillMaxWidth().height(150.dp), fill = false, minZero = true)
                    }
                    if (a.hasHr) ZoneBarsCard("Czas w strefach tętna (wg LTHR ${Prefs.lthr})", HR_ZONE_NAMES, a.hrZoneSec, HR_ZONE_COLORS)
                    if (a.hasPower) {
                        ZoneBarsCard("Czas w strefach mocy (wg FTP ${Prefs.ftp} W)", POWER_ZONE_NAMES, a.powerZoneSec, POWER_ZONE_COLORS)
                        PowerCurveGroup(a.powerCurve, Prefs.weightKg.toDouble())
                    }
                }

                ElevationChartCard(st.series)
                SpeedChartCard(st.series)
                TerrainSpeedCard(st)

                IosGroup(header = "Więcej statystyk") {
                    IosRow("Czas całkowity", fmtTime(st.elapsedSec)); IosDivider()
                    IosRow("Czas postoju", fmtTime(st.stoppedSec)); IosDivider()
                    IosRow("Czas pauzy", fmtTime(st.pausedSec)); IosDivider()
                    IosRow("Wysokość min / maks.", "${fmtM(st.minEle)} / ${fmtM(st.maxEle)}"); IosDivider()
                    IosRow("Maks. nachylenie podjazdu", fmtPct(st.maxGradePct)); IosDivider()
                    IosRow("Maks. nachylenie zjazdu", fmtPct(st.minGradePct)); IosDivider()
                    IosRow("Przewyższenie na km", fmtM(st.ascentPerKm)); IosDivider()
                    IosRow("Punkty GPS", d.points.size.toString())
                }

                SplitsGroup("Okrążenia", st.laps)
                SplitsGroup("Podziały co 1 km", st.splits)

                IosButton("Eksportuj TCX (TrainingPeaks)", c.blue, Modifier.fillMaxWidth()) {
                    export("tcx", "application/vnd.garmin.tcx+xml", "Eksportuj TCX")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IosButton("GPX", c.blue, Modifier.weight(1f), filled = false) {
                        export("gpx", "application/gpx+xml", "Eksportuj GPX")
                    }
                    IosButton("CSV", c.blue, Modifier.weight(1f), filled = false) {
                        export("csv", "text/csv", "Eksportuj CSV")
                    }
                }
                IosButton("Usuń przejazd", c.red, Modifier.fillMaxWidth(), filled = false) { showDelete = true }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDelete) {
        IosAlert(
            title = "Usunąć przejazd?", message = "Tej operacji nie można cofnąć.",
            confirmText = "Usuń", confirmColor = c.red,
            onConfirm = { showDelete = false; scope.launch { repo.deleteRide(id); onBack() } },
            dismissText = "Anuluj", onDismiss = { showDelete = false }
        )
    }
    if (showRename) {
        IosInputDialog(
            title = "Nazwa przejazdu", initial = name,
            onConfirm = { n ->
                showRename = false
                if (n.isNotBlank()) { name = n; scope.launch { repo.renameRide(id, n) } }
            },
            onDismiss = { showRename = false }
        )
    }
}
