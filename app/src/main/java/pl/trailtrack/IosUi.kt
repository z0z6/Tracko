package pl.trailtrack

import android.app.Activity
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat

// ---------- paleta w stylu iOS (jasna/ciemna) ----------

data class IosColors(
    val bg: Color, val card: Color, val label: Color, val secondary: Color,
    val separator: Color, val blue: Color, val green: Color, val red: Color,
    val orange: Color, val fill: Color,
    val border: Color = Color(0x293C3C43)
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

// ---------- style: iOS / KDE Breeze / Windows 11 (Fluent) ----------

enum class ThemeStyle(val label: String) {
    IOS("iOS"), KDE("KDE Breeze"), WIN11("Windows 11")
}

/** Parametry kształtu i typografii zależne od stylu. */
data class ThemeSpec(
    val style: ThemeStyle,
    val cardR: Dp,
    val btnR: Dp,
    val btnH: Dp,
    val segR: Dp,
    val cardBorder: Boolean,
    val iconStroke: Float,
    val roundIcons: Boolean,
    val uppercaseHeaders: Boolean,
    val titleSize: TextUnit,
    val titleWeight: FontWeight,
    val dividerInset: Dp,
    val centerNavTitle: Boolean
)

private val KdeLight = IosColors(
    bg = Color(0xFFEFF0F1), card = Color(0xFFFCFCFC), label = Color(0xFF31363B), secondary = Color(0xFF7F8C8D),
    separator = Color(0xFFD3D7DA), blue = Color(0xFF3DAEE9), green = Color(0xFF27AE60),
    red = Color(0xFFDA4453), orange = Color(0xFFF67400), fill = Color(0xFFE3E5E7), border = Color(0xFFBDC3C7)
)
private val KdeDark = IosColors(
    bg = Color(0xFF31363B), card = Color(0xFF232629), label = Color(0xFFEFF0F1), secondary = Color(0xFFA1A9B1),
    separator = Color(0xFF3F4448), blue = Color(0xFF3DAEE9), green = Color(0xFF27AE60),
    red = Color(0xFFDA4453), orange = Color(0xFFF67400), fill = Color(0xFF3B4045), border = Color(0xFF4D4D4D)
)
private val WinLight = IosColors(
    bg = Color(0xFFF3F3F3), card = Color(0xFFFFFFFF), label = Color(0xFF1A1A1A), secondary = Color(0xFF5E5E5E),
    separator = Color(0xFFE5E5E5), blue = Color(0xFF0067C0), green = Color(0xFF0F7B0F),
    red = Color(0xFFC42B1C), orange = Color(0xFFCA5010), fill = Color(0xFFEAEAEA), border = Color(0xFFE0E0E0)
)
private val WinDark = IosColors(
    bg = Color(0xFF202020), card = Color(0xFF2B2B2B), label = Color(0xFFFFFFFF), secondary = Color(0xFFC5C5C5),
    separator = Color(0xFF3A3A3A), blue = Color(0xFF60CDFF), green = Color(0xFF6CCB5F),
    red = Color(0xFFFF99A4), orange = Color(0xFFF7630C), fill = Color(0xFF3A3A3A), border = Color(0xFF3F3F3F)
)

fun paletteFor(style: ThemeStyle, dark: Boolean): IosColors = when (style) {
    ThemeStyle.IOS -> if (dark) DarkIos else LightIos
    ThemeStyle.KDE -> if (dark) KdeDark else KdeLight
    ThemeStyle.WIN11 -> if (dark) WinDark else WinLight
}

fun specFor(style: ThemeStyle): ThemeSpec = when (style) {
    ThemeStyle.IOS -> ThemeSpec(
        style, cardR = 14.dp, btnR = 25.dp, btnH = 50.dp, segR = 9.dp, cardBorder = false,
        iconStroke = 2f, roundIcons = true, uppercaseHeaders = true,
        titleSize = 34.sp, titleWeight = FontWeight.Bold, dividerInset = 16.dp, centerNavTitle = true
    )
    ThemeStyle.KDE -> ThemeSpec(
        style, cardR = 5.dp, btnR = 5.dp, btnH = 40.dp, segR = 4.dp, cardBorder = true,
        iconStroke = 1.6f, roundIcons = false, uppercaseHeaders = false,
        titleSize = 24.sp, titleWeight = FontWeight.SemiBold, dividerInset = 0.dp, centerNavTitle = false
    )
    ThemeStyle.WIN11 -> ThemeSpec(
        style, cardR = 8.dp, btnR = 4.dp, btnH = 42.dp, segR = 5.dp, cardBorder = true,
        iconStroke = 1.8f, roundIcons = true, uppercaseHeaders = false,
        titleSize = 28.sp, titleWeight = FontWeight.SemiBold, dividerInset = 0.dp, centerNavTitle = false
    )
}

val LocalIos = staticCompositionLocalOf { LightIos }
val LocalSpec = staticCompositionLocalOf { specFor(ThemeStyle.IOS) }

@Composable
fun spec(): ThemeSpec = LocalSpec.current

/** Czy aktualnie obowiązuje ciemny wariant (z uwzględnieniem trybu wybranego w ustawieniach). */
@Composable
fun isDarkNow(): Boolean = when (ThemeState.mode) {
    1 -> false
    2, 3 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun ios(): IosColors = LocalIos.current

/** Kolory akcentu do wyboru (jasny, ciemny wariant). */
data class AccentTheme(val name: String, val light: Color, val dark: Color)

val ACCENTS = listOf(
    AccentTheme("Błękit", Color(0xFF007AFF), Color(0xFF0A84FF)),
    AccentTheme("Turkus", Color(0xFF00A3A3), Color(0xFF3CCFCF)),
    AccentTheme("Fiolet", Color(0xFF6C4DF6), Color(0xFF9A86FF)),
    AccentTheme("Pomarańcz", Color(0xFFFF6B00), Color(0xFFFF8F40)),
    AccentTheme("Róż", Color(0xFFE91E63), Color(0xFFFF5C8D))
)

/** Stan motywu: zmiana od razu przebudowuje cały interfejs. */
object ThemeState {
    /** 0 = auto, 1 = jasny, 2 = ciemny, 3 = AMOLED */
    var mode by mutableIntStateOf(Prefs.themeMode)
    /** 0 = iOS, 1 = KDE Breeze, 2 = Windows 11 */
    var style by mutableIntStateOf(Prefs.themeStyle)
    var accent by mutableIntStateOf(Prefs.accent)
}

@Composable
fun IosTheme(content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val mode = ThemeState.mode
    val dark = when (mode) {
        1 -> false
        2, 3 -> true
        else -> system
    }
    val style = ThemeStyle.values()[ThemeState.style.coerceIn(0, ThemeStyle.values().size - 1)]
    val themeSpec = specFor(style)
    val accentIdx = ThemeState.accent.coerceIn(0, ACCENTS.lastIndex)
    val accent = ACCENTS[accentIdx]
    var c = paletteFor(style, dark)
    if (accentIdx != 0) c = c.copy(blue = if (dark) accent.dark else accent.light)
    if (mode == 3) c = c.copy(bg = Color.Black, card = Color(0xFF111113))

    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window
        if (w != null) {
            val ctl = WindowCompat.getInsetsController(w, view)
            ctl.isAppearanceLightStatusBars = !dark
            ctl.isAppearanceLightNavigationBars = !dark
        }
    }

    val scheme = if (dark) darkColorScheme(primary = c.blue, background = c.bg, surface = c.card, onSurface = c.label)
    else lightColorScheme(primary = c.blue, background = c.bg, surface = c.card, onSurface = c.label)
    CompositionLocalProvider(LocalIos provides c, LocalSpec provides themeSpec) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().background(c.bg)) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = c.bg, contentColor = c.label) { content() }
            }
        }
    }
}

