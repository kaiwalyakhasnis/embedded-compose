package charger

import ui.Color
import ui.DrawScope
import ui.Offset
import ui.Size
import ui.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The charging scene: a glass orb filling with liquid (two waves, rising
// bubbles) inside a glowing progress ring with a spark at its head, a comet
// orbiting while charging, energy rising from a pulsing bolt, and the level
// in seven-segment digits. Everything is plain DrawScope shapes; sizes are
// in u = 1/411 of the width, so it scales from 720 to 1080 px and beyond.
//
// Large areas use opaque colours pre-mixed over what lies beneath (the
// background is known): on the phone minui draws straight into uncached
// framebuffer memory, where every translucent pixel costs a slow read.
// Translucency is kept for small things only (stars, bubbles, sparks).

internal val Background = Color(0xff05070d)
private val OrbBackground = Color(0xff0b1120)
private val Muted = Color(0xff7d8799)
private val Red = Color(0xffff6b6b)

/** Level colour: red, orange, amber, aqua, green. */
internal fun levelColor(level: Float): Color {
    val stops = listOf(0f to 0xffff3b5c, 0.2f to 0xffff8a3d, 0.5f to 0xffffd23f, 0.8f to 0xff2ee6c9, 1f to 0xff3dff8f)
    val l = level.coerceIn(0f, 1f)
    val i = stops.indexOfLast { it.first <= l }.coerceAtMost(stops.size - 2)
    val (p0, c0) = stops[i]
    val (p1, c1) = stops[i + 1]
    return lerp(Color(c0), Color(c1), (l - p0) / (p1 - p0))
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
    return Color((0xff shl 24) or (ch(a.red, b.red) shl 16) or (ch(a.green, b.green) shl 8) or ch(a.blue, b.blue))
}

private fun Color.a(alpha: Float) = copy(alpha = alpha.coerceIn(0f, 1f))

/** Stable pseudo-random 0..1 per (i, k). */
private fun rnd(i: Int, k: Int): Float {
    val v = sin(i * 12.9898f + k * 78.233f) * 43758.547f
    return v - kotlin.math.floor(v)
}

internal fun DrawScope.drawCharger(t: Float, level: Float, s: BatteryState, powerOffIn: Int?, simulated: Boolean) {
    val w = size.width
    val h = size.height
    val u = w / 411f
    val color = levelColor(level)
    val charging = s.status == Status.Charging
    val full = s.status == Status.Full

    stars(t, u)   // the runtime has cleared the screen to Background

    val c = Offset(w / 2, h * 0.40f)
    val ringR = min(150 * u, h * 0.25f)
    val orbR = ringR - 24 * u

    // Halo
    val breathe = 0.5f + 0.5f * sin(t * 2.2f)
    val halo1 = lerp(Background, color, 0.035f + 0.02f * breathe)
    val halo2 = lerp(Background, color, 0.085f + 0.05f * breathe)
    drawCircle(halo1, ringR + 80 * u, c)
    drawCircle(halo2, ringR + 42 * u, c)

    // Ring: track, glow, progress, spark
    val box = Offset(c.x - ringR, c.y - ringR)
    val boxSize = Size(2 * ringR, 2 * ringR)
    drawCircle(lerp(halo2, Color.White, 0.07f), ringR, c, Stroke(10 * u))
    val sweep = 360f * level.coerceIn(0.002f, 1f)
    drawArc(lerp(halo2, color, 0.12f), -90f, sweep, false, Offset(box.x - 9 * u, box.y - 9 * u), Size(boxSize.width + 18 * u, boxSize.height + 18 * u), Stroke(28 * u))
    drawArc(lerp(halo2, color, 0.32f), -90f, sweep, false, Offset(box.x - 4 * u, box.y - 4 * u), Size(boxSize.width + 8 * u, boxSize.height + 8 * u), Stroke(18 * u))
    drawArc(color, -90f, sweep, false, box, boxSize, Stroke(10 * u))
    val head = -90f + sweep
    for (j in 7 downTo 1) {
        val p = polar(c, ringR - 5 * u, head - j * 2.6f)
        drawCircle(Color.White.a(0.10f * (8 - j) / 8f), (7 - j * 0.6f) * u, p)
    }
    val hp = polar(c, ringR - 5 * u, head)
    drawCircle(color.a(0.35f + 0.25f * breathe), 16 * u, hp)
    drawCircle(Color.White, 6 * u, hp)

    // Comet orbiting the ring while charging
    if (charging) {
        val a0 = (t * 150f) % 360f - 90f
        for (j in 0 until 14) {
            val p = polar(c, ringR + 22 * u, a0 - j * 3.2f)
            drawCircle(color.a(0.55f * (1 - j / 14f)), (4.5f - j * 0.25f) * u, p)
        }
    }

    // Orb: glass, liquid (back wave, front wave), bubbles, rim
    drawCircle(OrbBackground, orbR, c)
    val surface = c.y + orbR - 2 * orbR * level
    val calm = if (full) 0.35f else 1f
    liquid(c, orbR, surface + 3 * u, 8 * u * calm, 1.7f, t * 1.6f + 1.3f, lerp(color, Background, 0.55f))
    liquid(c, orbR, surface, 10 * u * calm, 1.3f, -t * 2.1f, color)
    bubbles(t, c, orbR, surface, u)
    drawArc(Color.White.a(0.18f), 200f, 70f, false, Offset(c.x - orbR + 8 * u, c.y - orbR + 8 * u),
        Size(2 * orbR - 16 * u, 2 * orbR - 16 * u), Stroke(4 * u))           // glass highlight
    drawCircle(Color.White.a(0.12f), orbR, c, Stroke(2 * u))

    // Level, seven-segment
    percent(level, c, orbR, u, surface)

    // Bolt with energy rising into the orb
    val boltC = Offset(c.x, c.y + ringR + 78 * u)
    if (charging) {
        for (i in 0 until 7) {
            val f = ((t * 0.9f + i / 7f) % 1f)
            val y = boltC.y - 30 * u - f * (boltC.y - 30 * u - (c.y + ringR + 14 * u))
            val x = boltC.x + sin(t * 3f + i * 1.7f) * 9 * u * (1 - f)
            drawCircle(color.a(0.8f * (1 - f)), (3.5f - 2 * f) * u, Offset(x, y))
        }
    }
    val pulse = if (charging) 0.5f + 0.5f * sin(t * 5f) else 0.3f
    drawCircle(lerp(Background, color, 0.10f + 0.14f * pulse), (30 + 6 * pulse) * u, boltC)
    drawPolygon(if (s.plugged) color else Muted, bolt(boltC, 26 * u))

    // Status and details
    val title = when {
        powerOffIn != null -> "UNPLUGGED"
        full -> "FULLY CHARGED"
        (s.currentMa ?: 0) >= 1500 -> "FAST CHARGING"
        else -> "CHARGING"
    }
    centered(title, boltC.y + 58 * u, if (powerOffIn != null) Red else color, 2)
    val details = listOfNotNull(
        s.currentMa?.takeIf { charging && it > 0 }?.let { "${it / 1000}.${(it % 1000) / 10}".padEnd(4, '0') + " A" },
        s.voltageMv?.let { "${it / 1000}.${((it % 1000) / 10).toString().padStart(2, '0')} V" },
        s.tempC?.let { "${(it * 10).roundToInt() / 10f} C" },
    ).joinToString("   ")
    if (powerOffIn != null) {
        centered("Powering off in $powerOffIn s", boltC.y + 58 * u + 2.6f * lineHeight(), Red, 1)
    } else if (details.isNotEmpty()) {
        centered(details, boltC.y + 58 * u + 2.6f * lineHeight(), Muted, 1)
    }
    centered("Hold POWER for 3 s to start", h - 70 * u, lerp(Background, Muted, 0.8f), 1)
    if (simulated) centered("simulated battery: UP/DOWN level, ENTER plug", h - 70 * u + 1.6f * lineHeight(), lerp(Background, Muted, 0.5f), 1)
}

private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset(c.x + cos(a) * r, c.y + sin(a) * r)
}

