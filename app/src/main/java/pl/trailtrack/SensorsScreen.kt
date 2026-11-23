package pl.trailtrack

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SensorsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val devices by SensorHub.devices.collectAsState()
    val found by SensorHub.found.collectAsState()
    val scanning by SensorHub.scanning.collectAsState()
    val live by SensorHub.live.collectAsState()
    // domyślnie skanujemy wszystko – zegarki, opaski, pasy, czujniki dowolnej marki
    var sportOnly by remember { mutableStateOf(false) }
    var showUnnamed by remember { mutableStateOf(false) }
    var wheel by remember { mutableStateOf(Prefs.wheelMm.toString()) }
    var forget by remember { mutableStateOf<SensorInfo?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) SensorHub.startScan(sportOnly)
        else Toast.makeText(ctx, "Bez zgody na Bluetooth nie znajdę czujników", Toast.LENGTH_LONG).show()
    }

    DisposableEffect(Unit) { onDispose { SensorHub.stopScan() } }

    // Health Connect: tętno z zegarków, które nie nadają go przez Bluetooth (CMF Watch 3 Pro, Galaxy Watch, Fitbit…)
    val scope = rememberCoroutineScope()
    val hcAvail = remember { HealthHr.availability(ctx) }
    var hcGranted by remember { mutableStateOf(false) }
    var hcOn by remember { mutableStateOf(Prefs.healthHr) }
    var notifOn by remember { mutableStateOf(Prefs.sensorNotif) }
    LaunchedEffect(Unit) { hcGranted = HealthHr.granted(ctx) }
    val hcLauncher = rememberLauncherForActivityResult(HealthHr.permissionContract()) { got ->
        hcGranted = got.containsAll(HealthHr.PERMISSIONS)
        if (hcGranted) {
            Prefs.healthHr = true
            hcOn = true
        } else {
            Toast.makeText(ctx, "Bez zgody Health Connect nie pobiorę tętna z zegarka", Toast.LENGTH_LONG).show()
        }
    }

    val now = SystemClock.elapsedRealtime()
    val hr = if (now - live.hrTime < 5000) live.hr else 0
    val pw = if (now - live.powerTime < 5000) live.power else 0
    val cad = if (now - live.cadTime < 5000) live.cadence else 0
    val spd = if (now - live.speedTime < 5000) live.speedMs else 0.0
    val runSpd = if (now - live.runTime < 5000) live.runSpeedMs else 0.0
    val runCad = if (now - live.runTime < 5000) live.runCadence else 0
    val mach = if (now - live.machineTime < 5000) live.machineSpeedMs else 0.0
    val spo2 = if (now - live.spo2Time < 30000) live.spo2 else 0
    val temp = if (now - live.tempTime < 60000) live.tempC else 0.0

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Czujniki", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (devices.any { it.connected }) {
                val tiles = arrayListOf(
                    "Tętno" to (if (hr > 0) "$hr bpm" else "–"),
                    "Moc" to (if (pw > 0) "$pw W" else "–"),
                    "Kadencja" to (if (cad > 0) "$cad rpm" else if (runCad > 0) "$runCad kr/min" else "–"),
                    "Prędkość (czujnik)" to when {
                        spd > 0 -> fmtKmh(spd)
                        runSpd > 0 -> fmtKmh(runSpd)
                        mach > 0 -> fmtKmh(mach)
                        else -> "–"
                    }
                )
                if (spo2 > 0) tiles.add("SpO₂" to "$spo2 %")
                if (temp > 0) tiles.add("Temperatura" to String.format(java.util.Locale.getDefault(), "%.1f °C", temp))
                TileGrid(tiles)
            }

            IosGroup(
                header = "Moje czujniki",
                footer = "Zapisane czujniki łączą się automatycznie po uruchomieniu aplikacji i przy starcie nagrywania."
            ) {
                if (devices.isEmpty()) {
                    Text("Brak czujników. Wyszukaj poniżej.", color = c.secondary, modifier = Modifier.padding(16.dp))
                }
                devices.forEachIndexed { i, d ->
                    if (i > 0) IosDivider()
                    val status = when {
                        d.connected -> "połączono" + (if (d.battery >= 0) " • 🔋${d.battery}%" else "")
                        d.connecting -> "łączenie…"
                        else -> "rozłączono"
                    }
                    IosRow(
                        title = d.name.ifBlank { d.address },
                        value = status,
                        onClick = { forget = d },
                        leading = {
                            IconBadge(d.kinds.firstOrNull()?.appIcon() ?: AppIcon.Sensor, if (d.connected) c.green else c.secondary)
                        }
                    )
                    val sub = listOfNotNull(
                        d.maker.takeIf { it.isNotBlank() },
                        d.kinds.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label }
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) {
                        Text(sub, fontSize = 12.sp, color = c.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    }
                    d.hint?.let {
                        Text(it, fontSize = 12.sp, color = c.orange, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
                    }
                }
            }

            IosButton(
                if (scanning) "Szukam… (dotknij, aby zatrzymać)" else "Wyszukaj urządzenia",
                c.blue, Modifier.fillMaxWidth()
            ) {
                if (scanning) SensorHub.stopScan()
                else if (SensorHub.hasPermissions()) SensorHub.startScan(sportOnly)
                else permLauncher.launch(SensorHub.permissions())
            }
            IosGroup(
                footer = "Wyszukiwane są wszystkie urządzenia Bluetooth LE w pobliżu – smartwatche, smartbandy, pasy, czujniki. " +
                    "Nazwa pochodzi z reklamy urządzenia; gdy jej nie podaje, widać producenta lub rodzaj i końcówkę adresu. " +
                    "Urządzenia sparowane już z telefonem (np. zegarek z aplikacją producenta) są na liście nawet wtedy, gdy się nie reklamują."
            ) {
                IosSwitchRow("Tylko czujniki sportowe", sportOnly) {
                    sportOnly = it
                    if (scanning) { SensorHub.stopScan(); SensorHub.startScan(it) }
                }
                IosDivider()
                IosSwitchRow("Pokaż urządzenia bez nazwy", showUnnamed) { showUnnamed = it }
            }

            val visible = found.filter { showUnnamed || it.name.isNotBlank() || it.kinds.isNotEmpty() || it.bonded }
            val hiddenCount = found.size - visible.size
            if (visible.isNotEmpty() || hiddenCount > 0) {
                IosGroup(
                    header = "Znalezione (dotknij, aby połączyć)",
                    footer = if (hiddenCount > 0) "Ukryto urządzenia bez nazwy: $hiddenCount." else null
                ) {
                    if (visible.isEmpty()) {
                        Text("Brak urządzeń z nazwą.", color = c.secondary, modifier = Modifier.padding(16.dp))
                    }
                    visible.forEachIndexed { i, f ->
                        if (i > 0) IosDivider()
                        val saved = devices.any { it.address == f.address }
                        IosRow(
                            title = f.displayName,
                            value = when {
                                saved -> "dodany"
                                f.rssi == Int.MIN_VALUE -> "sparowane"
                                else -> "${f.rssi} dBm"
                            },
                            onClick = { if (!saved) SensorHub.add(f.address, f.name.ifBlank { f.displayName }) },
                            leading = { IconBadge(f.kinds.firstOrNull()?.appIcon() ?: AppIcon.Sensor, c.blue) }
                        )
                        val sub = listOfNotNull(
                            f.type,
                            f.maker?.takeIf { f.name.isNotBlank() || f.type != null },
                            f.kinds.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label },
                            if (f.bonded) "sparowane z telefonem" else null
                        ).distinct().joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(sub, fontSize = 12.sp, color = c.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                        }
                    }
                }
            }

            IosGroup(
                header = "Tętno w powiadomieniu",
                footer = "Po połączeniu czujnika tętno (i bateria) widać na pasku powiadomień i ekranie blokady, także bez nagrywania. " +
                    "Podczas nagrywania tętno jest w powiadomieniu trasy."
            ) {
                IosSwitchRow("Pokazuj po połączeniu", notifOn) {
                    notifOn = it
                    Prefs.sensorNotif = it
                    SensorNotifier.refresh(ctx)
                }
            }

            IosGroup(
                header = "Zegarki bez transmisji tętna (Health Connect)",
                footer = "Część zegarków i opasek nie wysyła tętna na żywo przez Bluetooth. Ich aplikacje " +
                    "zapisują je jednak " +
                    "do Health Connect – Tracko dołącza je do aktywności po treningu, gdy zegarek się zsynchronizuje " +
                    "(automatycznie przez 3 dni albo przyciskiem w podsumowaniu aktywności). Punkty z tętnem z pasa BLE " +
                    "nie są nadpisywane."
            ) {
                when {
                    hcAvail == 2 -> Text("Health Connect nie jest dostępny na tym urządzeniu (wymaga Androida 9+).",
                        color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    hcAvail == 1 -> IosRow("Zainstaluj / zaktualizuj Health Connect", chevron = true, onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                    !hcGranted -> IosRow("Połącz z Health Connect", chevron = true, onClick = { hcLauncher.launch(HealthHr.PERMISSIONS) })
                    else -> {
                        IosSwitchRow("Dołączaj tętno z zegarka", hcOn) {
                            hcOn = it
                            Prefs.healthHr = it
                            if (it) scope.launch { runCatching { Repo(ctx.applicationContext).autoFillRecentHr() } }
                        }
                        IosDivider()
                        IosRow("Uprawnienia Health Connect", value = "przyznane", onClick = { hcLauncher.launch(HealthHr.PERMISSIONS) })
                    }
                }
            }

            IosGroup(
                header = "Czujnik prędkości (CSC)",
                footer = "Obwód koła w mm – potrzebny do przeliczenia obrotów koła na prędkość (np. 700×28c ≈ 2136 mm)."
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = wheel,
                        onValueChange = { v ->
                            wheel = v.filter { it.isDigit() }.take(4)
                            wheel.toIntOrNull()?.let { if (it in 1000..3000) Prefs.wheelMm = it }
                        },
                        label = { Text("Obwód koła [mm]") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            IosGroup(header = "Obsługiwany sprzęt") {
                Text(
                    "Tracko łączy się z każdym urządzeniem Bluetooth LE, niezależnie od marki – smartwatchem, smartbandem, " +
                        "pasem piersiowym, opaską na ramię, czujnikiem czy maszyną treningową – przez standardowe profile:\n" +
                        "• tętno (Heart Rate),\n" +
                        "• moc, prędkość i kadencja na rowerze (Cycling Power, CSC),\n" +
                        "• prędkość i kadencja biegu (RSC),\n" +
                        "• trenażery, bieżnie, wioślarze, orbitreki (FTMS) – na bieżni prędkość i nachylenie trafiają same do aktywności,\n" +
                        "• SpO₂ (Pulse Oximeter) i temperatura (Health Thermometer),\n" +
                        "• bateria, producent i model (Battery, Device Information).\n\n" +
                        "Zegarki i opaski zwykle trzeba przełączyć w tryb udostępniania tętna. Urządzenie, które nie ma żadnego " +
                        "z tych profili, może przekazać tętno po treningu przez Health Connect.\n" +
                        "Treningi zapisane na innym urządzeniu zaimportujesz jako plik .FIT (Aktywności → Importuj plik FIT).\n" +
                        "ANT+ nie jest obsługiwane (telefony zwykle nie mają radia ANT). Czujnik zwykle łączy się z jednym " +
                        "odbiornikiem naraz – rozłącz go od innego urządzenia, jeśli nie widać go na liście.",
                    fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val f = forget
    if (f != null) {
        IosAlert(
            title = "Usunąć czujnik?", message = f.name,
            confirmText = "Usuń", confirmColor = c.red,
            onConfirm = { SensorHub.forget(f.address); forget = null },
            dismissText = "Anuluj", onDismiss = { forget = null }
        )
    }
}
