package pl.trailtrack

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
private fun ThresholdSlider(title: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    val c = ios()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
            Text("$value $unit", fontSize = 17.sp, color = c.secondary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}

@Composable
fun AnalyticsScreen(repo: Repo) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    val rides by repo.rides.collectAsState(initial = emptyList())
    var ftp by remember { mutableIntStateOf(Prefs.ftp) }
    var lthr by remember { mutableIntStateOf(Prefs.lthr) }
    var maxHr by remember { mutableIntStateOf(Prefs.maxHr) }
    var restHr by remember { mutableIntStateOf(Prefs.restHr) }
    var busy by remember { mutableStateOf(false) }

    val pmc = remember(rides) { buildPmc(rides, 90) }
    val weeks = remember(rides) { weeklySums(rides, 8) }
    val last = pmc.lastOrNull()
    val blue = c.blue
    val pink = Color(0xFFFF2D55)
    val yellow = Color(0xFFFFCC00)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LargeTitle("Analiza")

        if (rides.isNotEmpty()) {
            IosGroup(header = "Podsumowanie wg aktywności", footer = "Wszystkie zapisane dane, osobno dla każdej kategorii.") {
                val groups = Sport.values().map { sp -> sp to rides.filter { it.sport == sp.id } }.filter { it.second.isNotEmpty() }
                groups.forEachIndexed { i, (sp, list) ->
                    if (i > 0) IosDivider()
                    val time = fmtTime(list.sumOf { it.movingSec })
                    val value = if (sp.hasDistance) {
                        val d = list.sumOf { it.distanceM }
                        "${list.size} · ${if (sp == Sport.SWIMMING) fmtM(d) else fmtKm(d)} · $time"
                    } else "${list.size} · $time"
                    IosRow(sp.label, value, leading = { IconBadge(sp.icon, c.blue) })
                }
            }
        }

        if (last == null) {
            IosCard(Modifier.fillMaxWidth()) {
                Text(
                    "Wykres formy (CTL/ATL/TSB) pojawi się, gdy będziesz mieć przejazdy z tętnem lub mocą. " +
                        "Podłącz czujniki w Ustawieniach → Czujniki, ustaw progi poniżej i nagraj przejazd " +
                        "albo zaimportuj plik FIT z Edge'a.",
                    color = c.secondary, modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            TileGrid(
                listOf(
                    "Sprawność (CTL)" to fmt0(last.ctl),
                    "Zmęczenie (ATL)" to fmt0(last.atl),
                    "Forma (TSB)" to fmt0(last.tsb),
                    "TSS ostatnie 7 dni" to fmt0(weeks.first().tss)
                )
            )
            if (pmc.size >= 2) {
                ChartCard("Performance Management Chart (ostatnie ${pmc.size} dni)") {
                    Column {
                        LinesChart(
                            lines = listOf(
                                pmc.map { it.ctl.toFloat() }.toFloatArray() to blue,
                                pmc.map { it.atl.toFloat() }.toFloatArray() to pink,
                                pmc.map { it.tsb.toFloat() }.toFloatArray() to yellow
                            ),
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            leftLabel = "${pmc.size} dni temu", rightLabel = "dziś"
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("● CTL", color = blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("● ATL", color = pink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("● TSB", color = yellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            ChartCard("TSS tygodniowo (8 tygodni)") {
                val ordered = (7 downTo 0).map { w -> weeks.firstOrNull { it.weeksAgo == w } ?: WeekSum(w, 0.0, 0.0, 0.0) }
                BarsChart(
                    values = ordered.map { it.tss },
                    labels = ordered.map { if (it.weeksAgo == 0) "teraz" else "-${it.weeksAgo}t" },
                    color = blue,
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                )
                Spacer(Modifier.height(6.dp))
                val cur = weeks.first()
                Text(
                    "Ten tydzień: ${fmt1(cur.hours)} h • ${fmt0(cur.km)} km • TSS ${fmt0(cur.tss)}",
                    fontSize = 13.sp, color = c.secondary
                )
            }
        }

        IosGroup(
            header = "Progi zawodnika",
            footer = "Używane do stref, NP/IF/TSS i hrTSS. Po zmianie użyj „Przelicz wszystkie przejazdy”. " +
                "TrainingPeaks liczy własne metryki z Twoich progów ustawionych w jego profilu."
        ) {
            ThresholdSlider("FTP", ftp, 80..450, "W") { ftp = it; Prefs.ftp = it }
            IosDivider()
            ThresholdSlider("Tętno progowe (LTHR)", lthr, 100..200, "bpm") { lthr = it; Prefs.lthr = it }
            IosDivider()
            ThresholdSlider("Tętno maksymalne", maxHr, 140..220, "bpm") { maxHr = it; Prefs.maxHr = it }
            IosDivider()
            ThresholdSlider("Tętno spoczynkowe", restHr, 30..90, "bpm") { restHr = it; Prefs.restHr = it }
        }

        IosButton(
            if (busy) "Przeliczanie…" else "Przelicz wszystkie przejazdy",
            c.blue, Modifier.fillMaxWidth(), filled = false
        ) {
            if (!busy) {
                busy = true
                scope.launch {
                    val n = repo.recomputeAll()
                    busy = false
                    Toast.makeText(ctx, "Przeliczono przejazdów: $n", Toast.LENGTH_LONG).show()
                }
            }
        }

        IosGroup(
            header = "Eksport do TrainingPeaks i innych",
            footer = "TrainingPeaks: zaloguj się na trainingpeaks.com → Upload (Wgraj plik) → wskaż pliki .tcx (można wiele naraz, " +
                "także rozpakowane z ZIP-a). Bezpośrednia synchronizacja przez API TrainingPeaks wymaga zatwierdzenia jako partner, " +
                "dlatego aplikacja korzysta z eksportu plików. CSV nadaje się do arkuszy i narzędzi typu Golden Cheetah."
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IosButton("ZIP z TCX – ostatnie 30 dni", c.blue, Modifier.fillMaxWidth()) {
                    scope.launch {
                        val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                        val f = repo.exportZip(since)
                        if (f == null) Toast.makeText(ctx, "Brak przejazdów w tym okresie", Toast.LENGTH_LONG).show()
                        else shareFile(ctx, f, "application/zip", "Eksport TCX (30 dni)")
                    }
                }
                IosButton("ZIP z TCX – wszystkie przejazdy", c.blue, Modifier.fillMaxWidth(), filled = false) {
                    scope.launch {
                        val f = repo.exportZip(0L)
                        if (f == null) Toast.makeText(ctx, "Brak przejazdów", Toast.LENGTH_LONG).show()
                        else shareFile(ctx, f, "application/zip", "Eksport TCX (wszystko)")
                    }
                }
                IosButton("Podsumowanie CSV (metryki wszystkich przejazdów)", c.blue, Modifier.fillMaxWidth(), filled = false) {
                    scope.launch {
                        val f = repo.exportSummaryCsv()
                        if (f == null) Toast.makeText(ctx, "Brak przejazdów", Toast.LENGTH_LONG).show()
                        else shareFile(ctx, f, "text/csv", "Podsumowanie CSV")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
