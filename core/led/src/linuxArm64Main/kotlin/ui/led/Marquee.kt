package ui.led

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import ui.Alignment
import ui.Box
import ui.Color
import ui.Density
import ui.Device
import ui.Dp
import ui.DrawScope
import ui.Layout
import ui.MeasurePolicy
import ui.MeasureResult
import ui.Modifier
import ui.Offset
import ui.Text
import ui.measureText
import ui.dp

// Scrolling text, in the board's font. Text can't be clipped (the display
// draws whole strings), so at the screen's edges a marquee scrolls smoothly
// and inside a narrower box characters come and go whole at its edges.

/** How far a marquee has moved after [millis] at [speed] a second, in pixels. */
private fun moved(millis: Long, speed: Dp) = (millis / 1000f * speed.value * Density.scale).toInt()

/**
 * [text] scrolling from right to left, as wide as it may be and as high as the text.
 *
 * Once ([repeat] false): it comes in at the right edge and leaves at the left
 * one, then [onDone] runs. Round and round ([repeat] true): it starts at the
 * left edge and follows itself, [gap] characters apart.
 *
 * [speed] is per second. A dp is one LED on the LED boards and the phone's
 * characters are about as many dp wide, so a speed reads alike everywhere.
 * [colors], if given, colours each character instead of [color]: its index
 * and the milliseconds since the start. Changing [speed] keeps the clock;
 * changing [text] or [repeat] starts again.
 */
@Composable
fun Marquee(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    speed: Dp = 20.dp,
    repeat: Boolean = false,
    gap: Int = 3,
    scale: Int = 1,
    colors: ((index: Int, millis: Long) -> Color)? = null,
    onDone: () -> Unit = {},
) {
    val millis = remember(text, repeat) { mutableLongStateOf(0L) }
    val width = remember { IntArray(1) }  // from the last measure
    val currentSpeed by rememberUpdatedState(speed)
    val currentOnDone by rememberUpdatedState(onDone)
    val textSize = measureText(text, scale)
    val cell = measureText(" ", scale).width  // one character
    val currentTextWidth by rememberUpdatedState(textSize.width)
    LaunchedEffect(text, repeat) {
        var t0 = -1L
        while (true) {
            val done = withFrameNanos { now ->
                if (t0 < 0) t0 = now
                millis.longValue = (now - t0) / 1_000_000
                !repeat && moved(millis.longValue, currentSpeed) >= width[0] + currentTextWidth
            }
            if (done) break
        }
        currentOnDone()
    }
    val policy = remember(textSize) {
        MeasurePolicy { _, c ->
            val w = c.constrainWidth(if (c.hasBoundedWidth) c.maxWidth else textSize.width)
            width[0] = w
            MeasureResult(w, c.constrainHeight(textSize.height))
        }
    }
    Layout(modifier, policy, drawContent = {
        val w = size.width.toInt()
        val x = moved(millis.longValue, currentSpeed)
        if (!repeat) {
            drawRun(text, w - x, cell, color, colors, millis.longValue, scale)
        } else {
            val cycle = textSize.width + gap * cell
            var start = -(x % cycle)
            while (start < w) {
                drawRun(text, start, cell, color, colors, millis.longValue, scale)
                start += cycle
            }
        }
    })
}

/**
 * [text] at [x] in this box, without characters that would stick out of it:
 * only at the screen's edges, which clip, may they be partly visible.
 */
private fun DrawScope.drawRun(text: String, x: Int, cell: Int, color: Color, colors: ((Int, Long) -> Color)?, millis: Long, scale: Int) {
    val w = size.width.toInt()
    val minX = if (originX <= 0) Int.MIN_VALUE else 0  // left edge on the screen's: partly off is fine
    val maxX = if (originX + w >= Device.width) Int.MAX_VALUE else w
    var first = 0
    while (first < text.length && (x + first * cell + cell <= 0 || x + first * cell < minX)) first++
    var last = text.length - 1
    while (last >= first && (x + last * cell >= w || x + last * cell + cell > maxX)) last--
    if (first > last) return
    if (colors == null) {
        drawText(text.substring(first, last + 1), Offset((x + first * cell).toFloat(), 0f), color, scale)
    } else {
        for (i in first..last) drawText(text[i].toString(), Offset((x + i * cell).toFloat(), 0f), colors(i, millis), scale)
    }
}

/**
 * [text] still, aligned by [alignment], if it fits; otherwise scrolling
 * round and round (a [Marquee] with [repeat]). As wide as it may be.
 */
@Composable
fun FitOrScroll(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    speed: Dp = 20.dp,
    gap: Int = 3,
    scale: Int = 1,
    alignment: Alignment = Alignment.Center,
) {
    var width by remember { mutableIntStateOf(-1) }
    val textWidth = measureText(text, scale).width
    val policy = remember(textWidth) {
        MeasurePolicy { children, c ->
            val w = if (c.hasBoundedWidth) c.maxWidth else textWidth
            if (w != width) width = w  // decides what is composed: still or scrolling
            val child = children.firstOrNull()?.measure(c.copy(minWidth = w, maxWidth = w))
            MeasureResult(w, c.constrainHeight(child?.height ?: 0)) { children.firstOrNull()?.placeAt(0, 0) }
        }
    }
    Layout(modifier, policy) {
        when {
            width < 0 -> {}
            textWidth <= width -> Box(contentAlignment = alignment) { Text(text, color = color, scale = scale) }
            else -> Marquee(text, color = color, speed = speed, repeat = true, gap = gap, scale = scale)
        }
    }
}
