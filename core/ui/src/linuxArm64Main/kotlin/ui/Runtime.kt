package ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import hal.EC_EV_KEY
import hal.EC_EV_POINTER_DOWN
import hal.EC_EV_POINTER_MOVE
import hal.EC_EV_POINTER_UP
import hal.ec_blank
import hal.ec_clear
import hal.ec_density
import hal.ec_display_init
import hal.ec_event
import hal.ec_flip
import hal.ec_height
import hal.ec_input_open
import hal.ec_input_poll
import hal.ec_log
import hal.ec_now_nanos
import hal.ec_power_off
import hal.ec_rss_kb
import hal.ec_width
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

fun log(msg: String) = ec_log(msg)

/**
 * Single-threaded event loop: a coroutine dispatcher with timers. The app's
 * main loop drains it between waits on input, so composition, effects,
 * delay() and input all run on the one thread the display is used from.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class MainLoop : CoroutineDispatcher(), Delay {
    private class Timer(val at: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val queue = ArrayDeque<Runnable>()
    private val timers = ArrayList<Timer>()  // in firing order

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val t = schedule(timeMillis) { with(continuation) { resumeUndispatched(Unit) } }
        continuation.invokeOnCancellation { t.cancelled = true }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val t = schedule(timeMillis) { block.run() }
        return DisposableHandle { t.cancelled = true }
    }

    private fun schedule(ms: Long, action: () -> Unit): Timer {
        val t = Timer(ec_now_nanos() + ms.coerceAtLeast(0) * 1_000_000, action)
        val i = timers.indexOfFirst { it.at > t.at }
        if (i < 0) timers.add(t) else timers.add(i, t)
        return t
    }

    /** Runs queued tasks and due timers until nothing is ready. */
    fun runReady() {
        while (true) {
            while (queue.isNotEmpty()) queue.removeFirst().run()
            timers.removeAll { it.cancelled }
            val now = ec_now_nanos()
            if (timers.isEmpty() || timers[0].at > now) return
            val t = timers.removeAt(0)
            t.action()
        }
    }

    val hasQueued get() = queue.isNotEmpty()

    fun msUntilNextTimer(): Long? {
        val t = timers.firstOrNull { !it.cancelled } ?: return null
        return ((t.at - ec_now_nanos()) / 1_000_000 + 1).coerceAtLeast(0)
    }
}

private const val FRAME_NS = 16_666_667L

/**
 * Runs [content] full-screen on the display until the process is killed.
 * Like a tiny ComposeView: Recomposer + frame clock + our NodeApplier,
 * then measure/place/draw the node tree through ec_hal.h when anything changed.
 */
fun runApp(background: Color = Color.Black, content: @Composable () -> Unit): Int {
    if (ec_display_init() != 0) {
        log("display init failed")
        return 1
    }
    val width = ec_width()
    val height = ec_height()
    Device.width = width
    Device.height = height
    Density.scale = ec_density()
    log("screen ${width}x$height, density ${Density.scale}, input devices: ${ec_input_open()}")

    val loop = MainLoop()
    val clock = BroadcastFrameClock()
    val crash = CoroutineExceptionHandler { _, e ->
        log("uncaught: $e\n${e.stackTraceToString()}")
        exitProcess(2)
    }
    val scope = CoroutineScope(loop + clock + Job() + crash)
    val recomposer = Recomposer(scope.coroutineContext)
    scope.launch { recomposer.runRecomposeAndApplyChanges() }

    var dirty = true
    val root = LayoutNode().apply { isRoot = true }
    val composition = Composition(NodeApplier(root) { dirty = true }, recomposer)
    Snapshot.registerApplyObserver { _, _ -> dirty = true }
    composition.setContent(content)

    val input = InputDispatcher(root) { dirty = true }
    val events = nativeHeap.allocArray<ec_event>(64)
    var nextFrame = 0L
    var frames = 0
    var totalFrames = 0L
    var renderNs = 0L
    var statsAt = ec_now_nanos()

    while (true) {
        loop.runReady()
        Snapshot.sendApplyNotifications()
        loop.runReady()

        var now = ec_now_nanos()
        if (clock.hasAwaiters && now >= nextFrame) {
            nextFrame = now + FRAME_NS
            clock.sendFrame(now)
            loop.runReady()
            Snapshot.sendApplyNotifications()
            loop.runReady()
        }

        if (dirty) {
            dirty = false
            val t0 = ec_now_nanos()
            root.measure(Constraints.fixed(width, height))
            root.place(0, 0)
            if (Trace.enabled) TestTags.report(root)
            Raster.setColor(background)
            ec_clear()
            root.draw(input.focused)
            ec_flip()
            frames++
            totalFrames++
            renderNs += ec_now_nanos() - t0
            if (Trace.enabled) log("frame $totalFrames")
        }

        now = ec_now_nanos()
        if (frames > 0 && now - statsAt > 5_000_000_000L) {
            log("stats: $frames frames in ${(now - statsAt) / 1_000_000} ms, render avg ${renderNs / frames / 1000} us, rss ${ec_rss_kb()} kB")
            frames = 0
            renderNs = 0
            statsAt = now
        }

        val waits = listOfNotNull(
            if (loop.hasQueued || dirty) 0L else null,
            if (clock.hasAwaiters) ((nextFrame - now) / 1_000_000).coerceAtLeast(0) else null,
            loop.msUntilNextTimer(),
        )
        val timeout = waits.minOrNull()?.coerceAtMost(5000)?.toInt() ?: 5000
        val n = ec_input_poll(timeout, events, 64)
        for (i in 0 until n) {
            val e = events[i]
            input.handle(e.type, e.code, e.value, e.x, e.y)
        }
    }
}

