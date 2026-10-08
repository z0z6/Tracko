package pl.trailtrack

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun hasFineLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun isGpsOn(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(true)
}

/**
 * Bramka GPS: dopóki aplikacja nie ma zgody na lokalizację i GPS nie jest włączony,
 * zasłania cały interfejs pełnoekranowym komunikatem. Stan sprawdzany jest po powrocie
 * do aplikacji (np. z ustawień) oraz na bieżąco (broadcast zmiany dostawców lokalizacji).
 *
 * Android bez Google Play Services nie pozwala włączyć GPS programowo – można tylko
 * otworzyć systemowe ustawienia lokalizacji, co robi przycisk na ekranie.
 */
@Composable
fun GpsGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val live by Live.state.collectAsState()

    // urządzenia bez GPS (np. część tabletów) nie mogą spełnić wymogu – nie blokujemy ich
    val hasGpsHw = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) }

    var permOk by remember { mutableStateOf(hasFineLocation(ctx)) }
    var gpsOn by remember { mutableStateOf(isGpsOn(ctx)) }
    var askedOnce by remember { mutableStateOf(false) }

    fun refresh() {
        permOk = hasFineLocation(ctx)
        gpsOn = isGpsOn(ctx)
    }

    // sprawdzenie po każdym powrocie do aplikacji
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // reakcja na włączenie/wyłączenie GPS z paska szybkich ustawień
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        val f = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        refresh()
    }

    // GPS wymuszamy tylko dla aktywności z mapą (rower, bieganie, narty); bieżnia/basen/siłownia go nie potrzebują
    val blocked = hasGpsHw && live.sport.gps && (!permOk || !gpsOn)

    Box(Modifier.fillMaxSize()) {
        content()
        if (blocked) {
            Box(
                Modifier.fillMaxSize().background(c.bg)
                    .pointerInput(Unit) { detectTapGestures { } } // pochłania dotknięcia – nic pod spodem nie jest klikalne
                    .safeDrawingPadding().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    AppIconView(AppIcon.Record, c.red, Modifier.size(56.dp))
                    if (!permOk) {
                        // czy użytkownik zaznaczył „nie pytaj ponownie”? wtedy trzeba wejść w ustawienia aplikacji
                        val permanentlyDenied = askedOnce && ctx is Activity &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                        Text("Potrzebna zgoda na lokalizację", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
                        Text(
                            "TrailTrack musi znać Twoją pozycję, żeby nagrywać trasę. Bez tego przejazd nie zostanie zapisany.",
                            color = c.secondary, fontSize = 15.sp, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        IosButton(
                            if (permanentlyDenied) "Otwórz ustawienia aplikacji" else "Zezwól na lokalizację",
                            c.blue, Modifier.fillMaxWidth()
                        ) {
                            if (permanentlyDenied) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                                    )
                                }
                            } else {
                                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                permLauncher.launch(perms.toTypedArray())
                            }
                        }
                    } else {
                        Text("GPS jest wyłączony", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
                        Text(
                            if (live.recording)
                                "Trwa nagrywanie, ale bez GPS trasa NIE jest zapisywana. Włącz lokalizację, aby kontynuować."
                            else
                                "Włącz GPS (lokalizację) w telefonie, żeby trasa była nagrywana od pierwszego metra.",
                            color = c.secondary, fontSize = 15.sp, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        IosButton("Włącz GPS", c.blue, Modifier.fillMaxWidth()) {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        }
                        if (live.recording) {
                            // wyjście awaryjne, żeby nie dało się „uwięzić” trwającego nagrania
                            IosButton("Zakończ przejazd", c.red, Modifier.fillMaxWidth(), filled = false) {
                                ctx.startService(Intent(ctx, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
                            }
                        }
                    }
                    if (!live.recording) {
                        // bieżnia, basen i siłownia nie potrzebują GPS – dalej wybierzesz aktywność w oknie z mapą
                        IosButton("Trenuję bez GPS (bieżnia, basen, siłownia)", c.secondary, Modifier.fillMaxWidth(), filled = false) {
                            val sp = Sport.fromId(Prefs.lastNoGpsSport)
                            Live.setSport(sp)
                            Prefs.sport = sp.id
                        }
                    }
                }
            }
        }
    }
}