private fun DrawScope.lineHeight() = measureText("X").height

private fun DrawScope.centered(text: String, y: Float, color: Color, scale: Int) {
    val m = measureText(text, scale)
    drawText(text, Offset((size.width - m.width) / 2, y), color, scale)
}

/** Twinkling stars drifting up. */
private fun DrawScope.stars(t: Float, u: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 46) {
        val speed = 6 + rnd(i, 3) * 14
        val y = ((rnd(i, 2) * h - t * speed * u) % h + h) % h
        val tw = 0.5f + 0.5f * sin(t * (1.2f + rnd(i, 5) * 2.5f) + i)
        drawCircle(Color.White.a(0.08f + 0.30f * tw * rnd(i, 4)), (0.8f + rnd(i, 6) * 1.6f) * u, Offset(rnd(i, 1) * w, y))
    }
}

/**
 * Liquid inside the orb: the region below a sine wave, cut to the circle.
 * Upper edge left to right along max(wave, circle top), lower edge back
 * along the circle bottom.
 */
private fun DrawScope.liquid(c: Offset, r: Float, surface: Float, amp: Float, waves: Float, phase: Float, color: Color) {
    if (surface >= c.y + r) return
    val n = 56
    val top = ArrayList<Offset>(n + 1)
    val bottom = ArrayList<Offset>(n + 1)
    for (i in 0..n) {
        val x = c.x - r + 2 * r * i / n
        val half = sqrt(max(0f, r * r - (x - c.x) * (x - c.x)))
        val wave = surface + amp * sin(phase + waves * PI.toFloat() * 2 * i / n)
        val yb = c.y + half
        top.add(Offset(x, min(max(wave, c.y - half), yb)))
        bottom.add(Offset(x, yb))
    }
    drawPolygon(color, top + bottom.asReversed())
}

