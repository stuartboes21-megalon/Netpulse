package com.netpulse.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netpulse.app.net.AccessPoint
import com.netpulse.app.net.Band
import com.netpulse.app.net.NetUtils
import com.netpulse.app.ui.theme.Semantic
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------- Speed gauge

private val GaugeStops = floatArrayOf(0f, 5f, 10f, 25f, 50f, 100f, 250f, 500f, 1000f)

/** Maps Mbps onto the gauge with equal visual spacing between the labelled stops. */
fun gaugeFraction(mbps: Float): Float {
    if (mbps <= 0f) return 0f
    val last = GaugeStops.size - 1
    for (i in 0 until last) {
        val a = GaugeStops[i]
        val b = GaugeStops[i + 1]
        if (mbps <= b) return (i + (mbps - a) / (b - a)) / last
    }
    return 1f
}

@Composable
fun SpeedGauge(
    value: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    center: @Composable ColumnScope.() -> Unit,
) {
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 45f),
        label = "gauge",
    )
    val frac = gaugeFraction(animated)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val needleColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val measurer = rememberTextMeasurer()
    val labels = remember(labelStyle) {
        GaugeStops.map { v -> measurer.measure(if (v >= 1000f) "1G" else v.toInt().toString(), labelStyle) }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "p",
    )

    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val dim = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.065f
            val r = size.minDimension / 2f - stroke / 2f - 6.dp.toPx()
            val c = center
            val topLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(r * 2, r * 2)

            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (frac > 0.002f) {
                val brush = Brush.sweepGradient(
                    0f to accent.copy(alpha = 0.35f),
                    0.75f to accent,
                    0.95f to accent,
                    1f to accent.copy(alpha = 0.35f),
                    center = c,
                )
                rotate(135f, c) {
                    drawArc(brush, 0f, 270f * frac, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }

            // Ticks
            val tickOuter = r - stroke / 2f - 8.dp.toPx()
            for (i in 0..40) {
                val ang = Math.toRadians(135.0 + 270.0 * i / 40)
                val major = i % 5 == 0
                val len = if (major) 9.dp.toPx() else 4.dp.toPx()
                val cx = cos(ang).toFloat()
                val sy = sin(ang).toFloat()
                val lit = i / 40f <= frac
                drawLine(
                    color = if (lit) accent else labelColor.copy(alpha = if (major) 0.55f else 0.25f),
                    start = Offset(c.x + cx * tickOuter, c.y + sy * tickOuter),
                    end = Offset(c.x + cx * (tickOuter - len), c.y + sy * (tickOuter - len)),
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Labels
            val labelR = tickOuter - 22.dp.toPx()
            labels.forEachIndexed { i, layout ->
                val ang = Math.toRadians(135.0 + 270.0 * i / (labels.size - 1))
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        c.x + cos(ang).toFloat() * labelR - layout.size.width / 2f,
                        c.y + sin(ang).toFloat() * labelR - layout.size.height / 2f,
                    ),
                )
            }

            // Needle
            val ang = Math.toRadians(135.0 + 270.0 * frac)
            val dir = Offset(cos(ang).toFloat(), sin(ang).toFloat())
            val needleLen = r * 0.46f
            drawLine(
                color = needleColor,
                start = c - dir * 10.dp.toPx(),
                end = c + dir * needleLen,
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(needleColor, 9.dp.toPx(), c)
            drawCircle(accent, 4.dp.toPx(), c)

            // Glowing head on the arc
            if (active && frac > 0.002f) {
                val head = c + dir * r
                drawCircle(accent.copy(alpha = 0.22f * pulse), stroke * 1.4f, head)
                drawCircle(Color.White, stroke * 0.3f, head)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dim * 0.06f),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = center,
        )
    }
}

// ---------------------------------------------------------------- Live throughput chart

