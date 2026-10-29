package ui.material

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ui.Canvas
import ui.Color
import ui.Dp
import ui.Modifier
import ui.Offset
import ui.dp
import ui.size
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The icons drawn here, by their Material Symbols names. */
val MATERIAL_ICONS = setOf(
    "check", "close", "add", "remove", "star", "favorite", "info", "warning", "error", "home", "settings",
    "search", "mail", "event", "person", "place", "shopping_cart", "schedule", "play_arrow", "pause",
    "sunny", "cloud", "rainy", "mic", "image", "arrow_back", "arrow_forward", "stop", "auto_awesome",
)

/** A Material icon, [size] square, in [color]; a name not drawn here shows as a ring with its first letter. */
@Composable
fun Icon(name: String, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = LocalContentColor.current) {
    val px = size.toPx()
    val mask = remember(name, px) { if (name in MATERIAL_ICONS) iconMask(name, px) else null }
    Canvas(modifier.size(size)) {
        if (mask != null) {
            drawMask(mask, px, px, Offset.Zero, color)
        } else {
            circle(color, px / 2f, px / 2f, px * 10f / 24, px * 2f / 24)
            val letter = name.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?"
            val style = TextStyle(size.value * 0.5f, size.value)
            drawText(letter, (px - measureWidth(letter, style)) / 2, 0f, style, color)
        }
    }
}

