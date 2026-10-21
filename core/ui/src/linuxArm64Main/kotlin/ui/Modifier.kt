package ui

/**
 * A much smaller Modifier than androidx.compose.ui's, with the same shape:
 * an ordered chain of elements, applied outside-in.
 */
interface Modifier {
    val elements: List<Element>

    infix fun then(other: Modifier): Modifier =
        if (other.elements.isEmpty()) this else Chain(elements + other.elements)

    interface Element : Modifier {
        override val elements: List<Element> get() = listOf(this)
    }

    companion object : Modifier {
        override val elements: List<Element> get() = emptyList()
        override fun then(other: Modifier) = other
    }

    private class Chain(override val elements: List<Element>) : Modifier
}

// ---- layout elements ----

internal class PaddingElement(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp) : Modifier.Element
internal class SizeElement(val minW: Dp?, val maxW: Dp?, val minH: Dp?, val maxH: Dp?) : Modifier.Element
internal class FillElement(val widthFraction: Float?, val heightFraction: Float?) : Modifier.Element
internal class OffsetElement(val x: () -> Dp, val y: () -> Dp) : Modifier.Element

/** Parent data for Row/Column. */
internal class WeightElement(val weight: Float) : Modifier.Element

// ---- draw elements ----

internal class BackgroundElement(val color: Color, val shape: Shape) : Modifier.Element
internal class BorderElement(val width: Dp, val color: Color, val shape: Shape) : Modifier.Element
internal class DrawBehindElement(val onDraw: DrawScope.() -> Unit) : Modifier.Element

// ---- input elements ----

internal class ClickableElement(val enabled: Boolean, val interaction: Interaction?, val onClick: () -> Unit) : Modifier.Element
internal class PointerElement(val onPointer: (PointerEvent) -> Unit) : Modifier.Element

// ---- testing ----

internal class TestTagElement(val tag: String) : Modifier.Element

fun Modifier.padding(all: Dp) = then(PaddingElement(all, all, all, all))
fun Modifier.padding(horizontal: Dp = 0.dp, vertical: Dp = 0.dp) = then(PaddingElement(horizontal, vertical, horizontal, vertical))
fun Modifier.padding(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp) = then(PaddingElement(start, top, end, bottom))

fun Modifier.size(size: Dp) = then(SizeElement(size, size, size, size))
fun Modifier.size(width: Dp, height: Dp) = then(SizeElement(width, width, height, height))
fun Modifier.width(width: Dp) = then(SizeElement(width, width, null, null))
fun Modifier.height(height: Dp) = then(SizeElement(null, null, height, height))
fun Modifier.heightIn(min: Dp? = null, max: Dp? = null) = then(SizeElement(null, null, min, max))
fun Modifier.widthIn(min: Dp? = null, max: Dp? = null) = then(SizeElement(min, max, null, null))

fun Modifier.fillMaxWidth(fraction: Float = 1f) = then(FillElement(fraction, null))
fun Modifier.fillMaxHeight(fraction: Float = 1f) = then(FillElement(null, fraction))
fun Modifier.fillMaxSize(fraction: Float = 1f) = then(FillElement(fraction, fraction))

fun Modifier.offset(x: Dp = 0.dp, y: Dp = 0.dp) = then(OffsetElement({ x }, { y }))
/** Offset read at layout time, so animating it re-lays-out without recomposing. */
fun Modifier.offset(x: () -> Dp, y: () -> Dp) = then(OffsetElement(x, y))

fun Modifier.weight(weight: Float) = then(WeightElement(weight))

fun Modifier.background(color: Color, shape: Shape = RectangleShape) = then(BackgroundElement(color, shape))
fun Modifier.border(width: Dp, color: Color, shape: Shape = RectangleShape) = then(BorderElement(width, color, shape))
fun Modifier.drawBehind(onDraw: DrawScope.() -> Unit) = then(DrawBehindElement(onDraw))

fun Modifier.clickable(enabled: Boolean = true, interaction: Interaction? = null, onClick: () -> Unit) =
    then(ClickableElement(enabled, interaction, onClick))

/** Raw pointer events for presses that start inside this element; the press is captured until release. */
fun Modifier.pointerInput(onPointer: (PointerEvent) -> Unit) = then(PointerElement(onPointer))

/** Names this element for UI tests: with tracing on, its screen bounds are logged whenever they change. */
fun Modifier.testTag(tag: String) = then(TestTagElement(tag))
