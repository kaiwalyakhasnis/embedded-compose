package charger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import ui.Canvas
import ui.Device
import ui.Modifier
import ui.fillMaxSize
import ui.log
import ui.rememberFrameMillis
import kotlin.math.exp
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Screen goes dark after this long without input; any key or touch wakes it. */
private val SCREEN_TIMEOUT = 30.seconds
/** Unplugged this long: power off (as AOSP's charger does). */
private val UNPLUGGED_SHUTDOWN = 10.seconds

/**
 * Off-mode charging screen. Does what AOSP's charger does besides drawing:
 * screen timeout and wake, power off when unplugged. Holding POWER to boot
 * is the backend's EC_POWER_REBOOT.
 */
@Composable
internal fun ChargerApp(battery: Battery) {
    val clock = TimeSource.Monotonic
    var state by remember { mutableStateOf(battery.read()) }
    var screenOn by remember { mutableStateOf(true) }
    var lastInput by remember { mutableStateOf(clock.markNow()) }
    var unplugged by remember { mutableStateOf<TimeMark?>(null) }

    DisposableEffect(battery) {
        Device.onInput = {
            lastInput = clock.markNow()
            if (!screenOn) {
                screenOn = true
                Device.setScreen(true)
            }
        }
        Device.onKey = { code, value -> if (value == 1) battery.onKey(code) }
        onDispose {
            Device.onInput = null
            Device.onKey = null
        }
    }

    LaunchedEffect(battery) {
        while (true) {
            state = battery.read()
            if (!state.plugged) {
                val since = unplugged ?: clock.markNow().also { unplugged = it; log("charger: unplugged") }
                if (since.elapsedNow() >= UNPLUGGED_SHUTDOWN && !battery.simulated) Device.powerOff()
                if (!screenOn) { screenOn = true; Device.setScreen(true) }  // show the countdown
            } else {
                unplugged = null
            }
            if (screenOn && !battery.simulated && lastInput.elapsedNow() >= SCREEN_TIMEOUT) {
                screenOn = false
                Device.setScreen(false)
            }
            delay(if (battery.simulated) 100 else 1000)
        }
    }

    val level = rememberGlide(state.level / 100f, running = screenOn)
    val millis by rememberFrameMillis(screenOn)
    val powerOffIn = unplugged?.let { (UNPLUGGED_SHUTDOWN - it.elapsedNow()).inWholeSeconds.coerceAtLeast(0).toInt() }

    Canvas(Modifier.fillMaxSize()) {
        if (screenOn) drawCharger(millis / 1000f, level.floatValue, state, powerOffIn, battery.simulated)
    }
}

/**
 * Follows [target] smoothly, every frame (time constant 0.35 s). Unlike
 * animateFloatAsState it doesn't restart its easing when the target keeps
 * changing, so frequent readings don't stall it.
 */
@Composable
private fun rememberGlide(target: Float, running: Boolean): MutableFloatState {
    val value = remember { mutableFloatStateOf(target) }
    val latest by rememberUpdatedState(target)
    LaunchedEffect(running) {
        if (!running) {
            value.floatValue = latest
            return@LaunchedEffect
        }
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0) {
                    val k = 1f - exp(-(now - last) / 1e9f / 0.35f)
                    value.floatValue += (latest - value.floatValue) * k
                }
                last = now
            }
        }
    }
    return value
}
