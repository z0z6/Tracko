package pl.trailtrack

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    val allRides by repo.rides.collectAsState(initial = emptyList())
    var filter by remember { mutableIntStateOf(-1) }   // -1 = wszystkie, inaczej Sport.id
    val rides = if (filter < 0) allRides else allRides.filter { it.sport == filter }
    val presentSports = remember(allRides) { allRides.map { it.sport }.toSet() }
    val filterSport = if (filter >= 0) Sport.fromId(filter) else null

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
        item { LargeTitle("Aktywności") }
        item {
            IosButton("Importuj plik FIT (Garmin Edge)", c.blue, Modifier.fillMaxWidth(), filled = false) {
                fitPicker.launch(arrayOf("*/*"))
            }
        }
        if (allRides.isNotEmpty()) {
            item { SportFilter(allRides.size, presentSports, filter) { filter = it } }
        }
        if (rides.isNotEmpty()) {
            item {
                val items = ArrayList<Pair<String, String>>()
                items.add("Aktywności" to rides.size.toString())
                if (filterSport == null || filterSport.hasDistance) items.add("Razem" to fmtKm(rides.sumOf { it.distanceM }))
                if (filterSport == null || filterSport.gps || filterSport == Sport.TREADMILL) items.add("Podjazd" to fmtM(rides.sumOf { it.ascentM }))
                items.add("Czas w ruchu" to fmtTime(rides.sumOf { it.movingSec }))
                if (filterSport != null && !filterSport.hasDistance) items.add("Kalorie" to fmtKcal(rides.sumOf { it.kcal }))
                TileGrid(items)
            }
        } else {
            item {
                IosCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (allRides.isEmpty())
                            "Brak zapisanych aktywności.\nNagraj pierwszą w zakładce „Nagrywaj” albo zaimportuj plik FIT z Edge'a."
                        else "Brak aktywności w tej kategorii.",
                        color = c.secondary, modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }
        items(rides, key = { it.id }) { r -> RideCard(r) { onOpen(r.id) } }
    }
}

/** Chipy kategorii: Wszystkie + aktywności, które mają zapisane wpisy. */
@Composable
private fun SportFilter(total: Int, present: Set<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val c = ios()
    val onAccent = if (c.blue.luminance() > 0.5f) Color.Black else Color.White
    val options = listOf(-1 to "Wszystkie ($total)") + Sport.values().filter { it.id in present || it.id == selected }.map { it.id to it.label }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, label) in options) {
            val sel = id == selected
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.clip(shape).background(if (sel) c.blue else c.card)
                    .border(1.dp, if (sel) c.blue else c.separator, shape)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (id >= 0) {
                    AppIconView(Sport.fromId(id).icon, if (sel) onAccent else c.label, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (sel) onAccent else c.label, maxLines = 1)
            }
        }
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
    val sport = Sport.fromId(r.sport)
    IosCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconView(sport.icon, c.blue, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(r.name.ifBlank { sport.label }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Text(fmtDate(r.id) + " · " + sport.label, fontSize = 13.sp, color = c.secondary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (sport.hasDistance) {
                    MiniStat("Dystans", if (sport == Sport.SWIMMING) fmtM(r.distanceM) else fmtKm(r.distanceM))
                    MiniStat("Czas", fmtTime(r.movingSec))
                    if (sport.gps || sport == Sport.TREADMILL) MiniStat("Podjazd", fmtM(r.ascentM))
                } else {
                    MiniStat("Czas", fmtTime(r.movingSec))
                    MiniStat("Kalorie", fmtKcal(r.kcal))
                    if (r.avgHr > 0) MiniStat("Śr. tętno", "${r.avgHr} bpm")
                }
                if (r.tss > 0) MiniStat(if (r.tssSource == 1) "TSS" else "hrTSS", fmt0(r.tss))
                else if (sport.hasDistance) MiniStat(if (sport.pace == 0) "Śr." else "Tempo", fmtSpeedFor(sport, if (r.movingSec > 0) r.distanceM / r.movingSec else 0.0))
            }
            if (sport.gps) {
                Spacer(Modifier.height(10.dp))
                TerrainBar(decodeTerrain(r.terrainEnc))
            }
        }
    }
}

// ---------- szczegóły przejazdu ----------