object Trace {
    var enabled = false
}

/** The device around the UI: screen power, power off, and raw input for apps that need it. */
object Device {
    /** Called for every key event (linux KEY_* code, 1 down / 0 up / 2 repeat) before focus handling. */
    var onKey: ((code: Int, value: Int) -> Unit)? = null
    /** Called for every input event of any kind (user activity). */
    var onInput: (() -> Unit)? = null
    /** Called for every key event after [onKey]; returning true keeps the key from focus handling (the app uses it). */
    var keyFilter: ((code: Int, value: Int) -> Boolean)? = null

    /** Screen size in pixels, set when [runApp] starts. */
    var width = 0
        internal set
    var height = 0
        internal set

    var screenOn = true
        private set

    fun setScreen(on: Boolean) {
        if (on == screenOn) return
        screenOn = on
        ec_blank(if (on) 0 else 1)
    }

    fun powerOff() = ec_power_off()
}

/**
 * With tracing on, logs "tag <name> <left> <top> <right> <bottom>" when a
 * [testTag]'s bounds change and "tag <name> gone" when it leaves the tree, so
 * a test driver can find what to tap on any screen size.
 */
private object TestTags {
    private var last = mapOf<String, IntRect>()

    fun report(root: LayoutNode) {
        val now = LinkedHashMap<String, IntRect>()
        root.collectTestTags(now)
        for ((tag, b) in now) if (last[tag] != b) log("tag $tag ${b.left} ${b.top} ${b.right} ${b.bottom}")
        for (tag in last.keys) if (tag !in now) log("tag $tag gone")
        last = now
    }
}

// ------------------------------------------------------------------- input

private const val KEY_ESC = 1
private const val KEY_TAB = 15
private const val KEY_ENTER = 28
private const val KEY_SPACE = 57
private const val KEY_KPENTER = 96
private const val KEY_UP = 103
private const val KEY_LEFT = 105
private const val KEY_RIGHT = 106
private const val KEY_DOWN = 108
private const val KEY_VOLUMEDOWN = 114
private const val KEY_VOLUMEUP = 115
private const val KEY_POWER = 116

/**
 * Touch: press goes to the topmost pointerInput or clickable under the finger.
 * Keys (recovery style): volume/arrows move focus between clickables,
 * power/enter clicks the focused one.
 */
internal class InputDispatcher(private val root: LayoutNode, private val invalidate: () -> Unit) {
    var focused: LayoutNode? = null
        private set
    private var pressed: LayoutNode? = null
    private var capture: Pair<LayoutNode, PointerElement>? = null
    private var keyPressed: LayoutNode? = null