/** Draws icon [name] on a 24-unit grid, as Material's icons are. */
private fun iconMask(name: String, px: Int): ByteArray {
    val u = px / 24f
    val v = Vector(px, px)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 2f, round: Boolean = false) = v.line(x1 * u, y1 * u, x2 * u, y2 * u, w * u, round = round)
    fun ring(x: Float, y: Float, r: Float, w: Float = 2f) = v.ring(x * u, y * u, r * u, w * u)
    fun dot(x: Float, y: Float, r: Float, cut: Boolean = false) = v.circle(x * u, y * u, r * u, cut)
    fun poly(vararg xy: Float, cut: Boolean = false) = v.polygon(*FloatArray(xy.size) { xy[it] * u }, cut = cut)
    fun rect(l: Float, t: Float, w: Float, h: Float, cut: Boolean = false) = v.rect(l * u, t * u, w * u, h * u, cut)
    when (name) {
        "check" -> { line(4.5f, 12.5f, 9.3f, 17.3f, 2.1f); line(8.6f, 17.3f, 19.8f, 6.1f, 2.1f) }
        "close" -> { line(5.5f, 5.5f, 18.5f, 18.5f); line(18.5f, 5.5f, 5.5f, 18.5f) }
        "add" -> { rect(11f, 5f, 2f, 14f); rect(5f, 11f, 14f, 2f) }
        "remove" -> rect(5f, 11f, 14f, 2f)
        "star" -> {
            val pts = FloatArray(20) { k ->
                val p = k / 2
                val r = if (p % 2 == 0) 10f else 4.3f
                val a = -PI / 2 + p * PI / 5
                if (k % 2 == 0) 12f + r * cos(a).toFloat() else 12.6f + r * sin(a).toFloat()
            }
            poly(*pts)
        }
        "favorite" -> { dot(7.9f, 8.9f, 4.6f); dot(16.1f, 8.9f, 4.6f); poly(3.6f, 10.6f, 20.4f, 10.6f, 12f, 20.6f) }
        "info" -> { ring(12f, 12f, 9f); rect(11f, 10.5f, 2f, 6.5f); dot(12f, 7.8f, 1.2f) }
        "error" -> { dot(12f, 12f, 10f); rect(11f, 6.5f, 2f, 7f, cut = true); rect(11f, 15f, 2f, 2f, cut = true) }
        "warning" -> { poly(12f, 2.5f, 23f, 21f, 1f, 21f); rect(11f, 9f, 2f, 5.5f, cut = true); rect(11f, 16.2f, 2f, 2f, cut = true) }
        "home" -> poly(10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f, 19f, 20f, 19f, 12f, 22f, 12f, 12f, 3f, 2f, 12f, 5f, 12f, 5f, 20f)
        "settings" -> {
            for (k in 0 until 8) {
                val a = k * PI / 4
                v.circle((12f + 8.3f * cos(a).toFloat()) * u, (12f + 8.3f * sin(a).toFloat()) * u, 2.3f * u)
            }
            dot(12f, 12f, 7.6f); dot(12f, 12f, 3.2f, cut = true)
        }
        "search" -> { ring(9.5f, 9.5f, 5.5f, 2f); line(13.6f, 13.6f, 20f, 20f, 2.2f) }
        "mail" -> {
            v.roundRectStroke(2.5f * u, 4.5f * u, 19f * u, 15f * u, 2f * u, 2f * u)
            line(4.5f, 7.5f, 12f, 12.5f, 2f, true); line(12f, 12.5f, 19.5f, 7.5f, 2f, true)
        }
        "event" -> {
            v.roundRectStroke(3f * u, 4.5f * u, 18f * u, 16.5f * u, 2f * u, 2f * u)
            rect(3f, 4.5f, 18f, 5f); rect(7f, 2f, 2f, 4f); rect(15f, 2f, 2f, 4f); rect(13f, 13f, 5f, 5f)
        }
        "person" -> { dot(12f, 8f, 4f); v.roundRect(4f * u, 14f * u, 16f * u, 6f * u, 3f * u, 3f * u, 0f, 0f) }
        "place" -> { dot(12f, 9f, 7f); poly(5.6f, 11.8f, 18.4f, 11.8f, 12f, 22f); dot(12f, 9f, 2.5f, cut = true) }
        "shopping_cart" -> {
            line(1.5f, 3f, 4.5f, 3f, 2f); line(4.5f, 3f, 7.2f, 14.5f, 2f)
            poly(6f, 5f, 21.5f, 5f, 18.8f, 12.5f, 7.6f, 12.5f); line(7f, 15.5f, 18.5f, 15.5f, 2f)
            dot(8f, 19.5f, 2f); dot(17f, 19.5f, 2f)
        }
        "schedule" -> { ring(12f, 12f, 9f); rect(11f, 6.5f, 2f, 6f); line(12f, 12f, 15.6f, 14.6f, 2f) }
        "play_arrow" -> poly(8f, 5f, 19f, 12f, 8f, 19f)
        "pause" -> { rect(6f, 5f, 4f, 14f); rect(14f, 5f, 4f, 14f) }
        "stop" -> v.roundRect(6f * u, 6f * u, 12f * u, 12f * u, 1.5f * u)
        "sunny" -> {
            dot(12f, 12f, 4.5f)
            for (k in 0 until 8) {
                val a = k * PI / 4
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                line(12f + 7.2f * c, 12f + 7.2f * s, 12f + 10f * c, 12f + 10f * s, 2f, true)
            }
        }
        "cloud" -> cloud(v, u, 0f)
        "rainy" -> { cloud(v, u, -3f); for (x in listOf(8f, 12f, 16f)) line(x, 17.5f, x - 1.2f, 21f, 1.8f, true) }
        "mic" -> {
            v.roundRect(9f * u, 2f * u, 6f * u, 12f * u, 3f * u)
            v.arc(12f * u, 11f * u, 6.2f * u, 1.8f * u, 0f, 180f)
            rect(11f, 17f, 2f, 4.5f)
        }
        "image" -> {
            v.roundRectStroke(3f * u, 3f * u, 18f * u, 18f * u, 2f * u, 2f * u)
            poly(6f, 17.5f, 9.5f, 12.5f, 12f, 15.5f, 15f, 11.5f, 18f, 17.5f)
        }
        "arrow_back" -> { rect(5.5f, 11f, 14.5f, 2f); line(11.6f, 5.4f, 5.6f, 12f, 2f); line(5.6f, 12f, 11.6f, 18.6f, 2f) }
        "arrow_forward" -> { rect(4f, 11f, 14.5f, 2f); line(12.4f, 5.4f, 18.4f, 12f, 2f); line(18.4f, 12f, 12.4f, 18.6f, 2f) }
        "auto_awesome" -> {
            sparkle(v, u, 10f, 13f, 7.5f)
            sparkle(v, u, 18.5f, 5.5f, 3.5f)
            sparkle(v, u, 18.5f, 18.5f, 3f)
        }
    }
    return v.mask()
}

/** A four-pointed star with concave sides (Material's sparkle). */
private fun sparkle(v: Vector, u: Float, cx: Float, cy: Float, r: Float) {
    val pts = FloatArray(32) { k ->
        val p = k / 2
        val a = p * PI / 8
        // points at 0, 90, 180, 270 degrees; pinched in between
        val f = kotlin.math.abs(cos(2 * a)).toFloat()
        val rr = r * (0.28f + 0.72f * f * f * f)
        if (k % 2 == 0) (cx + rr * cos(a).toFloat()) * u else (cy + rr * sin(a).toFloat()) * u
    }
    v.polygon(*pts)
}

private fun cloud(v: Vector, u: Float, dy: Float) {
    v.circle(7.5f * u, (14f + dy) * u, 4.2f * u)
    v.circle(12.5f * u, (11.5f + dy) * u, 5.6f * u)
    v.circle(17.5f * u, (14.5f + dy) * u, 3.8f * u)
    v.rect(7.5f * u, (14f + dy) * u, 10f * u, 4.3f * u)
}
