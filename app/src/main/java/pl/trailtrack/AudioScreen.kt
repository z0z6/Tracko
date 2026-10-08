package pl.trailtrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun PrefSwitch(title: String, initial: Boolean, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    IosSwitchRow(title, v) { v = it; set(it) }
}

private val GOAL_UNITS = listOf("", "km", "min", "m")
private val GOAL_NAMES = listOf("", "Cel: dystans (km)", "Cel: czas w ruchu (min)", "Cel: przewyższenie (m)")

/** Ustawienia dźwięków, komunikatów głosowych i celu treningowego. */
@Composable
fun AudioSettingsScreen(onBack: () -> Unit) {
    val c = ios()
    val voiceState by Sound.voiceState.collectAsState()
    LaunchedEffect(Unit) { Sound.prepare() }

    var soundOn by remember { mutableStateOf(Prefs.soundOn) }
    var voiceOn by remember { mutableStateOf(Prefs.voiceOn) }
    var volume by remember { mutableIntStateOf(Prefs.cueVolume) }
    var goalType by remember { mutableIntStateOf(Prefs.goalType) }
    var goalValue by remember { mutableStateOf(Prefs.goalValue) }
    var showGoalInput by remember { mutableStateOf(false) }
    var margin by remember { mutableIntStateOf(Prefs.paceMarginPct) }
    var zone by remember { mutableIntStateOf(Prefs.effortZone) }
    val margins = listOf(5, 10, 15)

    Column(Modifier.fillMaxSize()) {
        IosNavBar("Dźwięki i głos", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IosGroup(
                header = "Ogólne",
                footer = when (voiceState) {
                    Sound.VOICE_NO_POLISH ->
                        "Brak polskiego głosu w telefonie. Zainstaluj go w Ustawienia → Zarządzanie ogólne → Język → " +
                            "Zamiana tekstu na mowę (nazwa zależy od producenta). Do tego czasu komunikaty zabrzmią w innym języku."
                    Sound.VOICE_FAILED ->
                        "Nie znaleziono syntezatora mowy. Zainstaluj silnik „zamiany tekstu na mowę” (np. Google TTS albo " +
                            "Speech Services by Google) – bez niego działają tylko sygnały dźwiękowe."
                    else ->
                        "Komunikaty są odtwarzane także przy wygaszonym ekranie; muzyka z innych aplikacji jest na chwilę ściszana. " +
                            "Głośność zależy od głośności multimediów."
                }
            ) {
                IosSwitchRow("Sygnały dźwiękowe", soundOn) { soundOn = it; Prefs.soundOn = it }
                IosDivider()
                IosSwitchRow("Komunikaty głosowe", voiceOn) { voiceOn = it; Prefs.voiceOn = it }
                IosDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("Głośność komunikatów", fontSize = 17.sp, color = c.label)
                    Spacer(Modifier.height(8.dp))
                    IosSegmented(listOf("Cicho", "Średnio", "Głośno"), volume, { volume = it; Prefs.cueVolume = it })
                }
                IosDivider()
                Column(Modifier.padding(16.dp)) {
                    IosButton("Przetestuj głos", c.blue, Modifier.fillMaxWidth(), filled = false, icon = AppIcon.Sound) {
                        Sound.cue(Beep.GOAL, "Cel osiągnięty. To jest test komunikatów głosowych.", force = true)
                    }
                }
            }

            IosGroup(
                header = "Cel treningowy",
                footer = "Cel dotyczy jednego przejazdu. Po jego osiągnięciu usłyszysz sygnał i komunikat. " +
                    "Przewyższenie liczone na bieżąco może się o kilka metrów różnić od podsumowania po przejeździe."
            ) {
                Column(Modifier.padding(16.dp)) {
                    IosSegmented(
                        listOf("Brak", "Dystans", "Czas", "Przewyż."), goalType,
                        { goalType = it; Prefs.goalType = it }
                    )
                }
                if (goalType != 0) {
                    IosDivider()
                    IosRow(
                        GOAL_NAMES[goalType],
                        if (goalValue > 0f) "${fmtGoal(goalValue)} ${GOAL_UNITS[goalType]}" else "Ustaw",
                        onClick = { showGoalInput = true }, chevron = true
                    )
                    IosDivider()
                    PrefSwitch("Komunikat po osiągnięciu celu", Prefs.evGoal) { Prefs.evGoal = it }
                    IosDivider()
                    PrefSwitch("Komunikat w połowie celu", Prefs.evHalf) { Prefs.evHalf = it }
                }
            }

            IosGroup(
                header = "Rekordy i porównania",
                footer = "„Najlepszy wynik”: najszybszy kilometr i najdłuższy przejazd w Twojej historii (od wersji 0.5 " +
                    "przejazdy są uzupełniane automatycznie). „Zazwyczaj” = średnie tempo z Twoich 10 ostatnich przejazdów; " +
                    "porównanie robione jest po każdym kilometrze (od drugiego), więc podjazd może dać „słabsze tempo”. " +
                    "Zwiększony wysiłek wymaga czujnika tętna lub mocy i progów (LTHR/FTP) z zakładki Analiza."
            ) {
                PrefSwitch("Najlepszy wynik", Prefs.evRecord) { Prefs.evRecord = it }
                IosDivider()
                PrefSwitch("Tempo lepsze / słabsze niż zazwyczaj", Prefs.evPace) { Prefs.evPace = it }
                IosDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("Czułość porównania tempa", fontSize = 17.sp, color = c.label)
                    Spacer(Modifier.height(8.dp))
                    IosSegmented(
                        margins.map { "±$it%" }, margins.indexOf(margin).coerceAtLeast(0),
                        { margin = margins[it]; Prefs.paceMarginPct = margins[it] }
                    )
                }
                IosDivider()
                PrefSwitch("Zwiększony wysiłek", Prefs.evEffort) { Prefs.evEffort = it }
                IosDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("Wysiłek od strefy", fontSize = 17.sp, color = c.label)
                    Spacer(Modifier.height(8.dp))
                    IosSegmented(
                        listOf("Z3 Tempo", "Z4 Próg", "Z5 Maks."), zone - 3,
                        { zone = it + 3; Prefs.effortZone = it + 3 }
                    )
                }
            }

            IosGroup(header = "Pozostałe komunikaty") {
                PrefSwitch("Start, pauza, okrążenia, koniec", Prefs.evStart) { Prefs.evStart = it }
                IosDivider()
                PrefSwitch("Podsumowanie każdego kilometra", Prefs.evKm) { Prefs.evKm = it }
                IosDivider()
                PrefSwitch("Utrata i powrót GPS", Prefs.evGps) { Prefs.evGps = it }
                IosDivider()
                PrefSwitch("Zjechanie z trasy do podążania", Prefs.evOffRoute) { Prefs.evOffRoute = it }
                IosDivider()
                PrefSwitch("Odcinki i ściganie z duchem", Prefs.evGhost) { Prefs.evGhost = it }
            }

            IosGroup(header = "Przesłuchaj sygnały") {
                Beep.values().forEachIndexed { i, b ->
                    if (i > 0) IosDivider()
                    IosRow(b.label, onClick = { Sound.cue(b, force = true) }, leading = { IconBadge(AppIcon.Sound, c.blue) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showGoalInput) {
        val unit = GOAL_UNITS[goalType]
        IosInputDialog(
            title = "Cel ($unit)",
            initial = if (goalValue > 0f) fmtGoal(goalValue) else "",
            onConfirm = { txt ->
                val v = txt.replace(',', '.').toFloatOrNull()
                if (v != null && v > 0f) {
                    goalValue = v
                    Prefs.goalValue = v
                }
                showGoalInput = false
            },
            onDismiss = { showGoalInput = false }
        )
    }
}

private fun fmtGoal(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.getDefault(), "%.1f", v)