@Composable
fun AccentPicker(selected: Int, onSelect: (Int) -> Unit) {
    val c = ios()
    val dark = isDarkNow()
    val styleBlue = paletteFor(ThemeStyle.values()[ThemeState.style.coerceIn(0, ThemeStyle.values().size - 1)], dark).blue
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ACCENTS.forEachIndexed { i, a ->
            val col = if (i == 0) styleBlue else if (dark) a.dark else a.light
            val sel = i == selected
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .border(2.5.dp, if (sel) col else Color.Transparent, CircleShape)
                    .clickable { onSelect(i) }.padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                    if (sel) AppIconView(AppIcon.Check, Color.White, Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---------- elementy ----------

@Composable
fun LargeTitle(text: String) {
    val sp = spec()
    Text(text, fontSize = sp.titleSize, fontWeight = sp.titleWeight, color = ios().label, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun IosNavBar(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = ios()
    val sp = spec()
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(c.bg).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Row(
                Modifier.clip(RoundedCornerShape(minOf(sp.btnR, 10.dp))).clickable { onBack() }.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconView(AppIcon.ChevronLeft, c.blue, Modifier.size(22.dp))
                Text("Wróć", color = c.blue, fontSize = 17.sp)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
            textAlign = if (sp.centerNavTitle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        if (trailing != null) trailing()
        if (sp.centerNavTitle && trailing == null && onBack != null) Spacer(Modifier.width(60.dp))
    }
}

@Composable
fun IosCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = ios()
    val sp = spec()
    val shape = RoundedCornerShape(sp.cardR)
    var m = modifier.clip(shape).background(c.card)
    if (sp.cardBorder) m = m.border(1.dp, c.border, shape)
    if (onClick != null) m = m.clickable { onClick() }
    Column(m, content = content)
}

@Composable
fun IosGroup(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = ios()
    Column {
        if (header != null) {
            val up = spec().uppercaseHeaders
            Text(
                if (up) header.uppercase() else header, fontSize = 13.sp, color = c.secondary,
                fontWeight = if (up) FontWeight.Normal else FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
        }
        IosCard(Modifier.fillMaxWidth(), content = content)
        if (footer != null) {
            Text(footer, fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
fun IosDivider() {
    Box(Modifier.fillMaxWidth().padding(start = spec().dividerInset).height(0.5.dp).background(ios().separator))
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
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            AppIconView(AppIcon.ChevronRight, c.secondary, Modifier.size(18.dp))
        }
    }
}

@Composable
fun IosSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ios()
    val sp = spec()
    val on = if (sp.style == ThemeStyle.IOS) c.green else c.blue
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (on.luminance() > 0.5f) Color.Black else Color.White,
                checkedTrackColor = on, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = if (sp.style == ThemeStyle.IOS) Color.White else c.secondary,
                uncheckedTrackColor = c.fill,
                uncheckedBorderColor = if (sp.style == ThemeStyle.IOS) Color.Transparent else c.secondary
            )
        )
    }
}

@Composable
fun IosButton(
    text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = true,
    icon: AppIcon? = null, onClick: () -> Unit
) {
    val sp = spec()
    val shape = RoundedCornerShape(sp.btnR)
    val bg: Brush = when {
        filled && sp.style == ThemeStyle.IOS -> Brush.verticalGradient(listOf(lerp(color, Color.White, 0.16f), color))
        filled -> SolidColor(color)
        else -> SolidColor(color.copy(alpha = 0.14f))
    }
    val fg = if (filled) (if (color.luminance() > 0.5f) Color.Black else Color.White) else color
    var m = modifier.height(sp.btnH).clip(shape).background(bg)
    if (!filled && sp.style != ThemeStyle.IOS) m = m.border(1.dp, color.copy(alpha = 0.6f), shape)
    Row(
        m.clickable { onClick() }.padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            AppIconView(icon, fg, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun IosSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = ios()
    val sp = spec()
    val outer = RoundedCornerShape(sp.segR)
    val inner = RoundedCornerShape((sp.segR - 2.dp).coerceAtLeast(2.dp))
    Row(modifier.fillMaxWidth().clip(outer).background(c.fill).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val bgc = if (!sel) Color.Transparent else if (sp.style == ThemeStyle.IOS) c.card else c.blue
            val fg = if (sel && sp.style != ThemeStyle.IOS) (if (c.blue.luminance() > 0.5f) Color.Black else Color.White) else c.label
            Box(
                Modifier.weight(1f).height(32.dp).clip(inner).background(bgc).clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = fg, maxLines = 1)
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null, icon: AppIcon? = iconForLabel(label)) {
    val c = ios()
    IosCard(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    AppIconView(icon, accent ?: c.blue, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(label, fontSize = 12.sp, color = c.secondary, maxLines = 1)
            }
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent ?: c.label, maxLines = 1)
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
    val sp = spec()
    val shape = RoundedCornerShape(maxOf(sp.cardR - 2.dp, 3.dp))
    Column(
        modifier.clip(shape)
            .background(if (selected) Color(t.color).copy(alpha = 0.18f) else c.card)
            .border(2.dp, if (selected) Color(t.color) else (if (sp.cardBorder) c.border else Color.Transparent), shape)
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
fun IosTabBar(items: List<Pair<AppIcon, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = ios()
    val sp = spec()
    Column(Modifier.background(c.card)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(if (sp.cardBorder) c.border else c.separator))
        Row(Modifier.fillMaxWidth().padding(top = if (sp.style == ThemeStyle.IOS) 6.dp else 0.dp, bottom = 6.dp)) {
            items.forEachIndexed { i, (icon, label) ->
                val sel = i == selected
                val tint = if (sel) c.blue else c.secondary
                Column(
                    Modifier.weight(1f).clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (sp.style) {
                        ThemeStyle.IOS -> {
                            Box(
                                Modifier.height(30.dp).width(52.dp).clip(RoundedCornerShape(15.dp))
                                    .background(if (sel) c.blue.copy(alpha = 0.14f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                AppIconView(icon, tint, Modifier.size(22.dp))
                            }
                        }
                        ThemeStyle.KDE -> {
                            Box(Modifier.fillMaxWidth(0.7f).height(3.dp).background(if (sel) c.blue else Color.Transparent))
                            Spacer(Modifier.height(6.dp))
                            AppIconView(icon, tint, Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                        }
                        ThemeStyle.WIN11 -> {
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.size(16.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(if (sel) c.blue else Color.Transparent))
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier.height(30.dp).width(52.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) c.fill else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                AppIconView(icon, tint, Modifier.size(22.dp))
                            }
                        }
                    }
                    Text(label, fontSize = 10.sp, color = tint, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
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
        val sp = spec()
        val dShape = RoundedCornerShape(sp.cardR + 2.dp)
        Column(Modifier.width(280.dp).clip(dShape).background(c.card).border(if (sp.cardBorder) 1.dp else 0.dp, c.border, dShape)) {
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
        val sp = spec()
        val dShape = RoundedCornerShape(sp.cardR + 2.dp)
        Column(Modifier.width(300.dp).clip(dShape).background(c.card).border(if (sp.cardBorder) 1.dp else 0.dp, c.border, dShape)) {
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

/** Miniatura stylu w ustawieniach (paleta i kształty w bieżącym trybie jasny/ciemny). */
@Composable
fun ThemePreviewCard(style: ThemeStyle, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = ios()
    val pal = paletteFor(style, isDarkNow())
    val sp = specFor(style)
    val outer = RoundedCornerShape(sp.cardR + 2.dp)
    val small = RoundedCornerShape(minOf(sp.cardR, 8.dp))
    Column(modifier.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(86.dp).clip(outer).background(pal.bg)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.blue else c.separator, outer)
                .padding(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(24.dp).clip(small).background(pal.card)
                        .border(if (sp.cardBorder) 1.dp else 0.dp, pal.border, small)
                )
                Box(Modifier.fillMaxWidth(0.7f).height(10.dp).clip(small).background(pal.card))
                Box(Modifier.fillMaxWidth(0.55f).height(16.dp).clip(RoundedCornerShape(minOf(sp.btnR, 8.dp))).background(pal.blue))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            style.label, fontSize = 12.sp, color = c.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center
        )
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
