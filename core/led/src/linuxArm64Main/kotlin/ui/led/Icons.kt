package ui.led

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ui.Color
import ui.DrawScope
import ui.Layout
import ui.MeasurePolicy
import ui.MeasureResult
import ui.Modifier
import ui.Offset
import ui.Size

/** A small one-colour picture, pixel by pixel: one pixel is one LED at scale 1. */
class LedBitmap(val width: Int, val height: Int, private val bits: BooleanArray) {
    init {
        require(bits.size == width * height) { "LedBitmap: ${bits.size} pixels for $width x $height" }
    }

    operator fun get(x: Int, y: Int) = bits[y * width + x]

    /** Mirrored left to right. */
    fun flipped() = LedBitmap(width, height, BooleanArray(bits.size) { this[width - 1 - it % width, it / width] })

    /** Turned a quarter clockwise. */
    fun rotated() = LedBitmap(height, width, BooleanArray(bits.size) { this[it / height, height - 1 - it % height] })
}

/**
 * A bitmap from its rows, top to bottom, separated by spaces or new lines:
 * '#' (or 'X', 'O', '*') is a lit pixel, anything else ('.') a dark one.
 *     ledBitmap(".#. ### .#.")    // a plus
 */
fun ledBitmap(rows: String): LedBitmap {
    val lines = rows.trim().split(Regex("\\s+"))
    val w = lines.maxOf { it.length }
    return LedBitmap(w, lines.size, BooleanArray(w * lines.size) {
        (lines[it / w].getOrNull(it % w) ?: '.') in "#XO*"
    })
}

/** [bitmap] with its top-left at [topLeft], each pixel [scale] x [scale], cut at the edges of this box. */
fun DrawScope.drawLedBitmap(bitmap: LedBitmap, topLeft: Offset, color: Color, scale: Int = 1) {
    val x0 = topLeft.x.toInt()
    val y0 = topLeft.y.toInt()
    val w = size.width.toInt()
    val h = size.height.toInt()
    for (y in 0 until bitmap.height) {
        val top = (y0 + y * scale).coerceAtLeast(0)
        val bottom = (y0 + (y + 1) * scale).coerceAtMost(h)
        if (top >= bottom) continue
        var x = 0
        while (x < bitmap.width) {
            if (!bitmap[x, y]) { x++; continue }
            var end = x  // a run of lit pixels: one rect
            while (end < bitmap.width && bitmap[end, y]) end++
            val left = (x0 + x * scale).coerceAtLeast(0)
            val right = (x0 + end * scale).coerceAtMost(w)
            if (left < right) drawRect(color, Offset(left.toFloat(), top.toFloat()), Size((right - left).toFloat(), (bottom - top).toFloat()))
            x = end
        }
    }
}

/** [bitmap], as big as it is ([scale] pixels per pixel of it). */
@Composable
fun LedIcon(bitmap: LedBitmap, modifier: Modifier = Modifier, color: Color = Color.White, scale: Int = 1) {
    val policy = remember(bitmap, scale) {
        MeasurePolicy { _, c -> MeasureResult(c.constrainWidth(bitmap.width * scale), c.constrainHeight(bitmap.height * scale)) }
    }
    Layout(modifier, policy, drawContent = { drawLedBitmap(bitmap, Offset.Zero, color, scale) })
}

/** Icons 7 pixels high, as the board font's capitals on LED panels. */
object LedIcons {
    val ArrowRight = ledBitmap("...#... ...##.. ...###. ####### ...###. ...##.. ...#...")
    val ArrowLeft = ArrowRight.flipped()
    val ArrowDown = ArrowRight.rotated()
    val ArrowUp = ArrowLeft.rotated()
    val Check = ledBitmap("....... ......# .....## #...##. ##.##.. .###... ..#....")
    val Cross = ledBitmap("#.....# .#...#. ..#.#.. ...#... ..#.#.. .#...#. #.....#")
    val Heart = ledBitmap(".##.##. ####### ####### ####### .#####. ..###.. ...#...")
    val Smile = ledBitmap(".#####. #.....# #.#.#.# #.....# ##...## #.###.# .#####.")
    val Play = ledBitmap("#.... ##... ###.. ####. ###.. ##... #....")
    val Pause = ledBitmap("##.## ##.## ##.## ##.## ##.## ##.## ##.##")
    val Bell = ledBitmap("...#... ..###.. .#####. .#####. .#####. ####### ...#...")
    val Note = ledBitmap("..##### ..#...# ..#...# ..#...# ###.### ###.### .......")

    /** All of them, by name. */
    val all: Map<String, LedBitmap> by lazy {
        mapOf(
            "ArrowRight" to ArrowRight, "ArrowLeft" to ArrowLeft, "ArrowUp" to ArrowUp, "ArrowDown" to ArrowDown,
            "Check" to Check, "Cross" to Cross, "Heart" to Heart, "Smile" to Smile,
            "Play" to Play, "Pause" to Pause, "Bell" to Bell, "Note" to Note,
        )
    }
}
