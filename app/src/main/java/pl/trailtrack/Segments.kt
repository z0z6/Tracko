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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtShortDate(t: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(t))

// =====================================================================================================
//  Lista odcinków
// =====================================================================================================

@Composable
fun SegmentsScreen(repo: Repo, onBack: () -> Unit, onOpen: (String) -> Unit, onOnline: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    val segs by repo.segments.collectAsState(initial = emptyList())
    val best by repo.segBest.collectAsState(initial = emptyList())

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val msg = when (repo.importSegment(uri)) {
                    1 -> "Zaimportowano odcinek"
                    2 -> "Dodano wyniki do istniejącego odcinka"
                    else -> "Nie udało się wczytać pliku odcinka (.ttseg)"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Odcinki i duchy", onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                IosCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Odcinek tworzysz ze szczegółów aktywności albo trasy (przycisk „Utwórz odcinek”): wybierasz jego początek " +
                            "i koniec. Każdy kolejny przejazd przez odcinek zapisuje wynik automatycznie – najlepszy (albo wybrany) " +
                            "staje się „duchem”, z którym się ścigasz. Plik odcinka z wynikami możesz wysłać sobie na drugi telefon " +
                            "albo znajomym, żeby też mogli się ścigać.",
                        color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(14.dp)
                    )
                }
            }
            if (Cloud.active) {
                item {
                    IosButton("Przeglądaj odcinki online", c.green, Modifier.fillMaxWidth()) { onOnline() }
                }
            }
            item {
                IosButton("Importuj odcinek (plik .ttseg)", c.blue, Modifier.fillMaxWidth(), filled = false) { picker.launch(arrayOf("*/*")) }
            }
            if (segs.isEmpty()) {
                item {
                    Text("Brak odcinków.", color = c.secondary, modifier = Modifier.padding(8.dp))
                }
            } else {
                item {
                    IosGroup(header = "Odcinki (${segs.size})") {
                        segs.forEachIndexed { i, s ->
                            if (i > 0) IosDivider()
                            val b = best.firstOrNull { it.segmentUid == s.uid }
                            val active = Prefs.ghostSegmentUid == s.uid
                            IosRow(
                                (if (active) "👻 " else "") + s.name,
                                "${fmtKm(s.lengthM)} · " + (b?.let { fmtTime(it.best) } ?: "bez wyniku"),
                                onClick = { onOpen(s.uid) }, chevron = true,
                                leading = { IconBadge(Sport.fromId(s.sport).icon, c.blue) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================================
//  Szczegóły odcinka: ranking i wybór ducha
// =====================================================================================================

@Composable
fun SegmentDetailScreen(repo: Repo, uid: String, onBack: () -> Unit, onRace: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    var seg by remember { mutableStateOf<SegmentEntity?>(null) }
    val efforts by repo.observeEfforts(uid).collectAsState(initial = emptyList())
    var selected by remember { mutableLongStateOf(if (Prefs.ghostSegmentUid == uid) Prefs.ghostEffortId else 0L) }
    var showDelete by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(Prefs.ghostSegmentUid == uid) }

    var online by remember { mutableStateOf("") }
    LaunchedEffect(uid) {
        seg = repo.getSegment(uid)
        Cloud.autoPull(repo, uid)   // odśwież ranking z serwera w tle
    }
    val sel = efforts.firstOrNull { it.id == selected } ?: efforts.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Odcinek", onBack)
        val sg = seg
        if (sg == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Wczytywanie…", color = c.secondary) }
        } else {
            val sport = Sport.fromId(sg.sport)
            val geo = remember(sg.geom) {
                val g = decodeGeom(sg.geom)
                if (g == null) emptyList() else (0 until g.n).map { GeoPoint(g.lat[it], g.lon[it]) }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(sg.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconView(sport.icon, c.secondary, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sport.label + (if (sg.author.isNotBlank()) " · autor: ${sg.author}" else ""), fontSize = 14.sp, color = c.secondary)
                }
                TrackMap(emptyList(), Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp)), route = geo, fit = true)
                TileGrid(
                    listOf(
                        "Długość" to fmtKm(sg.lengthM),
                        "Przejazdy" to efforts.size.toString(),
                        "Najlepszy czas" to (efforts.firstOrNull()?.let { fmtTime(it.timeSec) } ?: "–"),
                        "Wybrany duch" to (sel?.let { fmtTime(it.timeSec) } ?: "–")
                    )
                )

                if (efforts.isEmpty()) {
                    IosCard(Modifier.fillMaxWidth()) {
                        Text(
                            "Brak wyników. Wybierz ten odcinek i przejedź go – pierwszy wynik zapisze się sam i zostanie Twoim duchem.",
                            color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    IosGroup(header = "Ranking – dotknij wynik, żeby wybrać ducha") {
                        efforts.forEachIndexed { i, e ->
                            if (i > 0) IosDivider()
                            IosRow(
                                "${i + 1}. ${e.athlete}" + (if (e.mine == 1) " (ja)" else ""),
                                fmtTime(e.timeSec) + " · " + fmtShortDate(e.startedAt),
                                onClick = { selected = e.id },
                                leading = {
                                    if (sel?.id == e.id) AppIconView(AppIcon.Ghost, c.blue, Modifier.size(22.dp))
                                    else Spacer(Modifier.size(22.dp))
                                }
                            )
                        }
                    }
                }

                IosButton(
                    if (efforts.isEmpty()) "Wybierz odcinek (zapisz pierwszy wynik)" else "Ścigaj się z duchem",
                    c.green, Modifier.fillMaxWidth(), icon = AppIcon.Play
                ) {
                    Prefs.ghostSegmentUid = uid
                    Prefs.ghostEffortId = sel?.id ?: 0L
                    // ustaw aktywność odcinka (poza nagrywaniem), żeby od razu ruszyć
                    Live.setSport(sport)
                    if (!Live.state.value.recording) Prefs.sport = sport.id
                    onRace()
                }
                if (active) {
                    IosButton("Wyłącz ściganie z tym odcinkiem", c.orange, Modifier.fillMaxWidth(), filled = false) {
                        Prefs.ghostSegmentUid = ""
                        Prefs.ghostEffortId = 0L
                        active = false
                    }
                }
                if (Cloud.active) {
                    IosButton(
                        if (online.isBlank()) "Wyślij do rankingu online i odśwież" else online,
                        c.green, Modifier.fillMaxWidth(), filled = false
                    ) {
                        scope.launch {
                            online = "Synchronizuję…"
                            val up = Cloud.uploadSegment(repo, uid)
                            val (sent, err) = if (up.ok) Cloud.uploadEfforts(repo, uid) else 0 to up.error
                            val pulled = Cloud.pullEfforts(repo, uid)
                            online = if (err != null) "Błąd: $err" else "Wysłano $sent wyn., pobrano $pulled nowych"
                        }
                    }
                }
                IosButton("Udostępnij odcinek (plik .ttseg)", c.blue, Modifier.fillMaxWidth(), filled = false) {
                    scope.launch {
                        val f = repo.exportSegment(uid)
                        if (f == null) Toast.makeText(ctx, "Nie udało się utworzyć pliku", Toast.LENGTH_LONG).show()
                        else shareFile(ctx, f, "application/json", "Odcinek: ${sg.name}")
                    }
                }
                if (sel != null) {
                    IosButton("Usuń wybrany wynik", c.red, Modifier.fillMaxWidth(), filled = false) {
                        val id = sel.id
                        scope.launch {
                            repo.deleteEffort(id)
                            if (Prefs.ghostEffortId == id) Prefs.ghostEffortId = 0L
                            selected = 0L
                        }
                    }
                }
                IosButton("Usuń odcinek", c.red, Modifier.fillMaxWidth(), filled = false) { showDelete = true }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDelete) {
        IosAlert(
            title = "Usunąć odcinek?", message = "Razem ze wszystkimi wynikami. Tej operacji nie można cofnąć.",
            confirmText = "Usuń", confirmColor = c.red,
            onConfirm = {
                showDelete = false
                if (Prefs.ghostSegmentUid == uid) { Prefs.ghostSegmentUid = ""; Prefs.ghostEffortId = 0L }
                scope.launch { repo.deleteSegment(uid); onBack() }
            },
            dismissText = "Anuluj", onDismiss = { showDelete = false }
        )
    }
}

// =====================================================================================================
//  Tworzenie odcinka z aktywności albo z trasy
// =====================================================================================================

private class SegSource(
    val name: String,
    val sport: Sport,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cum: DoubleArray,
    /** czas aktywny narastająco w s (tylko dla aktywności; dla trasy null) */
    val act: DoubleArray?,
    val times: LongArray?,
    val rideId: Long
)

private const val MIN_SEGMENT_M = 200f

@Composable
fun SegmentCreateScreen(repo: Repo, rideId: Long, routeId: Long, onBack: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<SegSource?>(null) }
    var failed by remember { mutableStateOf(false) }
    var segFrom by remember { mutableFloatStateOf(0f) }
    var segTo by remember { mutableFloatStateOf(0f) }
    var name by remember { mutableStateOf("") }
    var showName by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(rideId, routeId) {
        val loaded = withContext(Dispatchers.Default) {
            if (rideId > 0) {
                val d = repo.loadRide(rideId)
                if (d == null || d.points.size < 2) null else {
                    val n = d.points.size
                    val lat = DoubleArray(n) { d.points[it].lat }
                    val lon = DoubleArray(n) { d.points[it].lon }
                    val cum = DoubleArray(n)
                    val act = DoubleArray(n)
                    for (i in 1 until n) {
                        val p = d.points[i]
                        val q = d.points[i - 1]
                        if (p.brk) {
                            cum[i] = cum[i - 1]
                            act[i] = act[i - 1]
                        } else {
                            cum[i] = cum[i - 1] + haversine(q.lat, q.lon, p.lat, p.lon)
                            act[i] = act[i - 1] + (p.time - q.time) / 1000.0
                        }
                    }
                    SegSource(
                        d.ride.name.ifBlank { "Odcinek" } + " – odcinek", Sport.fromId(d.ride.sport),
                        lat, lon, cum, act, LongArray(n) { d.points[it].time }, rideId
                    )
                }
            } else {
                val r = repo.loadRoute(routeId)
                if (r == null || r.points.size < 2) null else {
                    val n = r.points.size
                    val lat = DoubleArray(n) { r.points[it].lat }
                    val lon = DoubleArray(n) { r.points[it].lon }
                    val cum = DoubleArray(n)
                    for (i in 1 until n) cum[i] = cum[i - 1] + haversine(lat[i - 1], lon[i - 1], lat[i], lon[i])
                    val live = Live.state.value.sport
                    SegSource(r.route.name.ifBlank { "Trasa" } + " – odcinek", if (live.gps) live else Sport.CYCLING, lat, lon, cum, null, null, 0L)
                }
            }
        }
        if (loaded == null) {
            failed = true
        } else {
            src = loaded
            name = loaded.name
            segFrom = 0f
            segTo = loaded.cum.last().toFloat()
        }
    }

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Nowy odcinek", onBack)
        val s = src
        if (failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nie udało się wczytać danych (za mało punktów GPS).", color = c.secondary, modifier = Modifier.padding(24.dp))
            }
        } else if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Wczytywanie…", color = c.secondary) }
        } else {
            val total = s.cum.last().toFloat()
            val iA = remember(segFrom, s) { s.cum.indexOfFirst { it >= segFrom }.coerceAtLeast(0) }
            val iB = remember(segTo, s) { s.cum.indexOfLast { it <= segTo }.coerceAtLeast(0) }
            val whole = remember(s) { s.lat.indices.map { GeoPoint(s.lat[it], s.lon[it]) } }
            val selGeo = remember(iA, iB, s) { if (iB > iA) (iA..iB).map { GeoPoint(s.lat[it], s.lon[it]) } else emptyList() }
            val len = (segTo - segFrom).coerceAtLeast(0f)

            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (s.act != null) "Zaznacz początek i koniec odcinka na swojej trasie. Twój przejazd zapisze się jako pierwszy wynik (duch)."
                    else "Zaznacz początek i koniec odcinka na trasie. Wynik pojawi się po pierwszym przejechaniu go.",
                    color = c.secondary, fontSize = 14.sp
                )
                TrackMap(
                    emptyList(), Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)),
                    route = whole, segment = selGeo, fit = true
                )
                if (total < MIN_SEGMENT_M) {
                    Text("Trasa jest krótsza niż ${MIN_SEGMENT_M.toInt()} m – za krótko na odcinek.", color = c.red)
                } else {
                    TileGrid(
                        listOf(
                            "Początek" to fmtKm(segFrom.toDouble()),
                            "Koniec" to fmtKm(segTo.toDouble()),
                            "Długość odcinka" to fmtM(len.toDouble()),
                            "Czas przejazdu" to (s.act?.let { fmtTime(it[iB] - it[iA]) } ?: "–")
                        )
                    )
                    Text("Początek", fontSize = 13.sp, color = c.secondary)
                    Slider(
                        value = segFrom, valueRange = 0f..total,
                        onValueChange = { segFrom = minOf(it, segTo - MIN_SEGMENT_M).coerceAtLeast(0f) }
                    )
                    Text("Koniec", fontSize = 13.sp, color = c.secondary)
                    Slider(
                        value = segTo, valueRange = 0f..total,
                        onValueChange = { segTo = maxOf(it, segFrom + MIN_SEGMENT_M).coerceAtMost(total) }
                    )
                    IosGroup {
                        IosRow("Nazwa odcinka", name, onClick = { showName = true }, chevron = true)
                    }
                    IosButton(if (busy) "Tworzenie…" else "Utwórz odcinek", c.green, Modifier.fillMaxWidth()) {
                        if (busy) return@IosButton
                        busy = true
                        scope.launch {
                            val geo = (iA..iB).map { s.lat[it] to s.lon[it] }
                            val effort = if (s.act != null && s.times != null) {
                                val n = iB - iA + 1
                                NewEffort(
                                    startedAt = s.times[iA], timeSec = s.act[iB] - s.act[iA], rideId = s.rideId,
                                    lat = DoubleArray(n) { s.lat[iA + it] }, lon = DoubleArray(n) { s.lon[iA + it] },
                                    t = DoubleArray(n) { s.act[iA + it] - s.act[iA] }
                                )
                            } else null
                            val uid = repo.createSegment(name.ifBlank { "Odcinek" }, s.sport, geo, effort)
                            busy = false
                            if (uid == null) Toast.makeText(ctx, "Odcinek jest za krótki", Toast.LENGTH_LONG).show()
                            else {
                                Cloud.autoSegment(repo, uid)   // jeśli włączone udostępnianie – wyślij w tle
                                onDone(uid)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showName) {
        IosInputDialog(
            title = "Nazwa odcinka", initial = name,
            onConfirm = { n -> showName = false; if (n.isNotBlank()) name = n },
            onDismiss = { showName = false }
        )
    }
}
