package pl.trailtrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ---------- paleta w stylu iOS (jasna/ciemna) ----------

data class IosColors(
    val bg: Color, val card: Color, val label: Color, val secondary: Color,
    val separator: Color, val blue: Color, val green: Color, val red: Color,
    val orange: Color, val fill: Color
)

val LightIos = IosColors(
    bg = Color(0xFFF2F2F7), card = Color.White, label = Color.Black, secondary = Color(0x993C3C43),
    separator = Color(0x293C3C43), blue = Color(0xFF007AFF), green = Color(0xFF34C759),
    red = Color(0xFFFF3B30), orange = Color(0xFFFF9500), fill = Color(0x1F787880)
)
val DarkIos = IosColors(
    bg = Color.Black, card = Color(0xFF1C1C1E), label = Color.White, secondary = Color(0x99EBEBF5),
    separator = Color(0x54545458), blue = Color(0xFF0A84FF), green = Color(0xFF30D158),
    red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A), fill = Color(0x3D787880)
)

val LocalIos = staticCompositionLocalOf { LightIos }

@Composable
fun ios(): IosColors = LocalIos.current

@Composable
fun IosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkIos else LightIos
    val scheme = if (dark) darkColorScheme(primary = c.blue, background = c.bg, surface = c.card, onSurface = c.label)
    else lightColorScheme(primary = c.blue, background = c.bg, surface = c.card, onSurface = c.label)
    CompositionLocalProvider(LocalIos provides c) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.label) { content() }
        }
    }
}

// ---------- elementy ----------

@Composable
fun LargeTitle(text: String) {
    Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = ios().label, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun IosNavBar(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = ios()
    Box(Modifier.fillMaxWidth().height(48.dp).background(c.bg).padding(horizontal = 8.dp)) {
        if (onBack != null) {
            Text(
                "‹ Wróć", color = c.blue, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(8.dp)
            )
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

@Composable
fun IosCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = ios()
    var m = modifier.clip(RoundedCornerShape(14.dp)).background(c.card)
    if (onClick != null) m = m.clickable { onClick() }
    Column(m, content = content)
}

@Composable
fun IosGroup(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = ios()
    Column {
        if (header != null) {
            Text(header.uppercase(), fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        IosCard(Modifier.fillMaxWidth(), content = content)
        if (footer != null) {
            Text(footer, fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
fun IosDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios().separator))
}

@Composable
fun IosRow(title: String, value: String? = null, onClick: (() -> Unit)? = null, chevron: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    val c = ios()
    var m = Modifier.fillMaxWidth()
    if (onClick != null) m = m.clickable { onClick() }
    Row(m.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 17.sp, color = c.secondary)
        if (chevron) Text("  ›", fontSize = 20.sp, color = c.secondary)
    }
}

@Composable
fun IosSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ios()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = c.green, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = c.fill, uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun IosButton(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(50.dp).clip(RoundedCornerShape(25.dp))
            .background(if (filled) color else color.copy(alpha = 0.15f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) Color.White else color, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun IosSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = ios()
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(c.fill).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (sel) c.card else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = c.label)
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val c = ios()
    IosCard(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, fontSize = 12.sp, color = c.secondary)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent ?: c.label)
        }
    }
}

@Composable
fun TileGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (l, v) -> StatTile(l, v, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun TerrainTile(t: Terrain, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = ios()
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(t.color).copy(alpha = 0.18f) else c.card)
            .border(2.dp, if (selected) Color(t.color) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(16.dp).background(Color(t.color), CircleShape))
        Spacer(Modifier.height(6.dp))
        Text(t.label, fontSize = 11.sp, color = c.label, textAlign = TextAlign.Center, maxLines = 2, minLines = 2)
    }
}

@Composable
fun IosTabBar(items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = ios()
    Column(Modifier.background(c.card)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
            items.forEachIndexed { i, (icon, label) ->
                val sel = i == selected
                Column(
                    Modifier.weight(1f).clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(icon, fontSize = 22.sp, modifier = Modifier.padding(bottom = 1.dp))
                    Text(label, fontSize = 10.sp, color = if (sel) c.blue else c.secondary, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Okienko w stylu iOS (alert). dismissText == null -> jeden przycisk. */
@Composable
fun IosAlert(
    title: String, message: String? = null,
    confirmText: String, confirmColor: Color? = null, onConfirm: () -> Unit,
    dismissText: String? = null, onDismiss: () -> Unit
) {
    val c = ios()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(c.card)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.label, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, fontSize = 13.sp, color = c.label, textAlign = TextAlign.Center)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                if (dismissText != null) {
                    Box(Modifier.weight(1f).fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text(dismissText, color = c.blue, fontSize = 17.sp)
                    }
                    Box(Modifier.width(0.5.dp).fillMaxSize().background(c.separator))
                }
                Box(Modifier.weight(1f).fillMaxSize().clickable { onConfirm() }, contentAlignment = Alignment.Center) {
                    Text(confirmText, color = confirmColor ?: c.blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun IosInputDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = ios()
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(c.card)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.label)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                Box(Modifier.weight(1f).fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                    Text("Anuluj", color = c.blue, fontSize = 17.sp)
                }
                Box(Modifier.width(0.5.dp).fillMaxSize().background(c.separator))
                Box(Modifier.weight(1f).fillMaxSize().clickable { onConfirm(text.trim()) }, contentAlignment = Alignment.Center) {
                    Text("Zapisz", color = c.blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------- formatowanie ----------

fun fmtKm(m: Double): String = String.format(java.util.Locale.getDefault(), "%.2f km", m / 1000.0)
fun fmtKmh(ms: Double): String = String.format(java.util.Locale.getDefault(), "%.1f km/h", ms * 3.6)
fun fmtM(m: Double): String = String.format(java.util.Locale.getDefault(), "%.0f m", m)
fun fmtKcal(k: Double): String = String.format(java.util.Locale.getDefault(), "%.0f kcal", k)
fun fmtPct(p: Double): String = String.format(java.util.Locale.getDefault(), "%.1f%%", p)
fun fmtTime(sec: Double): String {
    val s = sec.toLong()
    return String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
fun fmtDate(t: Long): String =
    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
