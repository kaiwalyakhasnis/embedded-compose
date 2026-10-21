package ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

private fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

/** Animates towards [target] over [durationMillis], one step per frame (withFrameNanos). */
@Composable
fun animateFloatAsState(target: Float, durationMillis: Int = 300): State<Float> {
    val state = remember { mutableFloatStateOf(target) }
    LaunchedEffect(target) {
        val start = state.floatValue
        var t0 = -1L
        while (true) {
            val done = withFrameNanos { now ->
                if (t0 < 0) t0 = now
                val f = ((now - t0) / 1e6f / durationMillis).coerceIn(0f, 1f)
                state.floatValue = start + (target - start) * easeInOut(f)
                f >= 1f
            }
            if (done) break
        }
    }
    return state
}

/** Milliseconds since this first composed, updated every frame while [running]. */
@Composable
fun rememberFrameMillis(running: Boolean): State<Long> {
    val state = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val base = state.longValue
        var t0 = -1L
        while (true) {
            withFrameNanos { now ->
                if (t0 < 0) t0 = now
                state.longValue = base + (now - t0) / 1_000_000
            }
        }
    }
    return state
}
