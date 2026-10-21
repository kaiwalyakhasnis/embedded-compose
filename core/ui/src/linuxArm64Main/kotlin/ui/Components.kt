package ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.remember
import kotlin.math.max

/** Every composable here is one LayoutNode with a different measure policy. */
@Composable
fun Layout(
    modifier: Modifier = Modifier,
    measurePolicy: MeasurePolicy,
    drawContent: (DrawScope.() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    ComposeNode<LayoutNode, NodeApplier>(
        factory = ::LayoutNode,
        update = {
            set(modifier) { this.modifier = it }
            set(measurePolicy) { this.measurePolicy = it }
            set(drawContent) { this.drawContent = it }
        },
        content = content,
    )
}

// -------------------------------------------------------------------- Box

internal class BoxPolicy(private val alignment: Alignment) : MeasurePolicy {
    override fun measure(children: List<LayoutNode>, constraints: Constraints): MeasureResult {
        val loose = constraints.loosen()
        var w = constraints.minWidth
        var h = constraints.minHeight
        for (c in children) {
            val s = c.measure(loose)
            w = max(w, s.width)
            h = max(h, s.height)
        }
        w = constraints.constrainWidth(w)
        h = constraints.constrainHeight(h)
        return MeasureResult(w, h) {
            for (c in children) {
                val (dx, dy) = alignment.align(IntSize(c.width, c.height), IntSize(w, h))
                c.placeAt(dx, dy)
            }
        }
    }

    override fun equals(other: Any?) = other is BoxPolicy && other.alignment == alignment
    override fun hashCode() = alignment.hashCode()
}

@Composable
fun Box(modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.TopStart, content: @Composable () -> Unit = {}) {
    val policy = remember(contentAlignment) { BoxPolicy(contentAlignment) }
    Layout(modifier, policy, content = content)
}

// ------------------------------------------------------------- Row / Column

sealed class Arrangement {
    /** Returns each child's offset along the main axis. */
    internal abstract fun arrange(total: Int, sizes: IntArray): IntArray

    data object Start : Arrangement() {
        override fun arrange(total: Int, sizes: IntArray) = packed(sizes, 0, 0)
    }
    data object End : Arrangement() {
        override fun arrange(total: Int, sizes: IntArray) = packed(sizes, total - sizes.sum(), 0)
    }
    data object Center : Arrangement() {
        override fun arrange(total: Int, sizes: IntArray) = packed(sizes, (total - sizes.sum()) / 2, 0)
    }
    data object SpaceBetween : Arrangement() {
        override fun arrange(total: Int, sizes: IntArray) =
            packed(sizes, 0, if (sizes.size > 1) (total - sizes.sum()) / (sizes.size - 1) else 0)
    }
    data object SpaceEvenly : Arrangement() {
        override fun arrange(total: Int, sizes: IntArray): IntArray {
            val gap = (total - sizes.sum()) / (sizes.size + 1)
            return packed(sizes, gap, gap)
        }
    }
    data class SpacedBy(val space: Dp) : Arrangement() {
        override fun arrange(total: Int, sizes: IntArray) = packed(sizes, 0, space.toPx())
    }

    companion object {
        val Top = Start
        val Bottom = End
        fun spacedBy(space: Dp): Arrangement = SpacedBy(space)

        private fun packed(sizes: IntArray, start: Int, gap: Int): IntArray {
            var pos = start
            return IntArray(sizes.size) { i -> pos.also { pos += sizes[i] + gap } }
        }
    }
}

/** Row and Column: the same linear layout along x or y, with weights (like Compose's RowColumnMeasurePolicy). */
internal class LinearPolicy(
    private val vertical: Boolean,
    private val arrangement: Arrangement,
    private val crossBias: Int,
) : MeasurePolicy {
    override fun measure(children: List<LayoutNode>, constraints: Constraints): MeasureResult {
        val mainMax = if (vertical) constraints.maxHeight else constraints.maxWidth
        val mainMin = if (vertical) constraints.minHeight else constraints.minWidth
        val crossMax = if (vertical) constraints.maxWidth else constraints.maxHeight
        val crossMin = if (vertical) constraints.minWidth else constraints.minHeight
        val gap = (arrangement as? Arrangement.SpacedBy)?.space?.toPx() ?: 0
        val totalGap = gap * (children.size - 1).coerceAtLeast(0)

        fun childConstraints(mainMinC: Int, mainMaxC: Int) =
            if (vertical) Constraints(0, crossMax, mainMinC, mainMaxC) else Constraints(mainMinC, mainMaxC, 0, crossMax)

        val mainSizes = IntArray(children.size)
        var used = totalGap
        var cross = crossMin
        var totalWeight = 0f
        for ((i, c) in children.withIndex()) {
            val w = c.weight()
            if (w > 0f) { totalWeight += w; continue }
            val remaining = if (mainMax == Infinity) Infinity else (mainMax - used).coerceAtLeast(0)
            val s = c.measure(childConstraints(0, remaining))
            mainSizes[i] = if (vertical) s.height else s.width
            used += mainSizes[i]
            cross = max(cross, if (vertical) s.width else s.height)
        }
        if (totalWeight > 0f && mainMax != Infinity) {
            val space = (mainMax - used).coerceAtLeast(0)
            var given = 0
            var weightLeft = totalWeight
            for ((i, c) in children.withIndex()) {
                val w = c.weight()
                if (w <= 0f) continue
                // Last weighted child takes the rounding remainder.
                val share = if (weightLeft - w <= 0f) space - given else (space * w / totalWeight).toInt()
                given += share
                weightLeft -= w
                val s = c.measure(childConstraints(share, share))
                mainSizes[i] = share
                cross = max(cross, if (vertical) s.width else s.height)
            }
            used += space
        }
        val main = if (totalWeight > 0f && mainMax != Infinity) mainMax else used.coerceIn(mainMin, mainMax)
        cross = cross.coerceIn(crossMin, crossMax)
        val w = if (vertical) cross else main
        val h = if (vertical) main else cross
        return MeasureResult(w, h) {
            val offsets = arrangement.arrange(main, mainSizes)
            for ((i, c) in children.withIndex()) {
                val childCross = if (vertical) c.width else c.height
                val crossPos = alignAxis(crossBias, childCross, cross)
                if (vertical) c.placeAt(crossPos, offsets[i]) else c.placeAt(offsets[i], crossPos)
            }
        }
    }

    override fun equals(other: Any?) =
        other is LinearPolicy && other.vertical == vertical && other.arrangement == arrangement && other.crossBias == crossBias
    override fun hashCode() = arrangement.hashCode() * 31 + crossBias + if (vertical) 7 else 0
}

@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    val policy = remember(verticalArrangement, horizontalAlignment) { LinearPolicy(true, verticalArrangement, horizontalAlignment.bias) }
    Layout(modifier, policy, content = content)
}