@Composable
fun LiveChart(
    series: List<Pair<List<Float>, Color>>,
    modifier: Modifier = Modifier,
    maxPoints: Int = 70,
) {
    Canvas(modifier) {
        val maxV = (series.flatMap { it.first }.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.15f
        val stepX = size.width / (maxPoints - 1)
        series.forEach { (samples, color) ->
            if (samples.size < 2) return@forEach
            val pts = samples.takeLast(maxPoints).mapIndexed { i, v ->
                Offset(i * stepX, size.height - (v / maxV) * size.height)
            }
            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val p0 = pts[i - 1]
                    val p1 = pts[i]
                    val mx = (p0.x + p1.x) / 2f
                    cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(pts.last().x, size.height)
                lineTo(pts.first().x, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color.copy(alpha = 0.25f), 8.dp.toPx(), pts.last())
            drawCircle(color, 4.dp.toPx(), pts.last())
        }
    }
}

// ---------------------------------------------------------------- Radar

data class Blip(val angle: Float, val radius: Float, val color: Color)

@Composable
fun Radar(scanning: Boolean, blips: List<Blip>, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "sweep",
    )
    val sweepAlpha by animateFloatAsState(if (scanning) 1f else 0f, tween(700), label = "sweepAlpha")
    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f
        for (i in 1..4) drawCircle(ring.copy(alpha = 0.7f), r * i / 4f, style = Stroke(1.dp.toPx()))
        drawLine(ring.copy(alpha = 0.5f), Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.dp.toPx())
        drawLine(ring.copy(alpha = 0.5f), Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.dp.toPx())

        if (sweepAlpha > 0.01f) {
            rotate(sweep) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to primary.copy(alpha = 0.45f * sweepAlpha),
                        center = center,
                    ),
                    radius = r,
                )
                drawLine(
                    color = primary.copy(alpha = sweepAlpha),
                    start = center,
                    end = Offset(center.x + r, center.y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        blips.forEach { b ->
            val rad = Math.toRadians(b.angle.toDouble())
            val p = Offset(
                center.x + cos(rad).toFloat() * r * b.radius,
                center.y + sin(rad).toFloat() * r * b.radius,
            )
            val behind = ((sweep - b.angle) % 360f + 360f) % 360f
            val flash = if (scanning) (1f - behind / 140f).coerceIn(0f, 1f) else 0f
            drawCircle(b.color.copy(alpha = 0.18f + 0.35f * flash), 9.dp.toPx() + 7.dp.toPx() * flash, p)
            drawCircle(b.color, 4.5.dp.toPx(), p)
        }
        drawCircle(primary, 5.dp.toPx(), center)
    }
}

// ---------------------------------------------------------------- Pulse rings (Go button backdrop)

