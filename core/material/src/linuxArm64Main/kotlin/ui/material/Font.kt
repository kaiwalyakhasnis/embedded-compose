package ui.material

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A TrueType font (glyf outlines, like Roboto), rasterized here with
 * anti-aliasing: the display's own font is one bitmap size. No hinting and
 * no kerning; glyphs are cached per size.
 */
class Font private constructor(private val d: ByteArray) {
    private fun u8(o: Int) = d[o].toInt() and 0xff
    private fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
    private fun s16(o: Int) = u16(o).toShort().toInt()
    private fun u32(o: Int) = (u16(o) shl 16) or u16(o + 2)
    private fun f2dot14(o: Int) = s16(o) / 16384f

    private val tables = HashMap<String, Int>().apply {
        for (i in 0 until u16(4)) {
            val r = 12 + 16 * i
            put(CharArray(4) { d[r + it].toInt().toChar() }.concatToString(), u32(r + 8))
        }
    }
    private fun table(tag: String) = tables[tag] ?: error("font has no $tag table")

    private val head = table("head")
    val unitsPerEm = u16(head + 18)
    private val longLoca = s16(head + 50) == 1
    private val hhea = table("hhea")
    /** Above and below the baseline, in font units (descender is negative). */
    val ascender = s16(hhea + 4)
    val descender = s16(hhea + 6)
    private val hMetrics = u16(hhea + 34)
    private val hmtx = table("hmtx")
    private val loca = table("loca")
    private val glyf = table("glyf")
    private val cmap = findCmap()

    /** The format 4 (BMP) or 12 (full Unicode) subtable of the Windows Unicode cmap. */
    private fun findCmap(): Int {
        val t = table("cmap")
        var best = -1
        for (i in 0 until u16(t + 2)) {
            val r = t + 4 + 8 * i
            val platform = u16(r); val encoding = u16(r + 2)
            val sub = t + u32(r + 4)
            if (platform == 3 && encoding == 10 && u16(sub) == 12) return sub
            if ((platform == 3 && encoding == 1 || platform == 0) && u16(sub) == 4) best = sub
        }
        return best
    }

    private val glyphIds = HashMap<Int, Int>()

    /** The glyph of [code] (a Unicode code point); 0 (.notdef, a box) if the font hasn't got it. */
    fun glyph(code: Int): Int = glyphIds.getOrPut(code) { lookup(code) }

    private fun lookup(c: Int): Int {
        val t = cmap
        if (t < 0) return 0
        if (u16(t) == 12) {
            for (i in 0 until u32(t + 12)) {
                val g = t + 16 + 12 * i
                if (c >= u32(g) && c <= u32(g + 4)) return u32(g + 8) + (c - u32(g))
            }
            return 0
        }
        val segs = u16(t + 6) / 2
        val ends = t + 14
        val starts = ends + 2 * segs + 2
        val deltas = starts + 2 * segs
        val ranges = deltas + 2 * segs
        for (i in 0 until segs) {
            if (c > u16(ends + 2 * i)) continue
            val start = u16(starts + 2 * i)
            if (c < start) return 0
            val delta = u16(deltas + 2 * i)
            val ro = u16(ranges + 2 * i)
            if (ro == 0) return (c + delta) and 0xffff
            val g = u16(ranges + 2 * i + ro + 2 * (c - start))
            return if (g == 0) 0 else (g + delta) and 0xffff
        }
        return 0
    }

    /** Advance width of glyph [g], in font units. */
    fun advance(g: Int): Int = u16(hmtx + 4 * (if (g < hMetrics) g else hMetrics - 1))

    private fun glyphOffset(g: Int): Pair<Int, Int> =
        if (longLoca) u32(loca + 4 * g) to u32(loca + 4 * g + 4)
        else 2 * u16(loca + 2 * g) to 2 * u16(loca + 2 * g + 2)

