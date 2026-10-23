package matrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.delay
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.localtime_r
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.timespec
import platform.posix.tm
import ui.Canvas
import ui.Color
import ui.Device
import ui.DrawScope
import ui.Modifier
import ui.Offset
import ui.Size
import ui.fillMaxSize
import ui.rememberFrameMillis
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// The pages. Everything is drawn LED by LED (1x1 rects) or in the 5x7 font;
// sizes are in LEDs, laid out for 64x32.

/** A colour from a hue (0..1, wrapping) at full saturation, and a brightness. */
internal fun hue(h: Float, v: Float = 1f): Color {
    val x = (h - floor(h)) * 6f
    val f = x - x.toInt()
    val (r, g, b) = when (x.toInt()) {
        0 -> Triple(1f, f, 0f)
        1 -> Triple(1 - f, 1f, 0f)
        2 -> Triple(0f, 1f, f)
        3 -> Triple(0f, 1 - f, 1f)
        4 -> Triple(f, 0f, 1f)
        else -> Triple(1f, 0f, 1 - f)
    }
    fun ch(c: Float) = (c * v * 255).roundToInt().coerceIn(0, 255)
    return Color((0xff shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b))
}

private fun DrawScope.led(x: Int, y: Int, color: Color) = drawRect(color, Offset(x.toFloat(), y.toFloat()), Size(1f, 1f))

/** Left edge that centres [text]: its cells end in one blank column, which doesn't count. */
private fun DrawScope.centeredX(text: String, scale: Int) =
    ((size.width - (measureText(text, scale).width - scale)) / 2).toInt().toFloat()

// ------------------------------------------------------------------ clock

private class WallTime(val hour: Int, val minute: Int, val second: Int, val weekday: Int, val day: Int, val month: Int)

private fun wallTime(): WallTime = memScoped {
    val t = alloc<time_tVar>()
    time(t.ptr)
    val tm = alloc<tm>()
    localtime_r(t.ptr, tm.ptr)
    WallTime(tm.tm_hour, tm.tm_min, tm.tm_sec, tm.tm_wday, tm.tm_mday, tm.tm_mon)
}

private fun msToNextSecond(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    1000 - ts.tv_nsec / 1_000_000
}

private val DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private val CLOCK_THEMES = listOf(
    Color(0xff40c4ff) to Color(0xffff4081),
    Color(0xffffc400) to Color(0xff00e676),
    Color(0xffffffff) to Color(0xffff6d00),
    Color(0xffea80fc) to Color(0xff18ffff),
)

/** HH:MM, the date, and a bar of the minute's seconds. Redraws once a second. */
@Composable
internal fun ClockPage(style: Int) {
    var now by remember { mutableStateOf(wallTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(msToNextSecond())
            now = wallTime()
        }
    }
    val (main, accent) = CLOCK_THEMES[style % CLOCK_THEMES.size]
    Canvas(Modifier.fillMaxSize()) {
        val n = now
        val time = n.hour.toString().padStart(2, '0') + (if (n.second % 2 == 0) ":" else " ") +
            n.minute.toString().padStart(2, '0')
        drawText(time, Offset(centeredX(time, 2), 2f), main, scale = 2)
        val date = "${DAYS[n.weekday]} ${n.day} ${MONTHS[n.month]}"
        drawText(date, Offset(centeredX(date, 1), 19f), accent)
        val x0 = ((size.width - 60) / 2).toInt()
        val y = size.height.toInt() - 4
        for (s in 0 until 60) led(x0 + s, y, if (s <= n.second) accent else Color(0xff1c1c1c))
    }
}

// ----------------------------------------------------------------- plasma

private fun gradient(vararg stops: Long): IntArray = IntArray(256) { i ->
    val p = i / 255f * (stops.size - 1)
    val k = p.toInt().coerceAtMost(stops.size - 2)
    val t = p - k
    val a = Color(stops[k]); val b = Color(stops[k + 1])
    fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
    (0xff shl 24) or (ch(a.red, b.red) shl 16) or (ch(a.green, b.green) shl 8) or ch(a.blue, b.blue)
}

private val PALETTES = listOf(
    { IntArray(256) { hue(it / 256f).argb } },
    { gradient(0xff000000, 0xff800000, 0xffff3d00, 0xffffd600, 0xffffffff, 0xffffd600, 0xffff3d00, 0xff800000, 0xff000000) },
    { gradient(0xff001030, 0xff0050a0, 0xff00c8ff, 0xffe0ffff, 0xff00c8ff, 0xff0050a0, 0xff001030) },
    { gradient(0xff12002a, 0xff7b1fa2, 0xffff2e88, 0xffffb000, 0xffff2e88, 0xff7b1fa2, 0xff12002a) },
)

