package ui.material

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.toKString
import platform.posix.getenv
import ui.Color
import ui.Density
import ui.DrawScope
import ui.Infinity
import ui.Layout
import ui.MeasurePolicy
import ui.MeasureResult
import ui.Modifier
import ui.Offset
import ui.measureText
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Roboto, from EC_FONT_DIR (default /res/fonts): Roboto-Regular.ttf and
 * Roboto-Medium.ttf (the AOSP tree has them: external/roboto-fonts).
 * Without them, text falls back to the display's bitmap font.
 */
internal object Fonts {
    private val dir = getenv("EC_FONT_DIR")?.toKString() ?: "/res/fonts"
    val regular by lazy { Font.load("$dir/Roboto-Regular.ttf") }
    val medium by lazy { Font.load("$dir/Roboto-Medium.ttf") ?: regular }
}

private fun TextStyle.font() = if (medium) Fonts.medium else Fonts.regular
private fun TextStyle.px() = size * Density.scale
private fun TextStyle.spacingPx() = letterSpacing * Density.scale
internal fun TextStyle.lineHeightPx() = ceil(lineHeight * Density.scale).toInt()

/** Calls [f] with each code point of [s] (UTF-16 pairs joined). */
private inline fun codePoints(s: String, f: (Int) -> Unit) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            f(((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000)
            i += 2
        } else {
            f(c.code)
            i++
        }
    }
}

/** Width of [s] in [style], in pixels. */
fun measureWidth(s: String, style: TextStyle): Float {
    val font = style.font() ?: return measureText(s).width.toFloat()
    val scale = style.px() / font.unitsPerEm
    var w = 0f
    var n = 0
    codePoints(s) { w += font.advance(font.glyph(it)) * scale; n++ }
    return w + style.spacingPx() * maxOf(0, n - 1)
}

/** Draws one line of [s] with its line box's top at [y]. */
fun DrawScope.drawText(s: String, x: Float, y: Float, style: TextStyle, color: Color) {
    val font = style.font()
    if (font == null) {
        drawText(s, Offset(x, y), color)
        return
    }
    val px = style.px()
    val scale = px / font.unitsPerEm
    val ascent = font.ascender * scale
    val height = (font.ascender - font.descender) * scale
    val baseline = (y + (style.lineHeightPx() - height) / 2 + ascent).roundToInt()
    var pen = x
    codePoints(s) { cp ->
        val g = font.glyph(cp)
        if (cp != ' '.code) {
            val glyph = font.render(g, px)
            if (glyph.w > 0) drawMask(glyph.mask, glyph.w, glyph.h, Offset((pen.roundToInt() + glyph.left).toFloat(), (baseline + glyph.top).toFloat()), color)
        }
        pen += font.advance(g) * scale + style.spacingPx()
    }
}

/** [text] in lines no wider than [width] pixels, broken between words (inside a word only if it must). */
fun wrapText(text: String, style: TextStyle, width: Int, maxLines: Int = Int.MAX_VALUE): List<String> {
    val out = ArrayList<String>()
    val space = measureWidth(" ", style)
    for (para in text.split('\n')) {
        var line = ""
        var lineW = 0f
        for (word0 in para.split(' ')) {
            var word = word0
            var w = measureWidth(word, style)
            while (w > width && word.length > 1) {  // longer than a line: cut it
                if (line.isNotEmpty()) { out += line; line = ""; lineW = 0f }
                var k = word.length - 1
                while (k > 1 && measureWidth(word.substring(0, k), style) > width) k--
                out += word.substring(0, k)
                word = word.substring(k)
                w = measureWidth(word, style)
            }
            if (line.isEmpty()) { line = word; lineW = w }
            else if (lineW + space + w <= width) { line += " $word"; lineW += space + w }
            else { out += line; line = word; lineW = w }
        }
        out += line
    }
    if (out.size <= maxLines) return out
    var last = out[maxLines - 1]
    while (last.isNotEmpty() && measureWidth("$last…", style) > width) last = last.dropLast(1)
    return out.take(maxLines - 1) + (last.trimEnd() + "…")
}

private class Lines {
    var lines: List<String> = emptyList()
    var widths = FloatArray(0)
}

/** Text in Material's type scale, wrapped at its box's width; [center] centers each line. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
    center: Boolean = false,
) {
    val holder = remember { Lines() }
    val policy = remember(text, style, maxLines) {
        MeasurePolicy { _, c ->
            val lines = if (c.maxWidth == Infinity) text.split('\n') else wrapText(text, style, c.maxWidth, maxLines)
            holder.lines = lines
            holder.widths = FloatArray(lines.size) { measureWidth(lines[it], style) }
            val w = ceil(holder.widths.maxOrNull() ?: 0f).toInt()
            MeasureResult(c.constrainWidth(w), c.constrainHeight(lines.size * style.lineHeightPx()))
        }
    }
    Layout(modifier, policy, drawContent = {
        val lh = style.lineHeightPx()
        holder.lines.forEachIndexed { i, line ->
            val x = if (center) (size.width - holder.widths[i]) / 2 else 0f
            drawText(line, x, (i * lh).toFloat(), style, color)
        }
    })
}
