package pl.trailtrack

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.key
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearEasing
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Kolor przewodni aktywności (kafelek, ikona, poświata). */
fun sportAccent(s: Sport): Color = when (s) {
    Sport.CYCLING -> Color(0xFF0A84FF)
    Sport.RUNNING -> Color(0xFFFF9F0A)
    Sport.SWIMMING -> Color(0xFF32ADE6)
    Sport.STRENGTH -> Color(0xFFFF375F)
    Sport.TREADMILL -> Color(0xFF30D158)
    Sport.SKIING -> Color(0xFF5E5CE6)
    Sport.WALKING -> Color(0xFF00C7BE)
    Sport.KAYAKING -> Color(0xFF30B0C7)
    Sport.INLINE -> Color(0xFFAF52DE)
}

/**
 * Ekran wyboru aktywności: siatka kafelków z ikoną i nazwą na dole.
 * Animacje: kaskadowe wjeżdżanie kafelków (sprężyna), delikatne „oddychanie” ikon,
 * przechylenie kafelka w stronę dotyku i sprężyste dociśnięcie; po wyborze kafelek się powiększa,
 * a pozostałe wygasają, zanim otworzy się ekran nagrywania.
 * „Dostosuj” przełącza tryb edycji: kafelki lekko drżą, a dotknięcie pokazuje lub ukrywa aktywność.
 */
@Composable
fun ActivityPickerScreen(last: Sport, onPick: (Sport) -> Unit) {
    val c = ios()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Sport?>(null) }
    var editing by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(Prefs.hiddenSports) }
    val head = remember { Animatable(0f) }
    LaunchedEffect(Unit) { head.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }

    val all = Sport.values().toList()
    val shown = if (editing) all else all.filter { it.id !in hidden }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Column(
            Modifier.graphicsLayer {
                alpha = head.value
                translationY = (1f - head.value) * -16.dp.toPx()
            }
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { LargeTitle(if (editing) "Dostosuj aktywności" else "Wybierz aktywność") }
                if (chosen == null) {
                    Text(
                        if (editing) "Gotowe" else "Dostosuj",
                        color = c.blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { editing = !editing }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            Text(
                if (editing) "Dotknij kafelka, aby go pokazać lub ukryć." else "Co dziś robimy?",
                color = c.secondary, fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(16.dp))

        shown.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { col, sp ->
                    key(sp.id) {
                        ActivityTile(
                            sport = sp, index = row * 2 + col, isLast = sp == last, chosen = chosen,
                            editing = editing, hidden = sp.id in hidden,
                            modifier = Modifier.weight(1f),
                            onToggle = {
                                val next = if (sp.id in hidden) hidden - sp.id else hidden + sp.id
                                if (all.all { it.id in next }) {
                                    Toast.makeText(ctx, "Zostaw co najmniej jedną aktywność", Toast.LENGTH_SHORT).show()
                                } else {
                                    hidden = next
                                    Prefs.hiddenSports = next
                                }
                            }
                        ) {
                            if (chosen == null) {
                                chosen = sp
                                scope.launch {
                                    delay(280)
                                    onPick(sp)
                                }
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActivityTile(
    sport: Sport, index: Int, isLast: Boolean, chosen: Sport?,
    editing: Boolean, hidden: Boolean,
    modifier: Modifier, onToggle: () -> Unit, onClick: () -> Unit
) {
    val c = ios()
    val accent = sportAccent(sport)
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(26.dp)

    // wjazd kaskadowy
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 75L)
        appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 230f))
    }

    // dotyk: przechył w stronę palca + sprężyste dociśnięcie
    var press by remember { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val isChosen = chosen == sport
    val scale by animateFloatAsState(
        when {
            isChosen -> 1.07f
            press != null -> 0.94f
            else -> 1f
        },
        spring(dampingRatio = 0.55f, stiffness = 380f), label = "scale"
    )
    val dimTarget = when {
        chosen != null && !isChosen -> 0.30f
        editing && hidden -> 0.38f
        else -> 1f
    }
    val dim by animateFloatAsState(dimTarget, tween(240), label = "dim")
    val rotY by animateFloatAsState(
        press?.let { if (size.width > 0) ((it.x / size.width) - 0.5f) * 18f else 0f } ?: 0f,
        spring(stiffness = 300f), label = "ry"
    )
    val rotX by animateFloatAsState(
        press?.let { if (size.height > 0) -((it.y / size.height) - 0.5f) * 18f else 0f } ?: 0f,
        spring(stiffness = 300f), label = "rx"
    )

    // „oddychanie” ikony i poświaty
    val inf = rememberInfiniteTransition(label = "idle")
    val bob by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2300 + index * 260, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )
    // tryb edycji: delikatne drżenie kafelków
    val wob by inf.animateFloat(
        -1.3f, 1.3f, infiniteRepeatable(tween(120 + (index % 3) * 18, easing = LinearEasing), RepeatMode.Reverse), label = "wob"
    )
    val glow by inf.animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(1900 + index * 210, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )

    Box(
        modifier.aspectRatio(1f)
            .onSizeChanged { size = it }
            .graphicsLayer {
                val a = appear.value
                alpha = a.coerceIn(0f, 1f) * dim
                val s = (0.84f + 0.16f * a) * scale
                scaleX = s
                scaleY = s
                translationY = (1f - a) * 56.dp.toPx()
                rotationX = rotX
                rotationY = rotY
                rotationZ = if (editing) wob else 0f
                cameraDistance = 14f * density
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.26f), c.card, c.card)))
            .border(1.5.dp, accent.copy(alpha = if (isLast || isChosen || (editing && !hidden)) 0.85f else 0.22f), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { o ->
                        press = o
                        tryAwaitRelease()
                        press = null
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (editing) onToggle() else onClick()
                    }
                )
            }
    ) {
        // poświata + ikona
        Box(Modifier.align(Alignment.Center).offset(y = (-14).dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(96.dp)
                    .graphicsLayer { alpha = 0.55f * glow; val g = 0.9f + 0.1f * glow; scaleX = g; scaleY = g }
                    .background(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)), CircleShape
                    )
            )
            AppIconView(
                sport.icon, accent,
                Modifier.size(58.dp).graphicsLayer { translationY = (bob - 0.5f) * 7.dp.toPx() }
            )
        }

        // nazwa na dole
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                sport.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                textAlign = TextAlign.Center, maxLines = 1
            )
            Text(if (sport.gps) "GPS · mapa" else "bez GPS", fontSize = 11.sp, color = c.secondary)
        }

        if (editing) {
            Box(
                Modifier.align(Alignment.TopStart).padding(10.dp).size(26.dp).clip(CircleShape)
                    .background(if (!hidden) accent else c.fill)
                    .border(1.5.dp, accent.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) { if (!hidden) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }

        if (isLast && !editing) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)
            ) { Text("ostatnio", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = accent) }
        }
    }
}