    fun handle(type: Int, code: Int, value: Int, x: Int, y: Int) {
        if (Trace.enabled) log("input type=$type code=$code value=$value x=$x y=$y")
        Device.onInput?.invoke()
        if (type == EC_EV_KEY.toInt()) {
            Device.onKey?.invoke(code, value)
            if (Device.keyFilter?.invoke(code, value) == true) return
        }
        when (type) {
            EC_EV_POINTER_DOWN.toInt() -> pointerDown(x, y)
            EC_EV_POINTER_MOVE.toInt() -> pointerMove(x, y)
            EC_EV_POINTER_UP.toInt() -> pointerUp(x, y)
            EC_EV_KEY.toInt() -> key(code, value)
        }
    }

    private fun relative(node: LayoutNode, e: Modifier.Element, x: Int, y: Int): Offset {
        val b = node.boundsOf(e)
        return Offset((x - b.left).toFloat(), (y - b.top).toFloat())
    }

    private fun pointerDown(x: Int, y: Int) {
        setFocus(null)
        root.hitTest(x, y) { it as? PointerElement }?.let { hit ->
            capture = hit
            hit.second.onPointer(PointerEvent(PointerEventType.Press, relative(hit.first, hit.second, x, y)))
            return
        }
        val hit = root.hitTest(x, y) { (it as? ClickableElement)?.takeIf { c -> c.enabled } } ?: return
        pressed = hit.first
        hit.second.interaction?.pressed = true
    }

    private fun pointerMove(x: Int, y: Int) {
        capture?.let { (node, _) ->
            // The element is re-created on recomposition; use the current one.
            val e = node.elements.firstNotNullOfOrNull { it as? PointerElement } ?: return
            if (node.isAttached()) e.onPointer(PointerEvent(PointerEventType.Move, relative(node, e, x, y)))
            return
        }
        val node = pressed ?: return
        val c = node.clickable ?: return
        c.interaction?.pressed = node.isAttached() && node.boundsOf(c).contains(x, y)
    }

    private fun pointerUp(x: Int, y: Int) {
        capture?.let { (node, _) ->
            capture = null
            val e = node.elements.firstNotNullOfOrNull { it as? PointerElement } ?: return
            if (node.isAttached()) e.onPointer(PointerEvent(PointerEventType.Release, relative(node, e, x, y)))
            return
        }
        val node = pressed ?: return
        pressed = null
        val c = node.clickable ?: return
        c.interaction?.pressed = false
        if (c.enabled && node.isAttached() && node.boundsOf(c).contains(x, y)) {
            if (Trace.enabled) log("click at $x,$y")
            c.onClick()
        }
    }

    private fun key(code: Int, value: Int) {
        when (code) {
            KEY_DOWN, KEY_RIGHT, KEY_TAB, KEY_VOLUMEDOWN -> if (value != 0) moveFocus(1)
            KEY_UP, KEY_LEFT, KEY_VOLUMEUP -> if (value != 0) moveFocus(-1)
            KEY_ENTER, KEY_KPENTER, KEY_SPACE, KEY_POWER -> {
                val node = focused?.takeIf { it.isAttached() }
                if (value == 1) {
                    if (node == null) { moveFocus(1); return }
                    keyPressed = node
                    node.clickable?.interaction?.pressed = true
                } else if (value == 0) {
                    val p = keyPressed ?: return
                    keyPressed = null
                    val c = p.clickable ?: return
                    c.interaction?.pressed = false
                    if (p === node && c.enabled) {
                        if (Trace.enabled) log("key click")
                        c.onClick()
                    }
                }
            }
            KEY_ESC -> setFocus(null)
        }
    }

    private fun moveFocus(delta: Int) {
        val list = mutableListOf<LayoutNode>()
        root.collectFocusable(list)
        if (list.isEmpty()) return
        val i = list.indexOf(focused)
        val next = if (i < 0) (if (delta > 0) 0 else list.size - 1) else (i + delta).mod(list.size)
        setFocus(list[next])
    }

    private fun setFocus(node: LayoutNode?) {
        if (focused === node) return
        focused?.clickable?.interaction?.focused = false
        focused = node
        node?.clickable?.interaction?.focused = true
        invalidate()
    }
}