private fun DrawScope.bubbles(t: Float, c: Offset, r: Float, surface: Float, u: Float) {
    for (i in 0 until 14) {
        val period = 2.4f + rnd(i, 7) * 2.6f
        val f = (t / period + rnd(i, 8)) % 1f
        val y = c.y + r - f * 2 * r
        val x = c.x + (rnd(i, 9) - 0.5f) * 1.3f * r + sin(t * 2.6f + i) * 5 * u
        val br = (2f + rnd(i, 10) * 4.5f) * (0.6f + 0.4f * f) * u
        val dx = x - c.x
        val dy = y - c.y
        if (y < surface + 8 * u || dx * dx + dy * dy > (r - br - 3 * u) * (r - br - 3 * u)) continue
        drawCircle(Color.White.a(0.30f), br, Offset(x, y), Stroke(1.5f * u))
        drawCircle(Color.White.a(0.10f), br, Offset(x, y))
    }
}

private fun bolt(c: Offset, s: Float) = listOf(
    Offset(0.15f, -1f), Offset(-0.55f, 0.12f), Offset(-0.05f, 0.12f),
    Offset(-0.2f, 1f), Offset(0.55f, -0.15f), Offset(0.05f, -0.15f),
).map { Offset(c.x + it.x * s, c.y + it.y * s) }

// ------------------------------------------------------------ seven segments

private val SEGMENTS = intArrayOf(0x7e, 0x30, 0x6d, 0x79, 0x33, 0x5b, 0x5f, 0x70, 0x7f, 0x7b)  // a..g = bits 6..0

/** Digits are white above the liquid and dark where it is behind them. */
private fun DrawScope.percent(level: Float, c: Offset, r: Float, u: Float, surface: Float) {
    val text = (level * 100).roundToInt().coerceIn(0, 100).toString()
    val dh = r * 0.62f
    val dw = dh * 0.52f
    val gap = dw * 0.30f
    val th = dh * 0.12f
    val pw = dw * 0.72f
    val total = text.length * dw + (text.length - 1) * gap + gap * 1.2f + pw
    var x = c.x - total / 2
    val y = c.y - dh / 2 - r * 0.04f
    for (ch in text) {
        digit(ch - '0', x, y, dw, dh, th, surface)
        x += dw + gap
    }
    x += gap * 0.2f
    // %: two rings and a slash
    val pr = pw * 0.2f
    val pcol = if (y + dh / 2 > surface) OrbBackground else Color.White
    drawLine(pcol, Offset(x + pw * 0.95f, y + dh * 0.12f), Offset(x + pw * 0.05f, y + dh * 0.88f), th * 0.55f)
    drawCircle(pcol, pr, Offset(x + pr + th * 0.2f, y + dh * 0.22f), Stroke(th * 0.5f))
    drawCircle(pcol, pr, Offset(x + pw - pr - th * 0.2f, y + dh * 0.78f), Stroke(th * 0.5f))
}

private fun DrawScope.digit(d: Int, x: Float, y: Float, w: Float, h: Float, th: Float, surface: Float) {
    val segs = arrayOf(
        floatArrayOf(x + th * 0.6f, y, w - th * 1.2f, th),                          // a
        floatArrayOf(x + w - th, y + th * 0.6f, th, h / 2 - th * 0.9f),             // b
        floatArrayOf(x + w - th, y + h / 2 + th * 0.3f, th, h / 2 - th * 0.9f),     // c
        floatArrayOf(x + th * 0.6f, y + h - th, w - th * 1.2f, th),                 // d
        floatArrayOf(x, y + h / 2 + th * 0.3f, th, h / 2 - th * 0.9f),              // e
        floatArrayOf(x, y + th * 0.6f, th, h / 2 - th * 0.9f),                      // f
        floatArrayOf(x + th * 0.6f, y + h / 2 - th / 2, w - th * 1.2f, th),         // g
    )
    val on = SEGMENTS[d]
    val ghost = lerp(OrbBackground, Color.White, 0.06f)
    for (i in 0 until 7) {
        val s = segs[i]
        val submerged = s[1] + s[3] / 2 > surface
        if (on shr (6 - i) and 1 == 0) {
            if (!submerged) drawRoundRect(ghost, Offset(s[0], s[1]), Size(s[2], s[3]), th / 2)
            continue
        }
        drawRoundRect(if (submerged) OrbBackground else Color.White, Offset(s[0], s[1]), Size(s[2], s[3]), th / 2)
    }
}