@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable () -> Unit,
) {
    val policy = remember(horizontalArrangement, verticalAlignment) { LinearPolicy(false, horizontalArrangement, verticalAlignment.bias) }
    Layout(modifier, policy, content = content)
}

// ------------------------------------------------------------------ leaves

private val EmptyPolicy = MeasurePolicy { _, c -> MeasureResult(c.minWidth, c.minHeight) }

@Composable
fun Spacer(modifier: Modifier) = Layout(modifier, EmptyPolicy)

/** Draws with the display's bitmap font (minui: the board's font.png); [scale] multiplies its pixels. */
@Composable
fun Text(text: String, modifier: Modifier = Modifier, color: Color = Color.White, scale: Int = 1, bold: Boolean = false) {
    val policy = remember(text, scale) {
        MeasurePolicy { _, c ->
            val s = TextRenderer.measure(text, scale)
            MeasureResult(c.constrainWidth(s.width), c.constrainHeight(s.height))
        }
    }
    Layout(modifier, policy, drawContent = { drawText(text, Offset.Zero, color, scale, bold) })
}

/** Size it with a modifier; [onDraw] runs on every frame it is part of, reading state there re-draws without recomposing. */
@Composable
fun Canvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) = Layout(modifier, EmptyPolicy, drawContent = onDraw)

@Composable
fun Image(image: ImageBitmap, modifier: Modifier = Modifier) {
    val policy = remember(image) {
        MeasurePolicy { _, c -> MeasureResult(c.constrainWidth(image.width), c.constrainHeight(image.height)) }
    }
    Layout(modifier, policy, drawContent = {
        drawImage(image, Offset((size.width - image.width) / 2, (size.height - image.height) / 2))
    })
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color(0xff3949ab),
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { Interaction() }
    val bg = when {
        !enabled -> color.copy(alpha = 0.35f)
        interaction.pressed -> mix(color, Color.Black, 0.35f)
        else -> color
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(bg, shape)
            .clickable(enabled, interaction, onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

fun mix(a: Color, b: Color, t: Float): Color {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color((ch(a.alpha, b.alpha) shl 24) or (ch(a.red, b.red) shl 16) or (ch(a.green, b.green) shl 8) or ch(a.blue, b.blue))
}
