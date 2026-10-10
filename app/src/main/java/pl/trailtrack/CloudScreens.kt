package pl.trailtrack

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun CloudSwitch(title: String, initial: Boolean, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    IosSwitchRow(title, v) { v = it; set(it) }
}

/** Dialog e-mail + hasło (rejestracja lub logowanie). */
@Composable
private fun CredentialsDialog(title: String, confirm: String, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val c = ios()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.width(320.dp).clip(shape).background(c.card).border(1.dp, c.border, shape)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.label)
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true, label = { Text("E-mail") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true, label = { Text("Hasło (min. 6 znaków)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                Box(Modifier.weight(1f).fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                    Text("Anuluj", color = c.blue, fontSize = 17.sp)
                }
                Box(Modifier.width(0.5.dp).fillMaxSize().background(c.separator))
                Box(
                    Modifier.weight(1f).fillMaxSize().clickable { onConfirm(email.trim(), pass) },
                    contentAlignment = Alignment.Center
                ) { Text(confirm, color = c.blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Ustawienia „Rywalizacja online”: zgoda, konto, zakres udostępniania, prywatność, synchronizacja. */
@Composable
fun CloudScreen(repo: Repo, onBack: () -> Unit, onSegments: () -> Unit, onRoutes: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    val busy by Cloud.busy.collectAsState()
    val status by Cloud.status.collectAsState()

    var enabled by remember { mutableStateOf(Prefs.cloudEnabled) }
    var url by remember { mutableStateOf(Prefs.cloudUrl) }
    var key by remember { mutableStateOf(Prefs.cloudKey) }
    var signed by remember { mutableStateOf(Cloud.signedIn) }
    var email by remember { mutableStateOf(Prefs.cloudEmail) }
    var trimIdx by remember { mutableIntStateOf(listOf(0, 100, 200, 500).indexOf(Prefs.privacyTrimM).coerceAtLeast(0)) }
    var showConsent by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var authMode by remember { mutableIntStateOf(0) }   // 1 = rejestracja, 2 = logowanie
    var showDelete by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val trims = listOf(0, 100, 200, 500)

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Rywalizacja online", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IosGroup(
                header = "Udostępnianie",
                footer = "Domyślnie nic nie jest wysyłane. Po włączeniu Tracko wymienia dane z serwerem rywalizacji " +
                    "(rankingi odcinków, duchy innych zawodników, strona www). Konto jest anonimowe, dopóki nie podasz e-maila."
            ) {
                IosSwitchRow("Włącz rywalizację online", enabled) { on ->
                    if (on && !Prefs.cloudConsent) showConsent = true
                    else { enabled = on; Prefs.cloudEnabled = on }
                }
                if (!Cloud.configured) {
                    IosDivider()
                    Text(
                        "Brak adresu serwera. Ustaw go poniżej (Zaawansowane) albo wbuduj w aplikację przy kompilacji.",
                        color = c.orange, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (enabled) {
                IosGroup(
                    header = "Konto",
                    footer = "Bez e-maila konto istnieje tylko na tym telefonie – po wyczyszczeniu danych aplikacji dostęp do wysłanych " +
                        "danych przepadnie. E-mail pozwala zalogować się też na stronie www."
                ) {
                    IosRow(
                        "Status",
                        when {
                            !signed -> "Niezalogowano (konto powstanie przy pierwszej synchronizacji)"
                            email.isNotBlank() -> email
                            else -> "Konto anonimowe"
                        }
                    )
                    IosDivider()
                    if (!signed || email.isBlank()) {
                        IosRow("Utwórz konto z e-mailem", onClick = { authMode = 1 }, chevron = true)
                        IosDivider()
                    }
                    IosRow("Zaloguj się na istniejące konto", onClick = { authMode = 2 }, chevron = true)
                    if (signed) {
                        IosDivider()
                        IosRow("Wyloguj", onClick = {
                            Cloud.signOut()
                            signed = false
                            email = ""
                        })
                    }
                }

                IosGroup(
                    header = "Co udostępniam",
                    footer = "Odcinki i wyniki tworzą rankingi i duchy. Aktywności i trasy są opcjonalne. „Publicznie” oznacza, że widzą je inni " +
                        "użytkownicy; bez tego widzisz je tylko Ty po zalogowaniu na stronie."
                ) {
                    CloudSwitch("Odcinki i moje wyniki", Prefs.shareSegments) { Prefs.shareSegments = it }
                    IosDivider()
                    CloudSwitch("Przejechane aktywności (ślad)", Prefs.shareRides) { Prefs.shareRides = it }
                    IosDivider()
                    CloudSwitch("Moje trasy (planowanie)", Prefs.shareRoutes) { Prefs.shareRoutes = it }
                    IosDivider()
                    CloudSwitch("Aktywności i trasy publicznie", Prefs.sharePublic) { Prefs.sharePublic = it }
                }

                IosGroup(
                    header = "Prywatność śladu",
                    footer = "Przed wysłaniem wycinamy początek i koniec śladu na wybranej odległości, żeby nie zdradzać miejsca zamieszkania. " +
                        "Ślad jest też upraszczany (punkt co ok. 15 m); tętno i szczegóły sensorów nie są wysyłane."
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Wycinaj początek i koniec", fontSize = 17.sp, color = c.label)
                        Spacer(Modifier.height(8.dp))
                        IosSegmented(trims.map { if (it == 0) "Nie" else "$it m" }, trimIdx, { trimIdx = it; Prefs.privacyTrimM = trims[it] })
                    }
                    IosDivider()
                    CloudSwitch("Automatycznie tylko przez Wi-Fi", Prefs.cloudWifiOnly) { Prefs.cloudWifiOnly = it }
                }

                IosGroup(
                    header = "Synchronizacja",
                    footer = if (Prefs.cloudLastSync > 0)
                        "Ostatnio: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(Prefs.cloudLastSync)) else null
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        IosButton(if (busy) "Synchronizuję…" else "Synchronizuj teraz", c.blue, Modifier.fillMaxWidth()) {
                            if (!busy) scope.launch {
                                Cloud.syncAll(repo, manual = true)
                                signed = Cloud.signedIn
                                email = Prefs.cloudEmail
                            }
                        }
                        if (status.isNotBlank()) Text(status, color = c.secondary, fontSize = 13.sp)
                    }
                }

                IosGroup(header = "Przeglądaj online") {
                    IosRow("Odcinki i rankingi", onClick = onSegments, chevron = true, leading = { IconBadge(AppIcon.Ghost, c.blue) })
                    IosDivider()
                    IosRow("Trasy z internetu i ze strony www", onClick = onRoutes, chevron = true, leading = { IconBadge(AppIcon.Routes, c.green) })
                }

                IosGroup(header = "Dane na serwerze") {
                    IosRow("Usuń konto i wszystkie dane online", onClick = { showDelete = true })
                }
            }

            IosGroup(
                header = "Zaawansowane",
                footer = "Adres projektu Supabase (https://….supabase.co) i publiczny klucz „anon”. Zwykle są wbudowane w aplikację."
            ) {
                IosRow("Adres serwera", if (url.isBlank()) "nie ustawiono" else url.removePrefix("https://"), onClick = { showUrl = true })
                IosDivider()
                IosRow("Klucz publiczny", if (key.isBlank()) "nie ustawiono" else key.take(8) + "…", onClick = { showKey = true })
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (working) {
        Dialog(onDismissRequest = {}) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.card).padding(24.dp)) { CircularProgressIndicator(color = c.blue) }
        }
    }
    if (showConsent) {
        IosAlert(
            title = "Wysyłanie danych na serwer",
            message = "Po włączeniu Tracko wyśle: odcinki, które utworzysz, oraz Twoje wyniki na nich (czas, imię z Ustawień, data startu). " +
                "Opcjonalnie – przejechane aktywności (uproszczony ślad bez początku i końca) i trasy. " +
                "Nic więcej nie jest wysyłane. Dane możesz w każdej chwili usunąć (Usuń konto).",
            confirmText = "Zgadzam się",
            onConfirm = {
                showConsent = false
                Prefs.cloudConsent = true
                Prefs.cloudEnabled = true
                enabled = true
            },
            dismissText = "Anuluj", onDismiss = { showConsent = false }
        )
    }
    if (showUrl) {
        IosInputDialog("Adres serwera", url, onConfirm = { v -> showUrl = false; Prefs.cloudUrl = v; url = Prefs.cloudUrl }, onDismiss = { showUrl = false })
    }
    if (showKey) {
        IosInputDialog("Klucz publiczny (anon)", key, onConfirm = { v -> showKey = false; Prefs.cloudKey = v; key = Prefs.cloudKey }, onDismiss = { showKey = false })
    }
    if (authMode != 0) {
        val registering = authMode == 1
        CredentialsDialog(
            title = if (registering) "Utwórz konto" else "Zaloguj się",
            confirm = if (registering) "Utwórz" else "Zaloguj",
            onConfirm = { mail, pw ->
                if (mail.isBlank() || pw.length < 6) {
                    toast("Podaj e-mail i hasło (min. 6 znaków)")
                } else {
                    authMode = 0
                    working = true
                    scope.launch {
                        val r = if (registering) Cloud.registerEmail(mail, pw) else Cloud.signInWithEmail(mail, pw)
                        working = false
                        signed = Cloud.signedIn
                        email = Prefs.cloudEmail
                        toast(if (r.ok) (if (registering) "Gotowe. Jeśli serwer wymaga potwierdzenia, sprawdź skrzynkę e-mail." else "Zalogowano") else (r.error ?: "Błąd"))
                    }
                }
            },
            onDismiss = { authMode = 0 }
        )
    }
    if (showDelete) {
        IosAlert(
            title = "Usunąć dane online?",
            message = "Zostanie usunięte konto na serwerze razem z odcinkami, wynikami, aktywnościami i trasami. Dane na telefonie zostają.",
            confirmText = "Usuń", confirmColor = c.red,
            onConfirm = {
                showDelete = false
                working = true
                scope.launch {
                    val r = Cloud.deleteAccount()
                    working = false
                    signed = Cloud.signedIn
                    email = Prefs.cloudEmail
                    toast(if (r.ok) "Konto i dane online usunięte" else (r.error ?: "Nie udało się usunąć"))
                }
            },
            dismissText = "Anuluj", onDismiss = { showDelete = false }
        )
    }
}

/** Przeglądanie odcinków z serwera: pobranie dodaje odcinek i ranking (duchy innych) do telefonu. */
@Composable
fun OnlineSegmentsScreen(repo: Repo, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<RemoteSegment>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            val (l, e) = Cloud.browseSegments(query)
            items = l
            error = e
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Odcinki online", onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Szukaj po nazwie") },
                        modifier = Modifier.weight(1f)
                    )
                    IosButton("Szukaj", c.blue, Modifier.width(96.dp)) { load() }
                }
            }
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.blue) } }
            } else if (error != null) {
                item { Text("Nie udało się pobrać listy: $error", color = c.orange, fontSize = 14.sp) }
            } else if (items.isEmpty()) {
                item { Text("Brak odcinków. Utwórz pierwszy i wyślij go do rankingu.", color = c.secondary, modifier = Modifier.padding(8.dp)) }
            } else {
                item {
                    IosGroup(header = "Odcinki (${items.size})") {
                        items.forEachIndexed { i, s ->
                            if (i > 0) IosDivider()
                            val sport = Sport.fromId(s.sport)
                            IosRow(
                                s.name,
                                (if (downloading == s.uid) "pobieranie…" else "${fmtKm(s.lengthM)} · ${s.efforts} wyn." +
                                    (s.bestSec?.let { " · " + fmtTime(it) } ?: "")),
                                onClick = {
                                    if (downloading == null) {
                                        downloading = s.uid
                                        scope.launch {
                                            val ok = Cloud.downloadSegment(repo, s.uid)
                                            downloading = null
                                            if (ok) onOpen(s.uid) else Toast.makeText(ctx, "Nie udało się pobrać odcinka", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                chevron = true,
                                leading = { IconBadge(sport.icon, c.blue) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Trasy z serwera (zaplanowane na stronie www i publiczne) – pobranie dodaje trasę do zakładki „Trasy”. */
@Composable
fun OnlineRoutesScreen(repo: Repo, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ios()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RemoteRoute>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(Prefs.syncedSet("routes")) }

    LaunchedEffect(Unit) {
        val (l, e) = Cloud.browseRoutes()
        items = l
        error = e
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Trasy online", onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.blue) } }
            } else if (error != null) {
                item { Text("Nie udało się pobrać listy: $error", color = c.orange, fontSize = 14.sp) }
            } else if (items.isEmpty()) {
                item { Text("Brak tras. Zaplanuj trasę na stronie www albo wyślij swoje z aplikacji.", color = c.secondary, modifier = Modifier.padding(8.dp)) }
            } else {
                item {
                    IosGroup(header = "Trasy (${items.size})") {
                        items.forEachIndexed { i, r ->
                            if (i > 0) IosDivider()
                            val imported = "remote:${r.id}" in done
                            IosRow(
                                r.name + if (r.mine) " (moja)" else "",
                                when {
                                    imported -> "✓ pobrana"
                                    busyId == r.id -> "pobieranie…"
                                    else -> "${fmtKm(r.distanceM)} · ↑${fmtM(r.ascentM)}" + (if (r.source == "web") " · www" else "")
                                },
                                onClick = {
                                    if (!imported && busyId == null) {
                                        busyId = r.id
                                        scope.launch {
                                            val ok = Cloud.importRoute(repo, r)
                                            busyId = null
                                            done = Prefs.syncedSet("routes")
                                            Toast.makeText(ctx, if (ok) "Trasa dodana do zakładki Trasy" else "Nie udało się dodać trasy", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                leading = { IconBadge(AppIcon.Routes, c.green) }
                            )
                        }
                    }
                }
            }
        }
    }
}
