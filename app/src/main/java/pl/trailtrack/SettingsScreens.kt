package pl.trailtrack

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream

// ---------- ustawienia ----------

@Composable
fun SettingsScreen(onOffline: () -> Unit, onSensors: () -> Unit, onAudio: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableIntStateOf(Prefs.themeMode) }
    var accent by remember { mutableIntStateOf(Prefs.accent) }
    var autoPause by remember { mutableStateOf(Prefs.autoPause) }
    var autoLap by remember { mutableIntStateOf(Prefs.autoLapKm) }
    var weight by remember { mutableFloatStateOf(Prefs.weightKg) }
    var mapMode by remember { mutableIntStateOf(Prefs.mapMode) }
    var url by remember { mutableStateOf(Prefs.customTileUrl) }

    val lapOptions = listOf(0, 1, 5, 10)

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        var name = "mapa_offline.mbtiles"
                        ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                            val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (i >= 0 && cur.moveToFirst()) name = cur.getString(i) ?: name
                        }
                        val target = File(Configuration.getInstance().osmdroidBasePath, name)
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(target).use { out -> input.copyTo(out) }
                        }
                    }.isSuccess
                }
                Toast.makeText(
                    ctx,
                    if (ok) "Zaimportowano. Uruchom aplikację ponownie, aby wczytać mapę." else "Import nie powiódł się",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LargeTitle("Ustawienia")

        IosGroup(header = "Wygląd", footer = "Styl, tryb i kolor akcentu zmieniają się od razu. Pierwszy kolor akcentu to kolor domyślny wybranego stylu.") {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeStyle.values().forEach { st ->
                    ThemePreviewCard(st, ThemeState.style == st.ordinal, Modifier.weight(1f)) {
                        ThemeState.style = st.ordinal
                        Prefs.themeStyle = st.ordinal
                    }
                }
            }
            IosDivider()
            Column(Modifier.padding(16.dp)) {
                Text("Tryb", fontSize = 17.sp, color = c.label)
                Spacer(Modifier.height(8.dp))
                IosSegmented(
                    listOf("Auto", "Jasny", "Ciemny", "AMOLED"), themeMode,
                    { themeMode = it; ThemeState.mode = it; Prefs.themeMode = it }
                )
                Spacer(Modifier.height(16.dp))
                Text("Kolor akcentu", fontSize = 17.sp, color = c.label)
                Spacer(Modifier.height(10.dp))
                AccentPicker(accent) { accent = it; ThemeState.accent = it; Prefs.accent = it }
            }
        }

        IosGroup(
            header = "Nagrywanie",
            footer = "Auto-pauza wstrzymuje nagrywanie, gdy stoisz w miejscu, i wznawia je po ruszeniu."
        ) {
            IosSwitchRow("Auto-pauza", autoPause) { autoPause = it; Prefs.autoPause = it }
            IosDivider()
            Column(Modifier.padding(16.dp)) {
                Text("Automatyczne okrążenia", fontSize = 17.sp, color = c.label)
                Spacer(Modifier.height(8.dp))
                IosSegmented(
                    listOf("Wył.", "1 km", "5 km", "10 km"),
                    lapOptions.indexOf(autoLap).coerceAtLeast(0),
                    { autoLap = lapOptions[it]; Prefs.autoLapKm = lapOptions[it] }
                )
            }
        }

        IosGroup(
            header = "Dźwięki i głos",
            footer = "Sygnały i komunikaty głosowe: cel treningowy, najlepszy wynik, tempo, zwiększony wysiłek, GPS, zjazd z trasy."
        ) {
            IosRow("Dźwięki i komunikaty głosowe", onClick = onAudio, chevron = true, leading = { IconBadge(AppIcon.Sound, c.orange) })
        }

        IosGroup(
            header = "Czujniki",
            footer = "Pasy tętna, mierniki mocy, czujniki prędkości i kadencji (Bluetooth LE, także Garmin)."
        ) {
            IosRow("Czujniki Bluetooth", onClick = onSensors, chevron = true, leading = { IconBadge(AppIcon.Sensor, c.blue) })
        }

        IosGroup(header = "Profil", footer = "Waga służy do szacowania spalonych kalorii.") {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Waga", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                Text("${weight.toInt()} kg", fontSize = 17.sp, color = c.secondary)
            }
            Slider(
                value = weight, onValueChange = { weight = it.toInt().toFloat(); Prefs.weightKg = weight },
                valueRange = 40f..140f, modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        IosGroup(
            header = "Mapy",
            footer = "Własny serwer: adres z {z}/{x}/{y}, np. https://twoj-serwer/tiles/{z}/{x}/{y}.png. " +
                "Tylko ze własnym serwerem lub dostawcą, który zezwala na pobieranie, można pobierać mapy offline " +
                "(OSM i OpenTopoMap zabraniają masowego pobierania)."
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Domyślna warstwa", fontSize = 17.sp, color = c.label)
                Spacer(Modifier.height(8.dp))
                IosSegmented(listOf("OSM", "Topo", "Własny"), mapMode, { mapMode = it; Prefs.mapMode = it })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it; Prefs.customTileUrl = it.trim() },
                    label = { Text("URL własnego serwera kafelków") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            IosDivider()
            IosRow("Mapy offline", onClick = onOffline, chevron = true, leading = { IconBadge(AppIcon.Layers, c.green) })
            IosDivider()
            IosRow(
                "Importuj plik mapy (MBTiles/GEMF/ZIP)",
                onClick = { archivePicker.launch(arrayOf("*/*")) }, chevron = true
            )
        }

        IosGroup(header = "O aplikacji") {
            IosRow("TrailTrack", "0.5.1")
            IosDivider()
            IosRow("Dane map", "© OpenStreetMap contributors")
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- mapy offline ----------

@Composable
fun OfflineScreen(initial: BoundingBox?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val hasCustom = Prefs.customTileUrl.isNotBlank()
    val mv = rememberMapView()
    var zMax by remember { mutableFloatStateOf(15f) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var task by remember { mutableStateOf<CacheManager.CacheManagerTask?>(null) }
    val maxTiles = 6000

    val cacheMb = remember(running) {
        val f = File(Configuration.getInstance().osmdroidBasePath, "cache.db")
        if (f.exists()) f.length() / (1024 * 1024) else 0L
    }

    androidx.compose.runtime.LaunchedEffect(mv) {
        mv.setTileSource(tileSourceFor(if (hasCustom) 2 else Prefs.mapMode))
        val b = initial
        if (b != null) mv.post { mv.zoomToBoundingBox(b, false, 40) }
    }

    fun zMin(): Int = mv.zoomLevelDouble.toInt().coerceIn(1, 18)

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Mapy offline", onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mv }, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasCustom) {
                Text(
                    "Aby pobierać mapy offline, wpisz w Ustawieniach adres własnego serwera kafelków. " +
                        "OSM i OpenTopoMap nie pozwalają na masowe pobieranie. Kafelki, które oglądasz online, " +
                        "i tak zapisują się automatycznie w pamięci podręcznej.",
                    color = c.secondary, fontSize = 13.sp
                )
            } else {
                Text(
                    "Przesuń i przybliż mapę na obszar do pobrania. Pobrany zostanie widoczny fragment, " +
                        "od bieżącego zoomu (${zMin()}) do wybranego maksimum (${zMax.toInt()}).",
                    color = c.secondary, fontSize = 13.sp
                )
                Slider(value = zMax, onValueChange = { zMax = it.toInt().toFloat() }, valueRange = 10f..18f, steps = 7, enabled = !running)
                if (running || status.isNotEmpty()) {
                    Text(
                        if (running) "$status ($done / $total)" else status,
                        fontWeight = FontWeight.SemiBold, color = c.label, fontSize = 14.sp
                    )
                }
                if (!running) {
                    IosButton("Pobierz widoczny obszar", c.blue, Modifier.fillMaxWidth()) {
                        try {
                            val cm = CacheManager(mv)
                            val lo = zMin()
                            val hi = zMax.toInt().coerceAtLeast(lo)
                            val box = mv.boundingBox
                            val n = cm.possibleTilesInArea(box, lo, hi)
                            if (n > maxTiles) {
                                status = "Za dużo kafelków ($n, limit $maxTiles). Przybliż mapę lub zmniejsz maks. zoom."
                            } else {
                                running = true; done = 0; total = n; status = "Pobieranie"
                                task = cm.downloadAreaAsyncNoUI(ctx, box, lo, hi, object : CacheManager.CacheManagerCallback {
                                    override fun onTaskComplete() { running = false; status = "Gotowe: pobrano $total kafelków" }
                                    override fun onTaskFailed(errors: Int) { running = false; status = "Zakończono z błędami: $errors" }
                                    override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) { done = progress }
                                    override fun downloadStarted() {}
                                    override fun setPossibleTilesInArea(total: Int) {}
                                })
                            }
                        } catch (e: Exception) {
                            running = false
                            status = "Błąd: ${e.message}"
                        }
                    }
                } else {
                    IosButton("Anuluj pobieranie", c.red, Modifier.fillMaxWidth()) {
                        task?.cancel(true)
                        running = false
                        status = "Anulowano"
                    }
                }
            }
            Text("Pamięć kafelków: ok. $cacheMb MB", fontSize = 12.sp, color = c.secondary)
        }
    }
}
