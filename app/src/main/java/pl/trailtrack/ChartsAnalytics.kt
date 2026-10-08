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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Kilka linii na wspólnej osi (np. CTL/ATL/TSB). Wszystkie tablice tej samej długości. */
@Composable
fun LinesChart(lines: List<Pair<FloatArray, Color>>, modifier: Modifier = Modifier, leftLabel: String = "", rightLabel: String = "") {
    val c = ios()
    val labelArgb = c.secondary.toArgb()
    val grid = c.separator
    val zeroColor = c.label.copy(alpha = 0.35f)
    Canvas(modifier) {
        val m = lines.firstOrNull()?.first?.size ?: 0
        if (m < 2) return@Canvas
        val padL = 38.dp.toPx()
        val padR = 6.dp.toPx()
        val padT = 8.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        var yMin = Float.MAX_VALUE
        var yMax = -Float.MAX_VALUE
        for ((arr, _) in lines) for (v in arr) {
            if (v < yMin) yMin = v
            if (v > yMax) yMax = v
        }
        if (yMax - yMin < 1f) yMax = yMin + 1f
        val pad = (yMax - yMin) * 0.08f
        yMin -= pad
        yMax += pad
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
        if (yMin < 0f && yMax > 0f) {
            val zy = padT + (1f - (0f - yMin) / range) * h
            drawLine(zeroColor, Offset(padL, zy), Offset(padL + w, zy), strokeWidth = 1.5f)
        }
        nc.drawText(leftLabel, padL, size.height - 4.dp.toPx(), paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        nc.drawText(rightLabel, padL + w, size.height - 4.dp.toPx(), paint)

        for ((arr, col) in lines) {
            val path = Path()
            for (i in 0 until m) {
                val x = padL + i.toFloat() / (m - 1) * w
                val y = padT + (1f - (arr[i] - yMin) / range) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, col, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Słupki (np. TSS tygodniowo): wartości od najstarszej do najnowszej. */
@Composable
fun BarsChart(values: List<Double>, labels: List<String>, color: Color, modifier: Modifier = Modifier) {
    val c = ios()
    val labelArgb = c.secondary.toArgb()
    Canvas(modifier) {
        val n = values.size
        if (n == 0) return@Canvas
        val maxV = maxOf(values.maxOrNull() ?: 1.0, 1.0).toFloat()
        val paint = android.graphics.Paint().apply {
            this.color = labelArgb
            textSize = 10.sp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val nc = drawContext.canvas.nativeCanvas
        val padT = 14.dp.toPx()
        val padB = 16.dp.toPx()
        val h = size.height - padT - padB
        val slot = size.width / n
        val bw = slot * 0.6f
        for (i in 0 until n) {
            val bh = (values[i].toFloat() / maxV) * h
            val x = i * slot + (slot - bw) / 2f
            drawRect(color, topLeft = Offset(x, padT + h - bh), size = Size(bw, maxOf(bh, 1f)))
            nc.drawText(String.format(Locale.getDefault(), "%.0f", values[i]), i * slot + slot / 2f, padT + h - bh - 3.dp.toPx(), paint)
            nc.drawText(labels.getOrElse(i) { "" }, i * slot + slot / 2f, size.height - 3.dp.toPx(), paint)
        }
    }
}

@Composable
fun ZoneBarsCard(title: String, names: List<String>, secs: DoubleArray, colors: IntArray) {
    val c = ios()
    val total = secs.sum().coerceAtLeast(1.0)
    ChartCard(title) {
        Column {
            for (i in names.indices) {
                val frac = (secs[i] / total).toFloat().coerceIn(0f, 1f)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(colors[i]), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(names[i], fontSize = 14.sp, color = c.label, modifier = Modifier.weight(1f))
                        Text("${fmtTime(secs[i])} · ${fmt0(frac * 100.0)}%", fontSize = 12.sp, color = c.secondary)
                    }
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.fill)) {
                        if (frac > 0.002f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(colors[i])))
                    }
                }
            }
        }
    }
}

@Composable
fun PowerCurveGroup(curve: List<Pair<Int, Int>>, weightKg: Double) {
    if (curve.isEmpty()) return
    IosGroup(header = "Krzywa mocy (najlepsze wysiłki)") {
        curve.forEachIndexed { i, (sec, w) ->
            if (i > 0) IosDivider()
            val label = if (sec < 60) "$sec s" else if (sec < 3600) "${sec / 60} min" else "${sec / 3600} h"
            val wkg = if (weightKg > 0) String.format(Locale.getDefault(), "  (%.2f W/kg)", w / weightKg) else ""
            IosRow(label, "$w W$wkg")
        }
    }
}
