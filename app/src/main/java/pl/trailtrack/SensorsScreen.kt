package pl.trailtrack

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

@Composable
fun SensorsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val devices by SensorHub.devices.collectAsState()
    val found by SensorHub.found.collectAsState()
    val scanning by SensorHub.scanning.collectAsState()
    val live by SensorHub.live.collectAsState()
    var all by remember { mutableStateOf(false) }
    var wheel by remember { mutableStateOf(Prefs.wheelMm.toString()) }
    var forget by remember { mutableStateOf<SensorInfo?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) SensorHub.startScan(all)
        else Toast.makeText(ctx, "Bez zgody na Bluetooth nie znajdę czujników", Toast.LENGTH_LONG).show()
    }

    DisposableEffect(Unit) { onDispose { SensorHub.stopScan() } }

    val now = SystemClock.elapsedRealtime()
    val hr = if (now - live.hrTime < 5000) live.hr else 0
    val pw = if (now - live.powerTime < 5000) live.power else 0
    val cad = if (now - live.cadTime < 5000) live.cadence else 0
    val spd = if (now - live.speedTime < 5000) live.speedMs else 0.0

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Czujniki", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (devices.any { it.connected }) {
                TileGrid(
                    listOf(
                        "Tętno" to (if (hr > 0) "$hr bpm" else "–"),
                        "Moc" to (if (pw > 0) "$pw W" else "–"),
                        "Kadencja" to (if (cad > 0) "$cad rpm" else "–"),
                        "Prędkość (czujnik)" to (if (spd > 0) fmtKmh(spd) else "–")
                    )
                )
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
                }
            }

            IosButton(
                if (scanning) "Szukam… (dotknij, aby zatrzymać)" else "Wyszukaj czujniki",
                c.blue, Modifier.fillMaxWidth()
            ) {
                if (scanning) SensorHub.stopScan()
                else if (SensorHub.hasPermissions()) SensorHub.startScan(all)
                else permLauncher.launch(SensorHub.permissions())
            }
            IosGroup {
                IosSwitchRow("Pokaż wszystkie urządzenia BLE", all) { all = it }
            }

            if (found.isNotEmpty()) {
                IosGroup(header = "Znalezione (dotknij, aby połączyć)") {
                    found.forEachIndexed { i, f ->
                        if (i > 0) IosDivider()
                        val saved = devices.any { it.address == f.address }
                        IosRow(
                            title = f.name,
                            value = if (saved) "dodany" else "${f.rssi} dBm",
                            onClick = { if (!saved) SensorHub.add(f.address, f.name) },
                            leading = { IconBadge(f.kinds.firstOrNull()?.appIcon() ?: AppIcon.Sensor, c.blue) }
                        )
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

            IosGroup(header = "Garmin Edge 530 i inne urządzenia Garmin") {
                Text(
                    "• Pasy tętna Garmin (HRM-Pro, HRM-Dual, HRM 600) oraz mierniki mocy i czujniki kadencji/prędkości z Bluetooth " +
                        "łączą się bezpośrednio z aplikacją – wyszukaj je powyżej.\n" +
                        "• Edge 530 to komputer rowerowy: zwykle sam jest odbiornikiem czujników, więc nie widać go jako czujnika. " +
                        "Jeśli w jego menu masz opcję transmisji tętna przez Bluetooth, pojawi się tu jak zwykły pas.\n" +
                        "• Dane zapisane na Edge'u zaimportujesz jako plik .FIT (zakładka Przejazdy → Importuj plik FIT): " +
                        "wyeksportuj go z Garmin Connect albo skopiuj z urządzenia (USB, folder Garmin/Activity).\n" +
                        "• ANT+ nie jest obsługiwany (telefony zwykle nie mają radia ANT); pasy Garmin mają równolegle Bluetooth.\n" +
                        "• Czujnik zwykle łączy się z jednym urządzeniem naraz – rozłącz go od Edge'a/zegarka, jeśli nie widać go w skanie.",
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
