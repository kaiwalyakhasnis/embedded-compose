package ui.material

import hal.ec_mask
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import ui.Color
import ui.DrawScope
import ui.Offset
import ui.Size
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Anti-aliased drawing in one color, into a coverage mask: icons, check
 * marks, slider parts, progress arcs. Everything drawn adds up; a [cut]
 * shape is a hole through all the rest (an icon's cut-outs). Coordinates
 * are pixels from the mask's top-left.
 */
class Vector(val width: Int, val height: Int) {
    private val fill = Coverage(width, height)
    private var holes: Coverage? = null

    private fun sink(cut: Boolean) = if (cut) holes ?: Coverage(width, height).also { holes = it } else fill

    /** A closed outline; [hole] reverses it (an opening in what it overlaps, within one call). */
    private fun outline(s: PathSink, xy: FloatArray, hole: Boolean = false) {
        val n = xy.size / 2
        if (n < 3) return
        var area = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1]
        }
        val reverse = (area < 0) != hole
        fun at(k: Int) = if (reverse) n - 1 - k else k
        s.moveTo(xy[2 * at(0)], xy[2 * at(0) + 1])
        for (k in 1 until n) s.lineTo(xy[2 * at(k)], xy[2 * at(k) + 1])
        s.close()
    }

    fun polygon(vararg xy: Float, cut: Boolean = false) = outline(sink(cut), xy)

    private fun arcPoints(cx: Float, cy: Float, rx: Float, ry: Float, start: Float, sweep: Float): FloatArray {
        val n = max(8, (max(rx, ry) * kotlin.math.abs(sweep) / 360f * 2.2f).toInt().coerceAtMost(160))
        return FloatArray(2 * (n + 1)) { k ->
            val a = (start + sweep * (k / 2) / n) * PI.toFloat() / 180f
            if (k % 2 == 0) cx + rx * cos(a) else cy + ry * sin(a)
        }
    }

    fun circle(cx: Float, cy: Float, r: Float, cut: Boolean = false) = oval(cx, cy, r, r, cut)

    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, cut: Boolean = false) {
        val p = arcPoints(cx, cy, rx, ry, 0f, 360f)
        outline(sink(cut), p.copyOf(p.size - 2))  // the last point is the first again
    }

    /** A circle's outline [w] wide, centered on radius [r]. */
    fun ring(cx: Float, cy: Float, r: Float, w: Float, cut: Boolean = false) {
        val s = sink(cut)
        val outer = arcPoints(cx, cy, r + w / 2, r + w / 2, 0f, 360f)
        val inner = arcPoints(cx, cy, r - w / 2, r - w / 2, 0f, 360f)
        outline(s, outer.copyOf(outer.size - 2))
        if (r > w / 2) outline(s, inner.copyOf(inner.size - 2), hole = true)
    }

    /** A line [w] wide with round ends. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, cut: Boolean = false, round: Boolean = true) {
        val len = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
        if (len > 0f) {
            val nx = -(y2 - y1) / len * w / 2
            val ny = (x2 - x1) / len * w / 2
            polygon(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, cut = cut)
        }
        if (round) { circle(x1, y1, w / 2, cut); circle(x2, y2, w / 2, cut) }
    }

    fun rect(l: Float, t: Float, w: Float, h: Float, cut: Boolean = false) = polygon(l, t, l + w, t, l + w, t + h, l, t + h, cut = cut)

    /** A rounded rectangle; radii from the top-left corner, clockwise. */
    fun roundRect(l: Float, t: Float, w: Float, h: Float, tl: Float, tr: Float = tl, br: Float = tl, bl: Float = tl, cut: Boolean = false) =
        outline(sink(cut), roundRectPoints(l, t, w, h, tl, tr, br, bl))

    /** A rounded rectangle's outline, [sw] wide, inside its bounds. */
    fun roundRectStroke(l: Float, t: Float, w: Float, h: Float, r: Float, sw: Float, cut: Boolean = false) {
        val s = sink(cut)
        outline(s, roundRectPoints(l, t, w, h, r, r, r, r))
        val ir = max(0f, r - sw)
        outline(s, roundRectPoints(l + sw, t + sw, w - 2 * sw, h - 2 * sw, ir, ir, ir, ir), hole = true)
    }

    private fun roundRectPoints(l: Float, t: Float, w: Float, h: Float, tl0: Float, tr0: Float, br0: Float, bl0: Float): FloatArray {
        val m = min(w, h) / 2
        val tl = min(tl0, m); val tr = min(tr0, m); val br = min(br0, m); val bl = min(bl0, m)
        return arcPoints(l + tl, t + tl, tl, tl, 180f, 90f) + arcPoints(l + w - tr, t + tr, tr, tr, 270f, 90f) +
            arcPoints(l + w - br, t + h - br, br, br, 0f, 90f) + arcPoints(l + bl, t + h - bl, bl, bl, 90f, 90f)
    }

    /** An arc of a circle, [w] wide, from [start] degrees (0 = 3 o'clock, clockwise) over [sweep]. */
    fun arc(cx: Float, cy: Float, r: Float, w: Float, start: Float, sweep: Float, round: Boolean = false, cut: Boolean = false) {
        val outer = arcPoints(cx, cy, r + w / 2, r + w / 2, start, sweep)
        val inner = arcPoints(cx, cy, r - w / 2, r - w / 2, start + sweep, -sweep)
        outline(sink(cut), outer + inner)
        if (round) {
            for (a in listOf(start, start + sweep)) {
                val rad = a * PI.toFloat() / 180f
                circle(cx + r * cos(rad), cy + r * sin(rad), w / 2, cut)
            }
        }
    }

    /** A slice of an oval in the box ([l], [t], [w], [h]), with the center. */
    fun pie(l: Float, t: Float, w: Float, h: Float, start: Float, sweep: Float, cut: Boolean = false) =
        outline(sink(cut), floatArrayOf(l + w / 2, t + h / 2) + arcPoints(l + w / 2, t + h / 2, w / 2, h / 2, start, sweep))

    /** The coverage of all that was drawn. */
    fun mask(): ByteArray {
        val h = holes ?: return fill.mask()
        val f = fill.values()
        val c = h.values()
        return ByteArray(f.size) { GAMMA[(f[it] * (1 - c[it]) * 255f + 0.5f).toInt()] }
    }
}