@Composable
fun RideDetailScreen(repo: Repo, id: Long, onBack: () -> Unit, onNewSegment: (Long) -> Unit) {
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
            stats = withContext(Dispatchers.Default) { computeStats(d.points, Prefs.weightKg.toDouble(), d.laps, Sport.fromId(d.ride.sport)) }
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
        IosNavBar("Aktywność", onBack)
        val d = data
        val st = stats
        if (d == null || st == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Wczytywanie…", color = c.secondary)
            }
        } else {
            val sport = Sport.fromId(d.ride.sport)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.clickable { showRename = true }) {
                    Text(name.ifBlank { sport.label } + "  ✎", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIconView(sport.icon, c.secondary, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(sport.label + " · " + fmtDate(id), fontSize = 14.sp, color = c.secondary)
                    }
                }

                if (sport.gps) {
                    TrackMap(
                        d.points, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)), fit = true
                    )
                }

                val main = ArrayList<Pair<String, String>>()
                if (sport.hasDistance) {
                    main.add("Dystans" to (if (sport == Sport.SWIMMING) fmtM(st.distanceM) else fmtKm(st.distanceM)))
                    main.add((if (sport.gps) "Czas w ruchu" else "Czas") to fmtTime(st.movingSec))
                    main.add(avgSpeedLabel(sport) to fmtSpeedFor(sport, st.avgSpeedMs))
                    main.add((if (sport.pace == 0) "Maks. prędkość" else "Najlepsze tempo") to fmtSpeedFor(sport, st.maxSpeedMs))
                    if (sport.gps || sport == Sport.TREADMILL) {
                        main.add("Podjazd" to fmtM(st.ascentM))
                        main.add("Zjazd" to fmtM(st.descentM))
                    }
                    main.add("Kalorie" to fmtKcal(d.ride.kcal))
                    main.add((if (sport == Sport.SWIMMING) "Najszybsze 100 m" else "Najszybszy km") to (if (st.bestKmSec > 0) fmtTime(st.bestKmSec) else "–"))
                    if (sport == Sport.SWIMMING) main.add("Długości" to d.points.drop(1).count { !it.brk && it.speed > 0.0 }.toString())
                } else {
                    main.add("Czas" to fmtTime(st.movingSec))
                    main.add("Kalorie" to fmtKcal(d.ride.kcal))
                    main.add("Serie" to d.laps.size.toString())
                }
                TileGrid(main)

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
                        LineAreaChart(st.series.distKm, st.series.hr, red, "bpm", Modifier.fillMaxWidth().height(150.dp), fill = false, xUnit = st.series.xUnit)
                    }
                    if (a.hasPower) ChartCard("Moc (uśredniona ok. 10 s)") {
                        LineAreaChart(st.series.distKm, st.series.power, purple, "W", Modifier.fillMaxWidth().height(150.dp), fill = false, minZero = true, xUnit = st.series.xUnit)
                    }
                    if (a.hasHr) ZoneBarsCard("Czas w strefach tętna (wg LTHR ${Prefs.lthr})", HR_ZONE_NAMES, a.hrZoneSec, HR_ZONE_COLORS)
                    if (a.hasPower) {
                        ZoneBarsCard("Czas w strefach mocy (wg FTP ${Prefs.ftp} W)", POWER_ZONE_NAMES, a.powerZoneSec, POWER_ZONE_COLORS)
                        PowerCurveGroup(a.powerCurve, Prefs.weightKg.toDouble())
                    }
                }

                if (sport.gps || (sport == Sport.TREADMILL && st.ascentM > 1.0)) ElevationChartCard(st.series)
                if (sport.hasDistance) SpeedChartCard(st.series)
                if (sport.gps) TerrainSpeedCard(st)

                IosGroup(header = "Więcej statystyk") {
                    IosRow("Czas całkowity", fmtTime(st.elapsedSec)); IosDivider()
                    IosRow("Czas postoju", fmtTime(st.stoppedSec)); IosDivider()
                    IosRow("Czas pauzy", fmtTime(st.pausedSec)); IosDivider()
                    if (sport.gps) {
                        IosRow("Wysokość min / maks.", "${fmtM(st.minEle)} / ${fmtM(st.maxEle)}"); IosDivider()
                        IosRow("Maks. nachylenie podjazdu", fmtPct(st.maxGradePct)); IosDivider()
                        IosRow("Maks. nachylenie zjazdu", fmtPct(st.minGradePct)); IosDivider()
                        IosRow("Przewyższenie na km", fmtM(st.ascentPerKm)); IosDivider()
                    }
                    IosRow(if (sport.gps) "Punkty GPS" else "Punkty danych", d.points.size.toString())
                }

                SplitsGroup(if (sport == Sport.STRENGTH || sport == Sport.SWIMMING) "Serie" else "Okrążenia", st.laps, sport)
                if (sport.hasDistance) SplitsGroup(if (sport.splitM >= 1000.0) "Podziały co 1 km" else "Podziały co ${sport.splitM.toInt()} m", st.splits, sport)

                if (sport.gps && st.distanceM >= 300.0) {
                    IosButton("Utwórz odcinek do ścigania z duchem", c.green, Modifier.fillMaxWidth(), filled = false, icon = AppIcon.Ghost) {
                        onNewSegment(id)
                    }
                }
                IosButton("Eksportuj TCX (TrainingPeaks)", c.blue, Modifier.fillMaxWidth()) {
                    export("tcx", "application/vnd.garmin.tcx+xml", "Eksportuj TCX")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (sport.gps) {
                        IosButton("GPX", c.blue, Modifier.weight(1f), filled = false) {
                            export("gpx", "application/gpx+xml", "Eksportuj GPX")
                        }
                    }
                    IosButton("CSV", c.blue, Modifier.weight(1f), filled = false) {
                        export("csv", "text/csv", "Eksportuj CSV")
                    }
                }
                IosButton("Usuń aktywność", c.red, Modifier.fillMaxWidth(), filled = false) { showDelete = true }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDelete) {
        IosAlert(
            title = "Usunąć aktywność?", message = "Tej operacji nie można cofnąć.",
            confirmText = "Usuń", confirmColor = c.red,
            onConfirm = { showDelete = false; scope.launch { repo.deleteRide(id); onBack() } },
            dismissText = "Anuluj", onDismiss = { showDelete = false }
        )
    }
    if (showRename) {
        IosInputDialog(
            title = "Nazwa aktywności", initial = name,
            onConfirm = { n ->
                showRename = false
                if (n.isNotBlank()) { name = n; scope.launch { repo.renameRide(id, n) } }
            },
            onDismiss = { showRename = false }
        )
    }
}
