package ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Measures a node's children inside the node's content box; returns the content size and places the children. */
fun interface MeasurePolicy {
    fun measure(children: List<LayoutNode>, constraints: Constraints): MeasureResult
}

class MeasureResult(val width: Int, val height: Int, val place: Placement.() -> Unit = {})

/** Handed to [MeasureResult.place]: positions children inside the parent's content box. */
class Placement internal constructor(private val owner: LayoutNode) {
    fun LayoutNode.placeAt(dx: Int, dy: Int) = place(owner.contentBounds.left + dx, owner.contentBounds.top + dy)
}

/** Pressed/focused state of a clickable, readable from composition (like MutableInteractionSource). */
class Interaction {
    var pressed by mutableStateOf(false)
        internal set
    var focused by mutableStateOf(false)
        internal set
}

enum class PointerEventType { Press, Move, Release }

/** Position relative to the element that received it. */
class PointerEvent(val type: PointerEventType, val position: Offset)

/**
 * The one node type of the tree, like Compose UI's LayoutNode: a modifier
 * chain, a measure policy for its children, and optional content drawing.
 */
class LayoutNode {
    internal val children = mutableListOf<LayoutNode>()
    internal var parent: LayoutNode? = null

    var modifier: Modifier = Modifier
        set(value) { field = value; elements = value.elements }
    internal var elements: List<Modifier.Element> = emptyList()
    var measurePolicy: MeasurePolicy = BoxPolicy(Alignment.TopStart)
    var drawContent: (DrawScope.() -> Unit)? = null

    // Results of the last measure/place, in absolute screen pixels.
    internal var x = 0
    internal var y = 0
    var width = 0; internal set
    var height = 0; internal set
    // Per modifier element: its measured size and its placed bounds.
    private var elementSizes = Array(0) { IntSize(0, 0) }
    internal var elementBounds = Array(0) { IntRect(0, 0, 0, 0) }
    internal var contentBounds = IntRect(0, 0, 0, 0)
    private var contentResult: MeasureResult? = null

    // ---- measure ----

    fun measure(constraints: Constraints): IntSize {
        if (elementSizes.size != elements.size) {
            elementSizes = Array(elements.size) { IntSize(0, 0) }
            elementBounds = Array(elements.size) { IntRect(0, 0, 0, 0) }
        }
        val size = measureFrom(0, constraints)
        width = size.width
        height = size.height
        return size
    }

    private fun measureFrom(i: Int, c: Constraints): IntSize {
        if (i == elements.size) {
            val r = measurePolicy.measure(children, c)
            contentResult = r
            return c.constrain(r.width, r.height)
        }
        val size = when (val e = elements[i]) {
            is PaddingElement -> {
                val h = e.start.toPx() + e.end.toPx()
                val v = e.top.toPx() + e.bottom.toPx()
                val inner = measureFrom(i + 1, c.offset(h, v))
                c.constrain(inner.width + h, inner.height + v)
            }
            is SizeElement -> {
                val inner = c.constrainRange(
                    e.minW?.toPx() ?: c.minWidth, e.maxW?.toPx() ?: c.maxWidth,
                    e.minH?.toPx() ?: c.minHeight, e.maxH?.toPx() ?: c.maxHeight,
                )
                measureFrom(i + 1, inner)
            }
            is FillElement -> {
                var inner = c
                if (e.widthFraction != null && c.hasBoundedWidth) {
                    val w = c.constrainWidth((c.maxWidth * e.widthFraction).toInt())
                    inner = inner.copy(minWidth = w, maxWidth = w)
                }
                if (e.heightFraction != null && c.hasBoundedHeight) {
                    val h = c.constrainHeight((c.maxHeight * e.heightFraction).toInt())
                    inner = inner.copy(minHeight = h, maxHeight = h)
                }
                measureFrom(i + 1, inner)
            }
            else -> measureFrom(i + 1, c)
        }
        elementSizes[i] = size
        return size
    }

    // ---- place ----