/** Four sine fields summed per LED, through a cycling palette. Every frame. */
@Composable
internal fun PlasmaPage(style: Int) {
    val millis by rememberFrameMillis(true)
    val palette = remember(style) { PALETTES[style % PALETTES.size]() }
    Canvas(Modifier.fillMaxSize()) {
        val t = millis / 1000f
        val w = size.width.toInt()
        val h = size.height.toInt()
        val cx = w / 2f + sin(t * 0.7f) * w / 4
        val cy = h / 2f + cos(t * 0.5f) * h / 4
        val shift = (t * 40).toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = sin(x / 6f + t) + sin(y / 4f - t * 1.3f) + sin((x + y) / 8f + t * 0.6f) +
                    sin(hypot(x - cx, y - cy) / 4f - t * 2f)
                val i = ((v + 4f) * 32f).toInt()
                led(x, y, Color(palette[(i + shift) and 255]))
            }
        }
    }
}

// ------------------------------------------------------------------- life

/** Each cell's age in generations, 0 = dead; the matrix wraps around at the edges. */
private fun randomCells(w: Int, h: Int) = IntArray(w * h) { if (Random.nextFloat() < 0.3f) 1 else 0 }

private fun step(cells: IntArray, w: Int, h: Int) = IntArray(w * h) { i ->
    val x = i % w
    val y = i / w
    var n = 0
    for (dy in -1..1) for (dx in -1..1) {
        if ((dx != 0 || dy != 0) && cells[(y + dy).mod(h) * w + (x + dx).mod(w)] > 0) n++
    }
    val age = cells[i]
    when {
        age > 0 && (n == 2 || n == 3) -> age + 1
        age == 0 && n == 3 -> 1
        else -> 0
    }
}

private val AGES = listOf(0xffe0ffff, 0xff18ffff, 0xff00b0ff, 0xff2962ff, 0xff6200ea, 0xffaa00ff).map { Color(it) }

/**
 * Conway's Game of Life, a generation every 110 ms; colour by age. When it
 * settles into still lifes and blinkers, it starts over. Enter: start over.
 */
@Composable
internal fun LifePage(style: Int) {
    val w = Device.width
    val h = Device.height
    var cells by remember(style) { mutableStateOf(randomCells(w, h)) }
    LaunchedEffect(style) {
        val recent = ArrayDeque<Int>()  // the last generations, by which cells live
        var repeats = 0
        while (true) {
            delay(110)
            val next = step(cells, w, h)
            val key = next.map { if (it > 0) 1 else 0 }.hashCode()
            repeats = if (key in recent) repeats + 1 else 0
            recent.addLast(key)
            if (recent.size > 12) recent.removeFirst()
            cells = if (repeats > 40) randomCells(w, h).also { recent.clear(); repeats = 0 } else next
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val c = cells
        for (i in c.indices) {
            if (c[i] > 0) led(i % w, i / w, AGES[minOf((c[i] - 1) / 3, AGES.size - 1)])
        }
    }
}

// ---------------------------------------------------------------- marquee

private const val MESSAGE = "Jetpack Compose runtime on a HUB75 LED matrix * no Android, no JVM * "

/** The message in rainbow letters between two waves. Enter: faster. */
@Composable
internal fun MarqueePage(style: Int) {
    val millis by rememberFrameMillis(true)
    Canvas(Modifier.fillMaxSize()) {
        val t = millis / 1000f
        val w = size.width.toInt()
        val h = size.height.toInt()
        val cell = 12  // a 6-pixel cell at scale 2
        val total = MESSAGE.length * cell
        val speed = if (style % 2 == 0) 20f else 45f  // LEDs per second
        val start = -((t * speed).toInt() % total)
        val y = (h - 14) / 2f
        for (copy in 0..1) {
            for (i in MESSAGE.indices) {
                val x = start + copy * total + i * cell
                if (x >= w || x + cell <= 0) continue
                drawText(MESSAGE[i].toString(), Offset(x.toFloat(), y), hue(i * 0.04f - t * 0.25f), scale = 2)
            }
        }
        for (x in 0 until w) {
            val a = sin(x / 5f + t * 4f) * 2f
            val c = hue(x / w.toFloat() + t * 0.1f, 0.6f)
            led(x, (3 + a).roundToInt(), c)
            led(x, (h - 5 - a).roundToInt(), c)
        }
    }
}