/** An alpha mask in [color]: [width] x [height] bytes of coverage, row by row (the backend blends it: ec_mask). */
fun DrawScope.drawMask(mask: ByteArray, width: Int, height: Int, topLeft: Offset, color: Color) {
    if (color.alpha == 0 || width <= 0 || height <= 0) return
    mask.usePinned {
        ec_mask(originX + topLeft.x.roundToInt(), originY + topLeft.y.roundToInt(), width, height,
            it.addressOf(0).reinterpret(), color.red, color.green, color.blue, color.alpha)
    }
}

/** Draws with a [Vector] the size of this box (or [w] x [h] at [x], [y]). */
fun DrawScope.drawVector(color: Color, x: Int = 0, y: Int = 0, w: Int = size.width.toInt(), h: Int = size.height.toInt(), draw: Vector.() -> Unit) {
    if (w <= 0 || h <= 0) return
    val v = Vector(w, h).apply(draw)
    drawMask(v.mask(), w, h, Offset(x.toFloat(), y.toFloat()), color)
}

private val corners = HashMap<Long, Array<ByteArray>>()

/** Corner masks for radius [r] (and a stroke [s] wide, or filled when 0): top-left, top-right, bottom-right, bottom-left. */
private fun cornerMasks(r: Int, s: Int): Array<ByteArray> = corners.getOrPut((r.toLong() shl 20) or s.toLong()) {
    val tl = ByteArray(r * r)
    for (j in 0 until r) for (i in 0 until r) {
        val dx = r - (i + 0.5f); val dy = r - (j + 0.5f)
        val d = sqrt(dx * dx + dy * dy)
        var cov = (r - d + 0.5f).coerceIn(0f, 1f)
        if (s > 0) cov -= (r - s - d + 0.5f).coerceIn(0f, 1f)
        tl[j * r + i] = GAMMA[(cov.coerceIn(0f, 1f) * 255f + 0.5f).toInt()]
    }
    fun flip(h: Boolean, v: Boolean) = ByteArray(r * r) { k ->
        val i = k % r; val j = k / r
        tl[(if (v) r - 1 - j else j) * r + (if (h) r - 1 - i else i)]
    }
    arrayOf(tl, flip(true, false), flip(true, true), flip(false, true))
}

/**
 * An anti-aliased rounded rectangle at whole pixels: the straight parts
 * filled at once, the corners from cached masks, so big ones (cards) are
 * cheap. [stroke] > 0 draws only an outline that wide, inside the bounds.
 */
fun DrawScope.roundRect(color: Color, radius: Float, x: Int = 0, y: Int = 0, w: Int = size.width.toInt(), h: Int = size.height.toInt(), stroke: Float = 0f) {
    if (w <= 0 || h <= 0 || color.alpha == 0) return
    val r = radius.roundToInt().coerceIn(0, min(w, h) / 2)
    val s = if (stroke > 0f) max(1, stroke.roundToInt()) else 0
    fun rect(l: Int, t: Int, rw: Int, rh: Int) {
        if (rw > 0 && rh > 0) drawRect(color, Offset(l.toFloat(), t.toFloat()), Size(rw.toFloat(), rh.toFloat()))
    }
    if (r > 0) {
        val m = cornerMasks(r, s)
        drawMask(m[0], r, r, Offset(x.toFloat(), y.toFloat()), color)
        drawMask(m[1], r, r, Offset((x + w - r).toFloat(), y.toFloat()), color)
        drawMask(m[2], r, r, Offset((x + w - r).toFloat(), (y + h - r).toFloat()), color)
        drawMask(m[3], r, r, Offset(x.toFloat(), (y + h - r).toFloat()), color)
    }
    if (s == 0) {
        rect(x + r, y, w - 2 * r, r)
        rect(x, y + r, w, h - 2 * r)
        rect(x + r, y + h - r, w - 2 * r, r)
    } else {
        rect(x + r, y, w - 2 * r, s)
        rect(x + r, y + h - s, w - 2 * r, s)
        rect(x, y + r, s, h - 2 * r)
        rect(x + w - s, y + r, s, h - 2 * r)
    }
}

/** An anti-aliased circle (or ring, [stroke] wide inside it) centered at ([cx], [cy]). */
fun DrawScope.circle(color: Color, cx: Float, cy: Float, r: Float, stroke: Float = 0f) {
    val box = ceil(2 * r).toInt() + 2
    val x = (cx - box / 2f).roundToInt()
    val y = (cy - box / 2f).roundToInt()
    drawVector(color, x, y, box, box) {
        if (stroke > 0f) ring(box / 2f, box / 2f, r - stroke / 2, stroke) else circle(box / 2f, box / 2f, r)
    }
}
