package ui.led

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import ui.Box
import ui.Canvas
import ui.Color
import ui.Layout
import ui.MeasurePolicy
import ui.MeasureResult
import ui.Modifier
import ui.Offset
import ui.Size
import ui.dp
import ui.fillMaxSize
import ui.offset
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [content] shown for [onMillis], hidden for [offMillis], over and over;
 * always shown when not [enabled]. Hidden, it keeps its place and its state
 * (a [Marquee] in it keeps moving).
 */
@Composable
fun Blink(onMillis: Long = 500, offMillis: Long = onMillis, enabled: Boolean = true, content: @Composable () -> Unit) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(enabled, onMillis, offMillis) {
        on = true
        while (enabled) {
            delay(onMillis)
            on = false
            delay(offMillis)
            on = true
        }
    }
    // Moved far off the screen, which clips it: there is no "invisible".
    Box(Modifier.offset(x = { if (on) 0.dp else HIDDEN }, y = { if (on) 0.dp else HIDDEN })) { content() }
}

private val HIDDEN = 100_000.dp

/**
 * A bar lit from the left to [progress] (0..1) of its width, the rest
 * [trackColor]. Size it with a modifier, e.g. Modifier.fillMaxWidth().height(1.dp).
 */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier, color: Color = Color.White, trackColor: Color = Color.Transparent) {
    Canvas(modifier) {
        val w = size.width.toInt()
        val lit = (progress.coerceIn(0f, 1f) * w).roundToInt()
        if (trackColor.alpha > 0) drawRect(trackColor)
        if (lit > 0) drawRect(color, size = Size(lit.toFloat(), size.height))
    }
}

/**
 * Bars standing on the bottom edge, one per value (0..[max]), [barWidth]
 * pixels wide and [gap] apart, from the left. Size it with a modifier.
 */
@Composable
fun BarGraph(
    values: List<Float>,
    modifier: Modifier,
    color: Color = Color.White,
    max: Float = 1f,
    barWidth: Int = 1,
    gap: Int = 0,
) {
    Canvas(modifier) {
        val h = size.height.toInt()
        for ((i, v) in values.withIndex()) {
            val x = i * (barWidth + gap)
            if (x >= size.width) break
            val bar = ((v / max).coerceIn(0f, 1f) * h).roundToInt()
            if (bar > 0) drawRect(color, Offset(x.toFloat(), (h - bar).toFloat()), Size(barWidth.toFloat(), bar.toFloat()))
        }
    }
}

/**
 * Which page of [count] is shown, as dots along the bottom edge, for
 * [showMillis] after [page] changes (0: always), on black one LED around
 * (on a strip 8 LEDs high: only beside). Draws over the whole box it is given.
 */
@Composable
fun PageDots(
    page: Int,
    count: Int,
    modifier: Modifier = Modifier.fillMaxSize(),
    showMillis: Long = 1500,
    color: Color = Color.White,
    dimColor: Color = Color(0xff3a3a3a),
) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(page, showMillis) {
        visible = true
        if (showMillis <= 0) return@LaunchedEffect
        delay(showMillis)
        visible = false
    }
    if (!visible) return
    Canvas(modifier) {
        val x0 = ((size.width - (count * 3 - 1)) / 2).toInt().toFloat()
        val y = size.height - 1
        val margin = if (size.height < 16) 0f else 1f
        drawRect(Color.Black, Offset(x0 - 1, y - margin), Size(count * 3 + 1f, 1f + margin))
        for (i in 0 until count) {
            drawRect(if (i == page) color else dimColor, Offset(x0 + i * 3, y), Size(2f, 1f))
        }
    }
}

/**
 * [content] for [target]; when [target] changes, the old one slides out and
 * the new one in, sideways over [durationMillis]: to the left when [forward]
 * says so (by default always), else to the right. Each target has its own
 * state. What slides out of the box is drawn there too (nothing is clipped),
 * so give it the screen's width.
 */
@Composable
fun <T> SlideTransition(
    target: T,
    modifier: Modifier = Modifier,
    durationMillis: Int = 400,
    forward: (from: T, to: T) -> Boolean = { _, _ -> true },
    content: @Composable (T) -> Unit,
) {
    var settled by remember { mutableStateOf(target) }  // shown when nothing moves
    val progress = remember(target) { mutableFloatStateOf(0f) }
    val from = settled
    val sliding = from != target
    val direction = if (!sliding || forward(from, target)) 1 else -1
    LaunchedEffect(target) {
        if (settled == target) return@LaunchedEffect
        var t0 = -1L
        while (progress.floatValue < 1f) {
            withFrameNanos { now ->
                if (t0 < 0) t0 = now
                progress.floatValue = ((now - t0) / 1e6f / durationMillis).coerceIn(0f, 1f)
            }
        }
        settled = target
    }
    val policy = remember(sliding, direction, progress) {
        MeasurePolicy { children, c ->
            var w = c.minWidth
            var h = c.minHeight
            for (child in children) {
                val s = child.measure(c)
                w = max(w, s.width)
                h = max(h, s.height)
            }
            MeasureResult(w, h) {
                val p = progress.floatValue
                val eased = p * p * (3 - 2 * p)
                if (sliding) {
                    children[0].placeAt((-direction * eased * w).roundToInt(), 0)
                    children[1].placeAt((direction * (1 - eased) * w).roundToInt(), 0)
                } else {
                    children[0].placeAt(0, 0)
                }
            }
        }
    }
    Layout(modifier, policy) {
        // One call site, by key: the old target keeps its state while it slides out.
        for (item in if (sliding) listOf(from, target) else listOf(target)) key(item) { content(item) }
    }
}
