package ui

import kotlin.math.roundToInt

/** 0xAARRGGBB, like androidx.compose.ui.graphics.Color. */
value class Color(val argb: Int) {
    val alpha get() = (argb ushr 24) and 0xff
    val red get() = (argb shr 16) and 0xff
    val green get() = (argb shr 8) and 0xff
    val blue get() = argb and 0xff

    fun copy(alpha: Float): Color = Color(((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (argb and 0xffffff))

    companion object {
        val Black = Color(0xff000000.toInt())
        val White = Color(0xffffffff.toInt())
        val Gray = Color(0xff888888.toInt())
        val Red = Color(0xfff44336.toInt())
        val Green = Color(0xff4caf50.toInt())
        val Blue = Color(0xff2196f3.toInt())
        val Yellow = Color(0xffffeb3b.toInt())
        val Transparent = Color(0)
    }
}

fun Color(rgb: Long): Color = Color(rgb.toInt())

/** Density-independent pixels: px = dp x [Density.scale], which the board sets (EC_DENSITY). */
value class Dp(val value: Float) {
    fun toPx(): Int = (value * Density.scale).roundToInt()
    operator fun plus(o: Dp) = Dp(value + o.value)
    operator fun times(f: Float) = Dp(value * f)
}

val Int.dp: Dp get() = Dp(toFloat())
val Float.dp: Dp get() = Dp(this)

object Density {
    /** Pixels per dp, set at start-up from ec_density(). */
    var scale = 2.625f
}

data class IntSize(val width: Int, val height: Int)

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}

const val Infinity = Int.MAX_VALUE

data class Constraints(val minWidth: Int = 0, val maxWidth: Int = Infinity, val minHeight: Int = 0, val maxHeight: Int = Infinity) {
    val hasBoundedWidth get() = maxWidth != Infinity
    val hasBoundedHeight get() = maxHeight != Infinity

    fun constrainWidth(w: Int) = w.coerceIn(minWidth, maxWidth)
    fun constrainHeight(h: Int) = h.coerceIn(minHeight, maxHeight)
    fun constrain(w: Int, h: Int) = IntSize(constrainWidth(w), constrainHeight(h))

    /** Shrinks the box by [h] x [v] pixels (padding). */
    fun offset(h: Int, v: Int) = Constraints(
        (minWidth - h).coerceAtLeast(0), if (maxWidth == Infinity) Infinity else (maxWidth - h).coerceAtLeast(0),
        (minHeight - v).coerceAtLeast(0), if (maxHeight == Infinity) Infinity else (maxHeight - v).coerceAtLeast(0),
    )

    fun loosen() = copy(minWidth = 0, minHeight = 0)

    /** Intersects with a requested range, keeping it inside this one (like Compose's SizeModifier). */
    fun constrainRange(minW: Int, maxW: Int, minH: Int, maxH: Int) = Constraints(
        minW.coerceIn(minWidth, maxWidth), maxW.coerceIn(minWidth, maxWidth).coerceAtLeast(minW.coerceIn(minWidth, maxWidth)),
        minH.coerceIn(minHeight, maxHeight), maxH.coerceIn(minHeight, maxHeight).coerceAtLeast(minH.coerceIn(minHeight, maxHeight)),
    )

    companion object {
        fun fixed(w: Int, h: Int) = Constraints(w, w, h, h)
    }
}

/** Where a child goes inside a bigger box. -1 = start, 0 = center, 1 = end. */
data class Alignment(val horizontal: Int, val vertical: Int) {
    fun align(size: IntSize, space: IntSize): Pair<Int, Int> =
        alignAxis(horizontal, size.width, space.width) to alignAxis(vertical, size.height, space.height)

    companion object {
        val TopStart = Alignment(-1, -1)
        val TopCenter = Alignment(0, -1)
        val TopEnd = Alignment(1, -1)
        val CenterStart = Alignment(-1, 0)
        val Center = Alignment(0, 0)
        val CenterEnd = Alignment(1, 0)
        val BottomStart = Alignment(-1, 1)
        val BottomCenter = Alignment(0, 1)
        val BottomEnd = Alignment(1, 1)

        val Start = Horizontal(-1)
        val CenterHorizontally = Horizontal(0)
        val End = Horizontal(1)
        val Top = Vertical(-1)
        val CenterVertically = Vertical(0)
        val Bottom = Vertical(1)
    }

    value class Horizontal(val bias: Int)
    value class Vertical(val bias: Int)
}

internal fun alignAxis(bias: Int, size: Int, space: Int) = when (bias) {
    -1 -> 0
    0 -> (space - size) / 2
    else -> space - size
}
