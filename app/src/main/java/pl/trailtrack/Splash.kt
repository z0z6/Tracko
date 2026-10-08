package pl.trailtrack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animacja startowa (ok. 2 s): ślad rysuje się sam gradientową linią, za nim z lekkim opóźnieniem
 * podąża przerywany „duch”, potem wjeżdża nazwa. Całość znika płynnym zanikiem z lekkim powiększeniem.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val c = ios()
    val draw = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        val line = launch { draw.animateTo(1f, tween(1150, easing = CubicBezierEasing(0.2f, 0.8f, 0.25f, 1f))) }
        delay(700)
        title.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
        line.join()
        delay(260)
        exit.animateTo(1f, tween(400, easing = FastOutLinearInEasing))
        onFinished()
    }

    val violet = Color(0xFF5E5CE6)
    val green = Color(0xFF30D158)

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = 1f - exit.value
                val s = 1f + 0.06f * exit.value
                scaleX = s
                scaleY = s
            }
            .background(c.bg)
            .pointerInput(Unit) { detectTapGestures { } },   // w trakcie animacji nic pod spodem nie reaguje
        contentAlignment = Alignment.Center
    ) {
        // miękka poświata za logo
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.blue.copy(alpha = 0.16f * draw.value), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f), radius = r
                ),
                radius = r, center = Offset(size.width / 2f, size.height / 2f)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 264.dp, height = 132.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(0.04f * w, 0.78f * h)
                    cubicTo(0.22f * w, 1.02f * h, 0.34f * w, 0.30f * h, 0.50f * w, 0.50f * h)
                    cubicTo(0.66f * w, 0.70f * h, 0.76f * w, 0.02f * h, 0.96f * w, 0.22f * h)
                }
                val pm = PathMeasure()
                pm.setPath(path, false)
                val len = pm.length
                val sw = 7.dp.toPx()

                // duch: lekko opóźniony, przerywany
                val ghostP = (draw.value - 0.17f).coerceIn(0f, 1f)
                if (ghostP > 0.01f) {
                    val gSeg = Path()
                    pm.getSegment(0f, len * ghostP, gSeg, true)
                    drawPath(
                        gSeg, color = c.secondary.copy(alpha = 0.40f),
                        style = Stroke(
                            width = sw * 0.7f, cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 1.6f, sw * 2.2f), 0f)
                        )
                    )
                }

                // właściwy ślad
                val seg = Path()
                pm.getSegment(0f, (len * draw.value).coerceAtLeast(0.01f), seg, true)
                drawPath(
                    seg,
                    brush = Brush.linearGradient(listOf(c.blue, violet, green), start = Offset(0f, 0f), end = Offset(w, 0f)),
                    style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // punkt startu i świecąca głowica
                drawCircle(c.blue, radius = sw * 0.95f, center = Offset(0.04f * w, 0.78f * h))
                if (draw.value in 0.01f..0.995f) {
                    val head = pm.getPosition(len * draw.value)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.95f), violet.copy(alpha = 0f)),
                            center = head, radius = sw * 3.4f
                        ),
                        radius = sw * 3.4f, center = head
                    )
                    drawCircle(Color.White, radius = sw * 0.7f, center = head)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Tracko",
                fontSize = 34.sp, fontWeight = FontWeight.Light, color = c.label,
                letterSpacing = (2f + 9f * (1f - title.value)).sp,
                modifier = Modifier.graphicsLayer {
                    alpha = title.value
                    translationY = (1f - title.value) * 14.dp.toPx()
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "TRASY  ·  WYSIŁEK  ·  DUCHY",
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.secondary,
                letterSpacing = 3.sp,
                modifier = Modifier.graphicsLayer { alpha = title.value * 0.9f }
            )
        }
    }
}