    /** Places this node with its top-left at absolute ([px], [py]). */
    fun place(px: Int, py: Int) {
        x = px
        y = py
        var cx = px
        var cy = py
        for (i in elements.indices) {
            val s = elementSizes[i]
            elementBounds[i] = IntRect(cx, cy, cx + s.width, cy + s.height)
            when (val e = elements[i]) {
                is PaddingElement -> { cx += e.start.toPx(); cy += e.top.toPx() }
                is OffsetElement -> { cx += e.x().toPx(); cy += e.y().toPx() }
                else -> {}
            }
        }
        val r = contentResult ?: return
        contentBounds = IntRect(cx, cy, cx + r.width, cy + r.height)
        Placement(this).apply(r.place)
    }

    internal fun weight(): Float = elements.firstNotNullOfOrNull { (it as? WeightElement)?.weight } ?: 0f

    // ---- draw ----

    fun draw(focused: LayoutNode?) {
        for (i in elements.indices) {
            val b = elementBounds[i]
            when (val e = elements[i]) {
                is BackgroundElement -> scope(b).drawShape(e.shape, e.color)
                is DrawBehindElement -> scope(b).(e.onDraw)()
                else -> {}
            }
        }
        drawContent?.let { scope(contentBounds).it() }
        for (child in children) child.draw(focused)
        for (i in elements.indices.reversed()) {
            val e = elements[i]
            if (e is BorderElement) scope(elementBounds[i]).drawShape(e.shape, e.color, e.width.toPx().toFloat())
        }
        if (focused === this) {
            val i = elements.indexOfFirst { it is ClickableElement }
            if (i >= 0) {
                val shape = elements.firstNotNullOfOrNull { (it as? BackgroundElement)?.shape } ?: RectangleShape
                scope(elementBounds[i]).drawShape(shape, Theme.focus, 3.dp.toPx().toFloat())
            }
        }
    }

    private fun scope(b: IntRect) = DrawScope(b.left, b.top, Size(b.width.toFloat(), b.height.toFloat()))

    // ---- input ----

    internal val clickable get() = elements.firstNotNullOfOrNull { it as? ClickableElement }
    internal fun boundsOf(e: Modifier.Element) = elementBounds[elements.indexOf(e)]

    /** Topmost node (last drawn) at ([px], [py]) with an element matching [pick]. */
    internal fun <T : Modifier.Element> hitTest(px: Int, py: Int, pick: (Modifier.Element) -> T?): Pair<LayoutNode, T>? {
        for (child in children.asReversed()) child.hitTest(px, py, pick)?.let { return it }
        for (i in elements.indices) {
            val t = pick(elements[i]) ?: continue
            if (elementBounds[i].contains(px, py)) return this to t
        }
        return null
    }

    /** Clickable nodes in draw order, for key focus. */
    internal fun collectFocusable(out: MutableList<LayoutNode>) {
        if (clickable?.enabled == true) out.add(this)
        for (child in children) child.collectFocusable(out)
    }

    /** Screen bounds of every [testTag] in this subtree. */
    internal fun collectTestTags(out: MutableMap<String, IntRect>) {
        for (i in elements.indices) (elements[i] as? TestTagElement)?.let { out[it.tag] = elementBounds[i] }
        for (child in children) child.collectTestTags(out)
    }

    /** Still in the tree (a press or focus may outlive the node it started on). */
    internal fun isAttached(): Boolean {
        var n = this
        while (true) {
            if (n.isRoot) return true
            n = n.parent ?: return false
        }
    }

    internal var isRoot = false
}

class NodeApplier(root: LayoutNode, private val onChanged: () -> Unit) : AbstractApplier<LayoutNode>(root) {
    override fun insertTopDown(index: Int, instance: LayoutNode) {}

    override fun insertBottomUp(index: Int, instance: LayoutNode) {
        current.children.add(index, instance)
        instance.parent = current
    }

    override fun remove(index: Int, count: Int) {
        repeat(count) { current.children.removeAt(index).parent = null }
    }

    override fun move(from: Int, to: Int, count: Int) {
        current.children.move(from, to, count)
    }

    override fun onClear() {
        root.children.clear()
    }

    override fun onEndChanges() = onChanged()
}

object Theme {
    var focus = Color(0xffffc107)
}
