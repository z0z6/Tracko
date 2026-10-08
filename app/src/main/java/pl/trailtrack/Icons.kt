package pl.trailtrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Własne ikony liniowe (siatka 24×24) – bez zależności od material-icons. */
enum class AppIcon {
    Record, Rides, Routes, Analytics, Settings,
    Heart, Bolt, Cadence, Speed, Mountain, Clock, Flame,
    Lap, Pause, Play, Stop, Layers, Sensor, Check,
    ChevronLeft, ChevronRight, Palette, Download, Upload, Sound,
    Bike, Run, Swim, Dumbbell, Treadmill, Ski
}

val Sport.icon: AppIcon get() = when (this) {
    Sport.CYCLING -> AppIcon.Bike
    Sport.RUNNING -> AppIcon.Run
    Sport.SWIMMING -> AppIcon.Swim
    Sport.STRENGTH -> AppIcon.Dumbbell
    Sport.TREADMILL -> AppIcon.Treadmill
    Sport.SKIING -> AppIcon.Ski
}

fun SensorKind.appIcon(): AppIcon = when (this) {
    SensorKind.HR -> AppIcon.Heart
    SensorKind.POWER -> AppIcon.Bolt
    SensorKind.CSC -> AppIcon.Cadence
}

/** Dobiera ikonę do etykiety kafelka statystyki. */
fun iconForLabel(label: String): AppIcon? {
    val s = label.lowercase()
    return when {
        s.contains("tętno") -> AppIcon.Heart
        s.contains("kadencj") -> AppIcon.Cadence
        s == "np" || s == "if" || s == "vi" || s.contains("moc") || s.contains("tss") || s.startsWith("praca") -> AppIcon.Bolt
        s.contains("dystans") || s == "razem" -> AppIcon.Routes
        s.contains("czas") || s.contains("najszybszy") -> AppIcon.Clock
        s.contains("prędk") || s.contains("tempo") || s.startsWith("średnia") -> AppIcon.Speed
        s.contains("podjazd") || s.contains("zjazd") || s.contains("wysokość") ||
            s.contains("przewyższenie") || s.contains("nachylenie") -> AppIcon.Mountain
        s.contains("kalor") -> AppIcon.Flame
        s.contains("okr") -> AppIcon.Lap
        s == "przejazdy" || s == "aktywności" -> AppIcon.Rides
        s.contains("sprawność") || s.contains("zmęczenie") || s.contains("forma") -> AppIcon.Analytics
        else -> null
    }
}

private class PB(private val s: Float) {
    val path = Path()
    fun m(x: Float, y: Float): PB { path.moveTo(x * s, y * s); return this }
    fun l(x: Float, y: Float): PB { path.lineTo(x * s, y * s); return this }
    fun c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PB {
        path.cubicTo(x1 * s, y1 * s, x2 * s, y2 * s, x3 * s, y3 * s); return this
    }
    fun z(): PB { path.close(); return this }
}

