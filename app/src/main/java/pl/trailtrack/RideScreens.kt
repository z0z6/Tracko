package pl.trailtrack

import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- lista przejazdów ----------

@Composable
fun RidesScreen(repo: Repo, onOpen: (Long) -> Unit) {
    val c = ios()
    val rides by repo.rides.collectAsState(initial = emptyList())
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { LargeTitle("Przejazdy") }
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
                        "Brak zapisanych przejazdów.\nNagraj pierwszy w zakładce „Nagrywaj”.",
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
                MiniStat("Śr.", fmtKmh(if (r.movingSec > 0) r.distanceM / r.movingSec else 0.0))
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
    var name by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val d = repo.loadRide(id)
        data = d
        if (d != null) {
            name = d.ride.name
            stats = withContext(Dispatchers.Default) { computeStats(d.points, Prefs.weightKg.toDouble(), d.laps) }
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
                        "Kalorie (szac.)" to fmtKcal(st.kcal),
                        "Najszybszy km" to (if (st.bestKmSec > 0) fmtTime(st.bestKmSec) else "–")
                    )
                )

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

                IosButton("Eksportuj GPX", c.blue, Modifier.fillMaxWidth()) {
                    scope.launch {
                        val f = repo.exportGpx(id) ?: return@launch
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/gpx+xml"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Eksportuj GPX"))
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
