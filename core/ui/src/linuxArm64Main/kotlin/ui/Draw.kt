package ui

import hal.ec_color
import hal.ec_fill
import hal.ec_font_height
import hal.ec_font_width
import hal.ec_image_draw
import hal.ec_image_height
import hal.ec_image_load
import hal.ec_image_width
import hal.ec_text
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Offset(val x: Float, val y: Float) {
    operator fun plus(o: Offset) = Offset(x + o.x, y + o.y)
    operator fun minus(o: Offset) = Offset(x - o.x, y - o.y)
    operator fun times(f: Float) = Offset(x * f, y * f)

    companion object {
        val Zero = Offset(0f, 0f)
    }
}

data class Size(val width: Float, val height: Float) {
    val minDimension get() = min(width, height)
    val center get() = Offset(width / 2, height / 2)
}

sealed interface DrawStyle
data object Fill : DrawStyle
data class Stroke(val width: Float) : DrawStyle

// ------------------------------------------------------------------ shapes

/**
 * A shape's outline as horizontal spans: for a row at height [y] (relative to
 * the shape's top-left), writes x-pairs [left, right) into [out] and returns
 * how many floats it wrote.
 */
fun interface Outline {
    fun spans(y: Float, out: FloatArray): Int
}

fun interface Shape {
    fun outline(width: Float, height: Float): Outline
}

val RectangleShape = Shape { w, h -> RectOutline(0f, 0f, w, h) }

fun RoundedCornerShape(radius: Dp) = Shape { w, h -> RoundRectOutline(0f, 0f, w, h, radius.toPx().toFloat()) }

/** Corner radius as a percentage of the shorter side. */
fun RoundedCornerShape(percent: Int) = Shape { w, h -> RoundRectOutline(0f, 0f, w, h, min(w, h) * percent / 100f) }

val CircleShape = RoundedCornerShape(50)

internal class RectOutline(val l: Float, val t: Float, val r: Float, val b: Float) : Outline {
    override fun spans(y: Float, out: FloatArray): Int {
        if (y < t || y >= b || r <= l) return 0
        out[0] = l; out[1] = r
        return 2
    }
}

internal class RoundRectOutline(val l: Float, val t: Float, val r: Float, val b: Float, radius: Float) : Outline {
    private val rad = radius.coerceIn(0f, min(r - l, b - t) / 2)

    override fun spans(y: Float, out: FloatArray): Int {
        if (y < t || y >= b || r <= l) return 0
        val dy = when {
            y < t + rad -> t + rad - y
            y > b - rad -> y - (b - rad)
            else -> 0f
        }
        val inset = if (dy > 0f) rad - sqrt((rad * rad - dy * dy).coerceAtLeast(0f)) else 0f
        out[0] = l + inset; out[1] = r - inset
        return 2
    }
}