@Composable
fun AppIconView(icon: AppIcon, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    val sp = spec()
    val cap = if (sp.roundIcons) StrokeCap.Round else StrokeCap.Butt
    val join = if (sp.roundIcons) StrokeJoin.Round else StrokeJoin.Miter
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = sp.iconStroke * s
        val stroke = Stroke(width = sw, cap = cap, join = join)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)

        when (icon) {
            AppIcon.Record -> {
                drawCircle(tint, radius = 9f * s, center = p(12f, 12f), style = stroke)
                drawCircle(tint, radius = 4.2f * s, center = p(12f, 12f))
            }
            AppIcon.Rides -> {
                for (y in listOf(7f, 12f, 17f)) {
                    drawCircle(tint, radius = 1.5f * s, center = p(5f, y))
                    drawLine(tint, p(9f, y), p(20f, y), strokeWidth = sw, cap = cap)
                }
            }
            AppIcon.Routes -> {
                drawCircle(tint, radius = 2.5f * s, center = p(6f, 18f), style = stroke)
                drawCircle(tint, radius = 2.5f * s, center = p(18f, 6f), style = stroke)
                drawPath(PB(s).m(8.5f, 18f).c(15f, 18f, 9f, 6f, 15.5f, 6f).path, tint, style = stroke)
            }
            AppIcon.Analytics -> {
                drawLine(tint, p(6f, 20f), p(6f, 13f), strokeWidth = 3f * s, cap = cap)
                drawLine(tint, p(12f, 20f), p(12f, 5f), strokeWidth = 3f * s, cap = cap)
                drawLine(tint, p(18f, 20f), p(18f, 10f), strokeWidth = 3f * s, cap = cap)
            }
            AppIcon.Settings -> {
                val rows = listOf(Pair(7f, 9f), Pair(12f, 15f), Pair(17f, 8f))
                for ((y, kx) in rows) {
                    drawLine(tint, p(4f, y), p(kx - 3f, y), strokeWidth = sw, cap = cap)
                    drawLine(tint, p(kx + 3f, y), p(20f, y), strokeWidth = sw, cap = cap)
                    drawCircle(tint, radius = 2.2f * s, center = p(kx, y), style = stroke)
                }
            }
            AppIcon.Heart -> {
                val h = PB(s).m(12f, 20.5f)
                    .c(4.5f, 14.5f, 3f, 11f, 3f, 8.2f)
                    .c(3f, 5.6f, 5f, 4f, 7.2f, 4f)
                    .c(9.3f, 4f, 11f, 5.2f, 12f, 7f)
                    .c(13f, 5.2f, 14.7f, 4f, 16.8f, 4f)
                    .c(19f, 4f, 21f, 5.6f, 21f, 8.2f)
                    .c(21f, 11f, 19.5f, 14.5f, 12f, 20.5f).z().path
                drawPath(h, tint.copy(alpha = 0.18f))
                drawPath(h, tint, style = stroke)
            }
            AppIcon.Bolt -> {
                val b = PB(s).m(13f, 3f).l(5.5f, 13f).l(11.5f, 13f).l(10.5f, 21f).l(18.5f, 10.5f).l(12.5f, 10.5f).z().path
                drawPath(b, tint.copy(alpha = 0.18f))
                drawPath(b, tint, style = stroke)
            }
            AppIcon.Cadence -> {
                val r = 8f
                drawArc(
                    tint, startAngle = 30f, sweepAngle = 280f, useCenter = false,
                    topLeft = p(12f - r, 12f - r), size = Size(2f * r * s, 2f * r * s), style = stroke
                )
                val a = Math.toRadians(310.0)
                val ex = 12f + r * cos(a).toFloat()
                val ey = 12f + r * sin(a).toFloat()
                val tx = -sin(a).toFloat()
                val ty = cos(a).toFloat()
                val nx = cos(a).toFloat()
                val ny = sin(a).toFloat()
                val head = PB(s)
                    .m(ex + tx * 3.4f, ey + ty * 3.4f)
                    .l(ex + nx * 2.8f, ey + ny * 2.8f)
                    .l(ex - nx * 2.8f, ey - ny * 2.8f).z().path
                drawPath(head, tint)
            }
            AppIcon.Speed -> {
                drawArc(
                    tint, startAngle = 150f, sweepAngle = 240f, useCenter = false,
                    topLeft = p(3f, 3f), size = Size(18f * s, 18f * s), style = stroke
                )
                drawLine(tint, p(12f, 13f), p(16.5f, 8.5f), strokeWidth = sw, cap = cap)
                drawCircle(tint, radius = 1.6f * s, center = p(12f, 13f))
            }
            AppIcon.Mountain -> {
                val m = PB(s).m(2.5f, 19f).l(9.5f, 6.5f).l(13f, 12.5f).l(15.5f, 9f).l(21.5f, 19f).z().path
                drawPath(m, tint.copy(alpha = 0.18f))
                drawPath(m, tint, style = stroke)
            }
            AppIcon.Clock -> {
                drawCircle(tint, radius = 9f * s, center = p(12f, 12f), style = stroke)
                drawLine(tint, p(12f, 7f), p(12f, 12f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(12f, 12f), p(15.5f, 14f), strokeWidth = sw, cap = cap)
            }
            AppIcon.Flame -> {
                val f = PB(s).m(12f, 3f)
                    .c(12f, 3f, 6f, 10f, 6f, 14.5f)
                    .c(6f, 18f, 8.7f, 21f, 12f, 21f)
                    .c(15.3f, 21f, 18f, 18f, 18f, 14.5f)
                    .c(18f, 10f, 12f, 3f, 12f, 3f).z().path
                drawPath(f, tint.copy(alpha = 0.18f))
                drawPath(f, tint, style = stroke)
            }
            AppIcon.Lap -> {
                drawLine(tint, p(6f, 4f), p(6f, 21f), strokeWidth = sw, cap = cap)
                val flag = PB(s).m(6f, 5f).l(18f, 5f).l(15f, 9f).l(18f, 13f).l(6f, 13f).path
                drawPath(flag, tint.copy(alpha = 0.18f))
                drawPath(flag, tint, style = stroke)
            }
            AppIcon.Pause -> {
                drawRoundRect(tint, topLeft = p(6.5f, 5f), size = Size(3.8f * s, 14f * s), cornerRadius = CornerRadius(1.6f * s))
                drawRoundRect(tint, topLeft = p(13.7f, 5f), size = Size(3.8f * s, 14f * s), cornerRadius = CornerRadius(1.6f * s))
            }
            AppIcon.Play -> {
                val t = PB(s).m(8f, 5f).l(19f, 12f).l(8f, 19f).z().path
                drawPath(t, tint)
                drawPath(t, tint, style = stroke)
            }
            AppIcon.Stop -> {
                drawRoundRect(tint, topLeft = p(6f, 6f), size = Size(12f * s, 12f * s), cornerRadius = CornerRadius(2.8f * s))
            }
            AppIcon.Layers -> {
                drawPath(PB(s).m(12f, 3f).l(21f, 8f).l(12f, 13f).l(3f, 8f).z().path, tint, style = stroke)
                drawPath(PB(s).m(3f, 12f).l(12f, 17f).l(21f, 12f).path, tint, style = stroke)
                drawPath(PB(s).m(3f, 16f).l(12f, 21f).l(21f, 16f).path, tint, style = stroke)
            }
            AppIcon.Sensor -> {
                drawCircle(tint, radius = 2f * s, center = p(12f, 12f))
                for (r in listOf(5.5f, 9.5f)) {
                    drawArc(tint, startAngle = -40f, sweepAngle = 80f, useCenter = false,
                        topLeft = p(12f - r, 12f - r), size = Size(2f * r * s, 2f * r * s), style = stroke)
                    drawArc(tint, startAngle = 140f, sweepAngle = 80f, useCenter = false,
                        topLeft = p(12f - r, 12f - r), size = Size(2f * r * s, 2f * r * s), style = stroke)
                }
            }
            AppIcon.Check -> {
                drawPath(PB(s).m(5f, 12.5f).l(10f, 17.5f).l(19f, 7f).path, tint, style = stroke)
            }
            AppIcon.ChevronLeft -> {
                drawPath(PB(s).m(15f, 5f).l(8f, 12f).l(15f, 19f).path, tint, style = stroke)
            }
            AppIcon.ChevronRight -> {
                drawPath(PB(s).m(9f, 5f).l(16f, 12f).l(9f, 19f).path, tint, style = stroke)
            }
            AppIcon.Palette -> {
                drawCircle(tint, radius = 9f * s, center = p(12f, 12f), style = stroke)
                drawCircle(tint, radius = 1.4f * s, center = p(8.5f, 10.5f))
                drawCircle(tint, radius = 1.4f * s, center = p(12f, 7.8f))
                drawCircle(tint, radius = 1.4f * s, center = p(15.5f, 10.5f))
            }
            AppIcon.Download -> {
                drawLine(tint, p(12f, 4f), p(12f, 15f), strokeWidth = sw, cap = cap)
                drawPath(PB(s).m(7f, 10f).l(12f, 15f).l(17f, 10f).path, tint, style = stroke)
                drawLine(tint, p(5f, 20f), p(19f, 20f), strokeWidth = sw, cap = cap)
            }
            AppIcon.Sound -> {
                val b = PB(s).m(4f, 9.5f).l(8f, 9.5f).l(13f, 5f).l(13f, 19f).l(8f, 14.5f).l(4f, 14.5f).z().path
                drawPath(b, tint.copy(alpha = 0.18f))
                drawPath(b, tint, style = stroke)
                for (r in listOf(4f, 8f)) {
                    drawArc(tint, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                        topLeft = p(14f - r, 12f - r), size = Size(2f * r * s, 2f * r * s), style = stroke)
                }
            }
            AppIcon.Bike -> {
                drawCircle(tint, radius = 3.8f * s, center = p(5.5f, 16f), style = stroke)
                drawCircle(tint, radius = 3.8f * s, center = p(18.5f, 16f), style = stroke)
                drawPath(PB(s).m(5.5f, 16f).l(10f, 8.5f).l(16f, 8.5f).l(18.5f, 16f).path, tint, style = stroke)
                drawLine(tint, p(10f, 8.5f), p(12.5f, 16f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(12.5f, 16f), p(5.5f, 16f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(16f, 8.5f), p(15f, 5.5f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(8.5f, 6.5f), p(11.5f, 6.5f), strokeWidth = sw, cap = cap)
            }
            AppIcon.Run -> {
                drawCircle(tint, radius = 2f * s, center = p(14.5f, 4.5f))
                drawLine(tint, p(13f, 8f), p(10.5f, 14f), strokeWidth = sw, cap = cap)
                drawPath(PB(s).m(12.5f, 9f).l(9f, 10.5f).l(7f, 8.5f).path, tint, style = stroke)
                drawPath(PB(s).m(12.8f, 8.5f).l(16.5f, 11f).l(18.5f, 9.5f).path, tint, style = stroke)
                drawPath(PB(s).m(10.5f, 14f).l(14f, 17f).l(12f, 21f).path, tint, style = stroke)
                drawPath(PB(s).m(10.5f, 14f).l(7.5f, 17f).l(4.5f, 16f).path, tint, style = stroke)
            }
            AppIcon.Swim -> {
                drawCircle(tint, radius = 1.9f * s, center = p(16f, 6f))
                drawPath(PB(s).m(5f, 12f).l(11f, 9f).l(14.5f, 9.5f).path, tint, style = stroke)
                for (y in listOf(15f, 19.5f)) {
                    drawPath(
                        PB(s).m(2.5f, y).c(4.5f, y - 2.2f, 6.5f, y - 2.2f, 8.5f, y)
                            .c(10.5f, y + 2.2f, 12.5f, y + 2.2f, 14.5f, y)
                            .c(16.5f, y - 2.2f, 18.5f, y - 2.2f, 21.5f, y).path,
                        tint, style = stroke
                    )
                }
            }
            AppIcon.Dumbbell -> {
                drawLine(tint, p(8f, 12f), p(16f, 12f), strokeWidth = sw, cap = cap)
                drawRoundRect(tint, topLeft = p(5f, 7.5f), size = Size(3f * s, 9f * s), cornerRadius = CornerRadius(1.2f * s))
                drawRoundRect(tint, topLeft = p(16f, 7.5f), size = Size(3f * s, 9f * s), cornerRadius = CornerRadius(1.2f * s))
                drawLine(tint, p(2.5f, 10f), p(2.5f, 14f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(21.5f, 10f), p(21.5f, 14f), strokeWidth = sw, cap = cap)
            }
            AppIcon.Treadmill -> {
                drawCircle(tint, radius = 1.8f * s, center = p(9f, 5f))
                drawLine(tint, p(9f, 8f), p(9f, 13f), strokeWidth = sw, cap = cap)
                drawPath(PB(s).m(9f, 13f).l(11.5f, 16f).path, tint, style = stroke)
                drawPath(PB(s).m(9f, 13f).l(6.5f, 16.5f).path, tint, style = stroke)
                drawPath(PB(s).m(9f, 9f).l(12f, 11f).path, tint, style = stroke)
                drawLine(tint, p(3f, 19f), p(18f, 19f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(18f, 19f), p(20f, 7f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(15f, 7f), p(21f, 7f), strokeWidth = sw, cap = cap)
            }
            AppIcon.Ski -> {
                drawCircle(tint, radius = 1.8f * s, center = p(12f, 4.5f))
                drawLine(tint, p(12f, 7.5f), p(12f, 14f), strokeWidth = sw, cap = cap)
                drawPath(PB(s).m(12f, 14f).l(9.5f, 19f).path, tint, style = stroke)
                drawPath(PB(s).m(12f, 14f).l(15f, 19f).path, tint, style = stroke)
                drawLine(tint, p(8f, 7f), p(5f, 19f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(16f, 7f), p(19f, 19f), strokeWidth = sw, cap = cap)
                drawLine(tint, p(2.5f, 20.5f), p(21.5f, 20.5f), strokeWidth = sw, cap = cap)
            }
            AppIcon.Upload -> {
                drawLine(tint, p(12f, 16f), p(12f, 5f), strokeWidth = sw, cap = cap)
                drawPath(PB(s).m(7f, 10f).l(12f, 5f).l(17f, 10f).path, tint, style = stroke)
                drawLine(tint, p(5f, 20f), p(19f, 20f), strokeWidth = sw, cap = cap)
            }
        }
    }
}

/** Kolorowa plakietka z ikoną (iOS) albo sama ikona w kolorze akcentu (KDE / Windows 11). */
@Composable
fun IconBadge(icon: AppIcon, color: Color) {
    if (spec().style == ThemeStyle.IOS) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(color),
            contentAlignment = Alignment.Center
        ) {
            AppIconView(icon, Color.White, Modifier.size(18.dp))
        }
    } else {
        AppIconView(icon, color, Modifier.size(24.dp))
    }
}