@Composable
fun PulseRings(color: Color, modifier: Modifier = Modifier, innerFraction: Float = 0.3f) {
    val t = rememberInfiniteTransition(label = "rings")
    val a by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearOutSlowInEasing)), label = "a",
    )
    val b by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearOutSlowInEasing), initialStartOffset = StartOffset(1300)),
        label = "b",
    )
    Canvas(modifier) {
        val base = size.minDimension * innerFraction
        val maxR = size.minDimension / 2f
        listOf(a, b).forEach { p ->
            drawCircle(color.copy(alpha = (1f - p) * 0.22f), base + (maxR - base) * p)
            drawCircle(color.copy(alpha = (1f - p) * 0.45f), base + (maxR - base) * p, style = Stroke(1.5.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------- Signal indicators

@Composable
fun SignalBars(level: Int, color: Color, modifier: Modifier = Modifier) {
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.1f
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (i + 1) / n
            drawRoundRect(
                color = if (i < level) color else inactive,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2.5f),
            )
        }
    }
}

@Composable
fun SignalRing(rssi: Int, track: Color, modifier: Modifier = Modifier) {
    val quality by animateFloatAsState(
        NetUtils.signalQuality(rssi), spring(stiffness = Spring.StiffnessLow), label = "quality",
    )
    val color by animateColorAsState(Semantic.forLevel(NetUtils.signalLevel(rssi)), tween(600), label = "color")
    Box(modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 7.dp.toPx()
            val tl = Offset(s / 2, s / 2)
            val sz = Size(size.width - s, size.height - s)
            drawArc(track, -90f, 360f, false, tl, sz, style = Stroke(s))
            drawArc(color, -90f, 360f * quality, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RollingText("$rssi", MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text("dBm", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------- Channel map

private val ApPalette = listOf(
    0xFF4285F4, 0xFFEA4335, 0xFF34A853, 0xFFFBBC05, 0xFFAB47BC, 0xFF00ACC1,
    0xFFFF7043, 0xFF9E9D24, 0xFF5C6BC0, 0xFFEC407A, 0xFF26A69A, 0xFF8D6E63,
).map { Color(it) }

fun apColor(key: String): Color = ApPalette[(key.hashCode() and 0x7fffffff) % ApPalette.size]

@Composable
fun ChannelGraph(aps: List<AccessPoint>, band: Band, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axisStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
    )
    val nameStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(band, aps.size, aps.firstOrNull()?.bssid) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val (fMin, fMax) = when (band) {
        Band.B24 -> 2400f to 2495f
        Band.B5 -> 5150f to 5895f
        Band.B6 -> 5925f to 7125f
    }
    val ticks = when (band) {
        Band.B24 -> (1..13).toList()
        Band.B5 -> listOf(36, 52, 64, 100, 116, 132, 149, 165)
        Band.B6 -> listOf(1, 37, 69, 101, 133, 165, 197, 229)
    }

    Canvas(modifier) {
        val left = 32.dp.toPx()
        val top = 14.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val w = size.width - left - 6.dp.toPx()
        val h = size.height - bottomPad - top
        val base = top + h
        fun x(f: Float) = left + (f - fMin) / (fMax - fMin) * w
        fun y(rssi: Float) = top + (1f - ((rssi + 100f) / 75f).coerceIn(0f, 1f)) * h

        listOf(-90, -70, -50, -30).forEach { db ->
            val yy = y(db.toFloat())
            drawLine(
                grid, Offset(left, yy), Offset(left + w, yy), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            val t = measurer.measure("$db", axisStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
        }
        drawLine(grid, Offset(left, base), Offset(left + w, base), 1.dp.toPx())
        ticks.forEach { ch ->
            val t = measurer.measure("$ch", axisStyle)
            drawText(t, topLeft = Offset(x(NetUtils.channelCenter(band, ch)) - t.size.width / 2f, base + 4.dp.toPx()))
        }

        // Weakest first so the strongest networks draw on top.
        aps.sortedBy { it.rssi }.forEach { ap ->
            val color = apColor(ap.bssid)
            val half = if (band == Band.B24 && ap.widthMhz == 20) 11f else ap.widthMhz / 2f
            val c = ap.centerFreq.toFloat()
            val xl = x(c - half)
            val xr = x(c + half)
            val peak = base - (base - y(ap.rssi.toFloat())) * grow.value
            val ctrlY = base - (base - peak) / 0.75f // makes the cubic's apex land exactly on `peak`
            val dx = xr - xl
            val path = Path().apply {
                moveTo(xl, base)
                cubicTo(xl + dx * 0.25f, ctrlY, xr - dx * 0.25f, ctrlY, xr, base)
            }
            drawPath(path, color.copy(alpha = if (ap.connected) 0.34f else 0.14f))
            drawPath(path, color, style = Stroke(if (ap.connected) 3.dp.toPx() else 1.6.dp.toPx()))
        }

        aps.filter { !it.hidden }.sortedByDescending { it.rssi }.take(6).forEach { ap ->
            val t = measurer.measure(ap.ssid.take(14), nameStyle.copy(color = apColor(ap.bssid)))
            val px = x(ap.centerFreq.toFloat())
            val py = base - (base - y(ap.rssi.toFloat())) * grow.value
            drawText(
                t,
                topLeft = Offset(
                    (px - t.size.width / 2f).coerceIn(left, (left + w - t.size.width).coerceAtLeast(left)),
                    (py - t.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                ),
                alpha = grow.value,
            )
        }
    }
}
