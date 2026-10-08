package pl.trailtrack

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

fun shareFile(ctx: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, title))
}

fun fmt0(v: Double): String = String.format(Locale.getDefault(), "%.0f", v)
fun fmt1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

fun fmtPace(sec: Double): String {
    val t = sec.toInt().coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", t / 60, t % 60)
}

/** Prędkość w jednostce właściwej dla aktywności: km/h, min/km albo min/100 m. */
fun fmtSpeedFor(sport: Sport, ms: Double): String = when (sport.pace) {
    1 -> if (ms < 0.3) "–" else fmtPace(1000.0 / ms) + " /km"
    2 -> if (ms < 0.1) "–" else fmtPace(100.0 / ms) + " /100 m"
    else -> fmtKmh(ms)
}

fun speedLabel(sport: Sport): String = if (sport.pace == 0) "Prędkość" else "Tempo"
fun avgSpeedLabel(sport: Sport): String = if (sport.pace == 0) "Śr. prędkość" else "Śr. tempo"