internal class OvalOutline(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : Outline {
    override fun spans(y: Float, out: FloatArray): Int {
        if (rx <= 0f || ry <= 0f) return 0
        val d = (y - cy) / ry
        if (d <= -1f || d >= 1f) return 0
        val dx = rx * sqrt(1f - d * d)
        out[0] = cx - dx; out[1] = cx + dx
        return 2
    }
}

/** Any polygon, even-odd rule (stars and other concave shapes work). */
internal class PolygonOutline(points: List<Offset>) : Outline {
    private val xs = FloatArray(points.size) { points[it].x }
    private val ys = FloatArray(points.size) { points[it].y }
    private val hits = FloatArray(points.size + 1)

    override fun spans(y: Float, out: FloatArray): Int {
        var n = 0
        val count = xs.size
        for (i in 0 until count) {
            val j = if (i + 1 == count) 0 else i + 1
            val y0 = ys[i]; val y1 = ys[j]
            if ((y0 <= y && y < y1) || (y1 <= y && y < y0)) {
                hits[n++] = xs[i] + (y - y0) / (y1 - y0) * (xs[j] - xs[i])
            }
        }
        hits.sort(0, n)
        val pairs = min(n / 2, out.size / 2)
        for (k in 0 until pairs * 2) out[k] = hits[k]
        return pairs * 2
    }
}

/** [outer] minus [inner], for strokes of convex shapes. */
internal class RingOutline(val outer: Outline, val inner: Outline) : Outline {
    private val tmp = FloatArray(4)

    override fun spans(y: Float, out: FloatArray): Int {
        if (outer.spans(y, out) == 0) return 0
        val l = out[0]; val r = out[1]
        if (inner.spans(y, tmp) == 0 || tmp[1] <= tmp[0]) return 2
        out[0] = l; out[1] = tmp[0].coerceAtLeast(l)
        out[2] = tmp[1].coerceAtMost(r); out[3] = r
        return 4
    }
}

// --------------------------------------------------------------- rasterizer

/**
 * Turns outlines into ec_fill() calls (minui: gr_fill): whole pixels in one fill per
 * row, plus one blended pixel at each end for the partly covered edge
 * (cheap horizontal anti-aliasing).
 */
internal object Raster {
    private val buf = FloatArray(64)

    fun fill(outline: Outline, originX: Float, originY: Float, top: Float, bottom: Float, color: Color) {
        if (color.alpha == 0) return
        val y0 = floor(originY + top).toInt()
        val y1 = ceil(originY + bottom).toInt()
        for (row in y0 until y1) {
            val n = outline.spans(row + 0.5f - originY, buf)
            var k = 0
            while (k < n) {
                span(row, originX + buf[k], originX + buf[k + 1], color)
                k += 2
            }
        }
    }

    private fun span(row: Int, l: Float, r: Float, color: Color) {
        if (r <= l) return
        val li = floor(l).toInt()
        val ri = floor(r).toInt()
        if (li == ri) {
            blend(li, row, r - l, color)
            return
        }
        val fullL = ceil(l).toInt()
        if (fullL > li) blend(li, row, fullL - l, color)
        if (ri > fullL) {
            setColor(color, 1f)
            ec_fill(fullL, row, ri, row + 1)
        }
        if (r > ri) blend(ri, row, r - ri, color)
    }

    private fun blend(x: Int, row: Int, coverage: Float, color: Color) {
        if (coverage < 0.02f) return
        setColor(color, coverage)
        ec_fill(x, row, x + 1, row + 1)
    }

    fun setColor(color: Color, coverage: Float = 1f) {
        ec_color(color.red, color.green, color.blue, (color.alpha * coverage).roundToInt())
    }

    fun rect(l: Int, t: Int, r: Int, b: Int, color: Color) {
        if (color.alpha == 0) return
        setColor(color)
        ec_fill(l, t, r, b)
    }
}

// --------------------------------------------------------------- DrawScope

/** Draws in pixels, relative to the element's top-left, like Compose's DrawScope. */
class DrawScope internal constructor(internal val originX: Int, internal val originY: Int, val size: Size) {
    val center get() = size.center

    private fun fill(outline: Outline, top: Float, bottom: Float, color: Color) =
        Raster.fill(outline, originX.toFloat(), originY.toFloat(), top, bottom, color)

    fun drawRect(color: Color, topLeft: Offset = Offset.Zero, size: Size = this.size, style: DrawStyle = Fill) {
        val l = topLeft.x; val t = topLeft.y; val r = l + size.width; val b = t + size.height
        when (style) {
            Fill -> if (l == floor(l) && t == floor(t) && r == floor(r) && b == floor(b)) {
                Raster.rect(originX + l.toInt(), originY + t.toInt(), originX + r.toInt(), originY + b.toInt(), color)
            } else {
                fill(RectOutline(l, t, r, b), t, b, color)
            }
            is Stroke -> fill(RingOutline(RectOutline(l, t, r, b), RectOutline(l + style.width, t + style.width, r - style.width, b - style.width)), t, b, color)
        }
    }

    fun drawRoundRect(color: Color, topLeft: Offset = Offset.Zero, size: Size = this.size, cornerRadius: Float, style: DrawStyle = Fill) {
        val l = topLeft.x; val t = topLeft.y; val r = l + size.width; val b = t + size.height
        val outer = RoundRectOutline(l, t, r, b, cornerRadius)
        when (style) {
            Fill -> fill(outer, t, b, color)
            is Stroke -> {
                val w = style.width
                fill(RingOutline(outer, RoundRectOutline(l + w, t + w, r - w, b - w, cornerRadius - w)), t, b, color)
            }
        }
    }

    fun drawCircle(color: Color, radius: Float = size.minDimension / 2, center: Offset = this.center, style: DrawStyle = Fill) =
        drawOval(color, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style)

    fun drawOval(color: Color, topLeft: Offset = Offset.Zero, size: Size = this.size, style: DrawStyle = Fill) {
        val rx = size.width / 2; val ry = size.height / 2
        val cx = topLeft.x + rx; val cy = topLeft.y + ry
        val outer = OvalOutline(cx, cy, rx, ry)
        when (style) {
            Fill -> fill(outer, topLeft.y, topLeft.y + size.height, color)
            is Stroke -> fill(RingOutline(outer, OvalOutline(cx, cy, rx - style.width, ry - style.width)), topLeft.y, topLeft.y + size.height, color)
        }
    }

    fun drawLine(color: Color, start: Offset, end: Offset, strokeWidth: Float = 1f) {
        val len = hypot(end.x - start.x, end.y - start.y)
        if (len == 0f) return
        // Perpendicular half-width vector; the line becomes a quad.
        val nx = -(end.y - start.y) / len * strokeWidth / 2
        val ny = (end.x - start.x) / len * strokeWidth / 2
        drawPolygon(color, listOf(
            Offset(start.x + nx, start.y + ny), Offset(end.x + nx, end.y + ny),
            Offset(end.x - nx, end.y - ny), Offset(start.x - nx, start.y - ny),
        ))
    }

    /** Filled polygon (even-odd), or its edges as lines of the stroke width. */
    fun drawPolygon(color: Color, points: List<Offset>, style: DrawStyle = Fill) {
        if (points.size < 2) return
        when (style) {
            Fill -> fill(PolygonOutline(points), points.minOf { it.y }, points.maxOf { it.y }, color)
            is Stroke -> for (i in points.indices) {
                drawLine(color, points[i], points[(i + 1) % points.size], style.width)
                drawCircle(color, style.width / 2, points[i])  // round joints
            }
        }
    }

    /** Angles in degrees, 0 = 3 o'clock, clockwise (as in Compose). */
    fun drawArc(color: Color, startAngle: Float, sweepAngle: Float, useCenter: Boolean, topLeft: Offset = Offset.Zero,
                size: Size = this.size, style: DrawStyle = Fill) {
        val rx = size.width / 2; val ry = size.height / 2
        val c = Offset(topLeft.x + rx, topLeft.y + ry)
        val steps = (kotlin.math.abs(sweepAngle) / 4f).toInt().coerceAtLeast(2)
        fun at(deg: Float, shrink: Float) = (deg * PI.toFloat() / 180f).let { Offset(c.x + cos(it) * (rx - shrink), c.y + sin(it) * (ry - shrink)) }
        val outer = (0..steps).map { at(startAngle + sweepAngle * it / steps, 0f) }
        when {
            style is Stroke && !useCenter -> {
                val inner = (steps downTo 0).map { at(startAngle + sweepAngle * it / steps, style.width) }
                drawPolygon(color, outer + inner)
            }
            style is Stroke -> drawPolygon(color, listOf(c) + outer, style)
            else -> drawPolygon(color, if (useCenter) listOf(c) + outer else outer)
        }
    }

    fun drawText(text: String, topLeft: Offset, color: Color, scale: Int = 1, bold: Boolean = false) =
        TextRenderer.draw(text, originX + topLeft.x.roundToInt(), originY + topLeft.y.roundToInt(), color, scale, bold)

    /** Size of [text] in the display font at [scale]. */
    fun measureText(text: String, scale: Int = 1): Size =
        TextRenderer.measure(text, scale).let { Size(it.width.toFloat(), it.height.toFloat()) }

    fun drawImage(image: ImageBitmap, topLeft: Offset = Offset.Zero) =
        ec_image_draw(image.handle, originX + topLeft.x.roundToInt(), originY + topLeft.y.roundToInt())

    /** Fill or stroke a [Shape] over the whole draw area (used by background/border). */
    internal fun drawShape(shape: Shape, color: Color, strokeWidth: Float? = null) {
        val w = size.width; val h = size.height
        if (shape === RectangleShape) {
            drawRect(color, style = if (strokeWidth == null) Fill else Stroke(strokeWidth))
            return
        }
        val outer = shape.outline(w, h)
        if (strokeWidth == null) {
            fill(outer, 0f, h, color)
        } else {
            val innerShape = shape.outline(w - 2 * strokeWidth, h - 2 * strokeWidth)
            val inner = Outline { y, out ->
                val n = innerShape.spans(y - strokeWidth, out)
                for (k in 0 until n) out[k] += strokeWidth
                n
            }
            fill(RingOutline(outer, inner), 0f, h, color)
        }
    }
}

// --------------------------------------------------------------- text, images

internal object TextRenderer {
    val charWidth by lazy { ec_font_width() }
    val charHeight by lazy { ec_font_height() }

    fun measure(text: String, scale: Int): IntSize {
        val lines = text.split('\n')
        return IntSize(lines.maxOf { it.length } * charWidth * scale, lines.size * charHeight * scale)
    }

    fun draw(text: String, x: Int, y: Int, color: Color, scale: Int, bold: Boolean) {
        if (color.alpha == 0) return
        Raster.setColor(color)
        var lineY = y
        for (line in text.split('\n')) {
            ec_text(x, lineY, line, scale, if (bold) 1 else 0)
            lineY += charHeight * scale
        }
    }
}

/** A PNG decoded by the backend (minui: /res/images/<name>.png). */
class ImageBitmap internal constructor(internal val handle: Long) {
    val width = ec_image_width(handle)
    val height = ec_image_height(handle)

    companion object {
        private val cache = mutableMapOf<String, ImageBitmap?>()

        /** [name] without the .png, as the backend wants it. */
        fun load(name: String): ImageBitmap? = cache.getOrPut(name) {
            val h = ec_image_load(name)
            if (h > 0) ImageBitmap(h) else null
        }
    }
}