    /** Sends glyph [g]'s outline to [out], through the affine transform (xx, xy, yx, yy, dx, dy). */
    private fun outline(g: Int, out: PathSink, m: FloatArray, depth: Int = 0) {
        val (start, end) = glyphOffset(g)
        if (end <= start || depth > 4) return
        val o = glyf + start
        val contours = s16(o)
        if (contours < 0) {
            composite(o + 10, out, m, depth)
            return
        }
        val ends = IntArray(contours) { u16(o + 10 + 2 * it) }
        val n = if (contours == 0) 0 else ends.last() + 1
        var p = o + 12 + 2 * contours + u16(o + 10 + 2 * contours)
        val flags = IntArray(n)
        var k = 0
        while (k < n) {
            val f = u8(p++)
            flags[k++] = f
            if (f and 8 != 0) repeat(u8(p++)) { if (k < n) flags[k++] = f }
        }
        val xs = FloatArray(n); val ys = FloatArray(n)
        var v = 0
        for (i in 0 until n) {
            val f = flags[i]
            v += when {
                f and 2 != 0 -> u8(p++).let { if (f and 0x10 != 0) it else -it }
                f and 0x10 != 0 -> 0
                else -> s16(p).also { p += 2 }
            }
            xs[i] = v.toFloat()
        }
        v = 0
        for (i in 0 until n) {
            val f = flags[i]
            v += when {
                f and 4 != 0 -> u8(p++).let { if (f and 0x20 != 0) it else -it }
                f and 0x20 != 0 -> 0
                else -> s16(p).also { p += 2 }
            }
            ys[i] = v.toFloat()
        }
        fun tx(i: Int) = m[0] * xs[i] + m[2] * ys[i] + m[4]
        fun ty(i: Int) = m[1] * xs[i] + m[3] * ys[i] + m[5]
        var first = 0
        for (c in 0 until contours) {
            val last = ends[c]
            val count = last - first + 1
            if (count >= 2) {
                fun on(i: Int) = flags[first + (i % count)] and 1 != 0
                fun x(i: Int) = tx(first + (i % count))
                fun y(i: Int) = ty(first + (i % count))
                // Start at an on-curve point (or between two off-curve ones).
                val s = (0 until count).firstOrNull { on(it) }
                val sx: Float; val sy: Float
                if (s != null) { sx = x(s); sy = y(s) } else { sx = (x(0) + x(1)) / 2; sy = (y(0) + y(1)) / 2 }
                val base = s ?: 0
                out.moveTo(sx, sy)
                var cx = 0f; var cy = 0f; var pending = false
                for (j in 1..count) {
                    val i = base + j
                    val px = x(i); val py = y(i)
                    if (on(i)) {
                        if (pending) out.quadTo(cx, cy, px, py) else out.lineTo(px, py)
                        pending = false
                    } else {
                        if (pending) out.quadTo(cx, cy, (cx + px) / 2, (cy + py) / 2)
                        cx = px; cy = py; pending = true
                    }
                }
                if (pending) out.quadTo(cx, cy, sx, sy)
                out.close()
            }
            first = last + 1
        }
    }

    private fun composite(at: Int, out: PathSink, m: FloatArray, depth: Int) {
        var p = at
        while (true) {
            val flags = u16(p); val g = u16(p + 2); p += 4
            val a: Int; val b: Int
            if (flags and 1 != 0) { a = s16(p); b = s16(p + 2); p += 4 } else { a = d[p].toInt(); b = d[p + 1].toInt(); p += 2 }
            var xx = 1f; var xy = 0f; var yx = 0f; var yy = 1f
            when {
                flags and 8 != 0 -> { xx = f2dot14(p); yy = xx; p += 2 }
                flags and 0x40 != 0 -> { xx = f2dot14(p); yy = f2dot14(p + 2); p += 4 }
                flags and 0x80 != 0 -> { xx = f2dot14(p); xy = f2dot14(p + 2); yx = f2dot14(p + 4); yy = f2dot14(p + 6); p += 8 }
            }
            val dx = if (flags and 2 != 0) a.toFloat() else 0f
            val dy = if (flags and 2 != 0) b.toFloat() else 0f
            // This component's transform, then the parent's.
            val c = floatArrayOf(
                m[0] * xx + m[2] * xy, m[1] * xx + m[3] * xy,
                m[0] * yx + m[2] * yy, m[1] * yx + m[3] * yy,
                m[0] * dx + m[2] * dy + m[4], m[1] * dx + m[3] * dy + m[5],
            )
            outline(g, out, c, depth + 1)
            if (flags and 0x20 == 0) break
        }
    }

