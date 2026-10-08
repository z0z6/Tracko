package pl.trailtrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Wykres liniowy z wypełnieniem; kolor każdego odcinka z tablicy [colors] (np. wg nawierzchni). */
@Composable
fun LineAreaChart(
    xs: FloatArray, ys: FloatArray, colors: IntArray, yUnit: String,
    modifier: Modifier = Modifier, fill: Boolean = true, minZero: Boolean = false, xUnit: String = "km"
) {
    val c = ios()
    val labelArgb = c.secondary.toArgb()
    val grid = c.separator
    Canvas(modifier) {
        if (xs.size < 2 || ys.size != xs.size) return@Canvas
        val padL = 38.dp.toPx()
        val padR = 6.dp.toPx()
        val padT = 8.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        val xMax = maxOf(xs.last(), 0.01f)
        var yMin = ys.min()
        var yMax = ys.max()
        if (minZero) yMin = 0f
        if (yMax - yMin < 1f) {
            yMax = yMin + 1f
        } else if (!minZero) {
            val pad = (yMax - yMin) * 0.08f
            yMin -= pad
            yMax += pad
        }
        val range = yMax - yMin
        val paint = android.graphics.Paint().apply {
            color = labelArgb
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        val nc = drawContext.canvas.nativeCanvas
        for (k in 0..2) {
            val yv = yMin + range * k / 2f
            val py = padT + (1f - k / 2f) * h
            drawLine(grid, Offset(padL, py), Offset(padL + w, py), strokeWidth = 1f)
            nc.drawText(String.format(Locale.getDefault(), "%.0f", yv), 2.dp.toPx(), py + 3.dp.toPx(), paint)
        }
        nc.drawText("0", padL, size.height - 4.dp.toPx(), paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        nc.drawText(String.format(Locale.getDefault(), "%.1f $xUnit", xMax), padL + w, size.height - 4.dp.toPx(), paint)
        nc.drawText(yUnit, padL + w, padT + 10.dp.toPx(), paint)

        val base = padT + h
        for (i in 1 until xs.size) {
            val x0 = padL + xs[i - 1] / xMax * w
            val x1 = padL + xs[i] / xMax * w
            val y0 = padT + (1f - (ys[i - 1] - yMin) / range) * h
            val y1 = padT + (1f - (ys[i] - yMin) / range) * h
            val col = Color(colors[i])
            if (fill) {
                val path = Path().apply {
                    moveTo(x0, y0); lineTo(x1, y1); lineTo(x1, base); lineTo(x0, base); close()
                }
                drawPath(path, col.copy(alpha = 0.22f))
            }
            drawLine(col, Offset(x0, y0), Offset(x1, y1), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun ChartCard(title: String, content: @Composable () -> Unit) {
    val c = ios()
    IosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun ElevationChartCard(series: Series, overrideColor: Int? = null) {
    val cols = if (overrideColor != null) IntArray(series.colors.size) { overrideColor } else series.colors
    ChartCard("Profil wysokości") {
        LineAreaChart(series.distKm, series.ele, cols, "m n.p.m.", Modifier.fillMaxWidth().height(160.dp), xUnit = series.xUnit)
    }
}

@Composable
fun SpeedChartCard(series: Series) {
    ChartCard("Prędkość (kolor = nawierzchnia)") {
        LineAreaChart(series.distKm, series.speedKmh, series.colors, "km/h", Modifier.fillMaxWidth().height(160.dp), fill = false, minZero = true, xUnit = series.xUnit)
    }
}

/** Średnia i maksymalna prędkość na każdej nawierzchni (poziome słupki). */
@Composable
fun TerrainSpeedCard(stats: RideStats) {
    val c = ios()
    val rows = stats.terrainDist.entries.sortedByDescending { it.value }.filter { it.value > 20.0 }
    val maxV = (rows.maxOfOrNull { stats.terrainMaxSpeed[it.key] ?: 0.0 } ?: 1.0).coerceAtLeast(1.0)
    ChartCard("Prędkość wg nawierzchni") {
        Column {
            rows.forEach { (t, d) ->
                val sec = stats.terrainTime[t] ?: 0.0
                val avg = if (sec > 1) d / sec else 0.0
                val mx = stats.terrainMaxSpeed[t] ?: 0.0
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(t.color), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(t.label, fontSize = 14.sp, color = c.label, modifier = Modifier.weight(1f))
                        Text("${fmtKm(d)} · śr. ${fmtKmh(avg)} · maks. ${fmtKmh(mx)}", fontSize = 12.sp, color = c.secondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    SpeedBar(avg / maxV, Color(t.color), 1f)
                    Spacer(Modifier.height(2.dp))
                    SpeedBar(mx / maxV, Color(t.color).copy(alpha = 0.35f), 1f)
                }
            }
            if (rows.isEmpty()) Text("Brak danych", color = c.secondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SpeedBar(frac: Double, color: Color, @Suppress("UNUSED_PARAMETER") unused: Float) {
    val f = frac.coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(ios().fill)) {
        if (f > 0.001f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
fun TerrainBar(m: Map<Terrain, Double>) {
    Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(ios().fill)) {
        m.forEach { (t, d) ->
            if (d > 1.0) Box(Modifier.weight(d.toFloat()).fillMaxHeight().background(Color(t.color)))
        }
    }
}

/** Podziały km: słupki prędkości. */
@Composable
fun SplitsGroup(title: String, splits: List<Split>, sport: Sport = Sport.CYCLING) {
    val c = ios()
    if (splits.isEmpty()) return
    val maxV = splits.maxOf { it.avgSpeedMs }.coerceAtLeast(0.1)
    IosGroup(header = title) {
        splits.forEachIndexed { i, s ->
            if (i > 0) IosDivider()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.label, fontSize = 14.sp, color = c.label, modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).padding(end = 8.dp)) { SpeedBar(s.avgSpeedMs / maxV, c.blue, 1f) }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtSpeedFor(sport, s.avgSpeedMs), fontSize = 13.sp, color = c.label, fontWeight = FontWeight.SemiBold)
                    Text("${fmtTime(s.activeSec)} · ↑${fmtM(s.ascentM)}", fontSize = 11.sp, color = c.secondary)
                }
            }
        }
    }
}