    private val cache = HashMap<Long, Glyph>()

    /** Glyph [g] at [px] pixels per em, rasterized (cached). */
    fun render(g: Int, px: Float): Glyph = cache.getOrPut((g.toLong() shl 32) or (px * 64).toLong()) {
        val s = px / unitsPerEm
        val bounds = Bounds()
        outline(g, bounds, floatArrayOf(s, 0f, 0f, -s, 0f, 0f))  // y down
        if (bounds.empty) return@getOrPut Glyph(ByteArray(0), 0, 0, 0, 0)
        val left = floor(bounds.minX).toInt() - 1
        val top = floor(bounds.minY).toInt() - 1
        val w = ceil(bounds.maxX).toInt() - left + 2
        val h = ceil(bounds.maxY).toInt() - top + 1
        val r = Coverage(w, h)
        outline(g, r, floatArrayOf(s, 0f, 0f, -s, -left.toFloat(), -top.toFloat()))
        Glyph(r.mask(), w, h, left, top)
    }

    companion object {
        private val loaded = HashMap<String, Font?>()

        /** Loads [path] (cached); null if it isn't there or isn't a TrueType font. */
        fun load(path: String): Font? = loaded.getOrPut(path) {
            val bytes = readFile(path) ?: return@getOrPut null
            runCatching { Font(bytes).takeIf { it.cmap >= 0 } }.getOrNull()
        }
    }
}

/** A rasterized glyph: [w] x [h] coverage, its top-left at ([left], [top]) from the pen on the baseline. */
class Glyph(val mask: ByteArray, val w: Int, val h: Int, val left: Int, val top: Int)

internal interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float)
    fun close()
}

private class Bounds : PathSink {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    val empty get() = minX > maxX

    private fun add(x: Float, y: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
    }
    override fun moveTo(x: Float, y: Float) = add(x, y)
    override fun lineTo(x: Float, y: Float) = add(x, y)
    override fun quadTo(cx: Float, cy: Float, x: Float, y: Float) { add(cx, cy); add(x, y) }
    override fun close() {}
}

/**
 * Exact-area coverage of closed outlines (the accumulation method of
 * font-rs): each edge adds its signed area to the cells it crosses, and a
 * running sum along the rows gives every pixel's coverage. Also used for
 * shapes and icons ([Vector]).
 */
internal class Coverage(val w: Int, val h: Int) : PathSink {
    private val acc = FloatArray(w * h + 4)
    private var x0 = 0f; private var y0 = 0f
    private var sx = 0f; private var sy = 0f

    override fun moveTo(x: Float, y: Float) { x0 = x; y0 = y; sx = x; sy = y }
    override fun lineTo(x: Float, y: Float) { line(x0, y0, x, y); x0 = x; y0 = y }
    override fun quadTo(cx: Float, cy: Float, x: Float, y: Float) {
        val dx = x0 - 2 * cx + x; val dy = y0 - 2 * cy + y
        val dev = dx * dx + dy * dy
        val n = if (dev < 0.333f) 1 else 1 + floor(sqrt(sqrt(3f * dev))).toInt()
        val px = x0; val py = y0
        for (i in 1..n) {
            val t = i.toFloat() / n
            val u = 1 - t
            lineTo(u * u * px + 2 * u * t * cx + t * t * x, u * u * py + 2 * u * t * cy + t * t * y)
        }
    }
    override fun close() { if (x0 != sx || y0 != sy) lineTo(sx, sy) }

    private fun line(ax: Float, ay: Float, bx: Float, by: Float) {
        if (abs(ay - by) <= 1e-6f) return
        val dir: Float; val p0x: Float; val p0y: Float; val p1x: Float; val p1y: Float
        if (ay < by) { dir = 1f; p0x = ax; p0y = ay; p1x = bx; p1y = by } else { dir = -1f; p0x = bx; p0y = by; p1x = ax; p1y = ay }
        val dxdy = (p1x - p0x) / (p1y - p0y)
        var x = p0x
        if (p0y < 0f) x -= p0y * dxdy
        val yStart = maxOf(0, p0y.toInt())
        val yEnd = min(h, ceil(p1y).toInt())
        for (y in yStart until yEnd) {
            val line = y * w
            val dy = min((y + 1).toFloat(), p1y) - maxOf(y.toFloat(), p0y)
            val xNext = x + dxdy * dy
            val d = dy * dir
            val xa = min(x, xNext).coerceIn(0f, (w - 1).toFloat())
            val xb = maxOf(x, xNext).coerceIn(0f, (w - 1).toFloat())
            val xaFloor = floor(xa)
            val xai = xaFloor.toInt()
            val xbCeil = ceil(xb)
            val xbi = xbCeil.toInt()
            if (xbi <= xai + 1) {
                val xmf = 0.5f * (xa + xb) - xaFloor
                acc[line + xai] += d - d * xmf
                acc[line + xai + 1] += d * xmf
            } else {
                val s = 1f / (xb - xa)
                val xaf = xa - xaFloor
                val a0 = 0.5f * s * (1 - xaf) * (1 - xaf)
                val xbf = xb - xbCeil + 1
                val am = 0.5f * s * xbf * xbf
                acc[line + xai] += d * a0
                if (xbi == xai + 2) {
                    acc[line + xai + 1] += d * (1 - a0 - am)
                } else {
                    val a1 = s * (1.5f - xaf)
                    acc[line + xai + 1] += d * (a1 - a0)
                    for (xi in xai + 2 until xbi - 1) acc[line + xi] += d * s
                    val a2 = a1 + (xbi - xai - 3) * s
                    acc[line + xbi - 1] += d * (1 - a2 - am)
                }
                acc[line + xbi] += d * am
            }
            x = xNext
        }
    }

    /** Coverage as bytes; overlapping outlines of the same direction add up to full, not more. */
    fun mask(): ByteArray {
        val out = ByteArray(w * h)
        var a = 0f
        for (i in 0 until w * h) {
            a += acc[i]
            out[i] = GAMMA[(min(abs(a), 1f) * 255f + 0.5f).toInt()]
        }
        return out
    }

    /** As [mask], as floats from 0 to 1, for combining two outlines ([Vector]'s cut-outs). */
    fun values(): FloatArray {
        val out = FloatArray(w * h)
        var a = 0f
        for (i in 0 until w * h) {
            a += acc[i]
            out[i] = min(abs(a), 1f)
        }
        return out
    }
}

/**
 * Blending happens on sRGB values, which makes light text on a dark
 * background look thin: edges get a little more coverage.
 */
internal val GAMMA = ByteArray(256) { (255.0 * (it / 255.0).pow(0.8) + 0.5).toInt().toByte() }

private fun readFile(path: String): ByteArray? {
    val f = fopen(path, "rb") ?: return null
    try {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        while (true) {
            val buf = ByteArray(65536)
            val n = buf.usePinned { fread(it.addressOf(0), 1u, buf.size.convert(), f) }.toInt()
            if (n <= 0) break
            chunks += if (n == buf.size) buf else buf.copyOf(n)
            total += n
        }
        val out = ByteArray(total)
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    } finally {
        fclose(f)
    }
}
