package charger

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import kotlin.math.abs
import kotlin.time.TimeSource

internal enum class Status { Charging, Full, Discharging }

internal data class BatteryState(
    val level: Int,           // percent
    val status: Status,
    val plugged: Boolean,
    val currentMa: Int?,      // charge current, when the driver reports it
    val voltageMv: Int?,
    val tempC: Float?,
)

internal interface Battery {
    val simulated: Boolean
    fun read(): BatteryState
    fun onKey(code: Int) {}
}

/** First line of a sysfs file, or null. */
internal fun readLine(path: String): String? = memScoped {
    val f = fopen(path, "r") ?: return null
    val buf = allocArray<ByteVar>(128)
    val line = fgets(buf, 128, f)?.toKString()
    fclose(f)
    line?.trim()
}

/** The kernel's power_supply class, as healthd reads it. */
internal class SysfsBattery(private val dir: String = "/sys/class/power_supply") : Battery {
    override val simulated = false

    override fun read(): BatteryState {
        val b = "$dir/battery"
        val level = readLine("$b/capacity")?.toIntOrNull() ?: 0
        val plugged = listOf("usb", "ac", "wireless", "dc").any { readLine("$dir/$it/online") == "1" }
        val status = when (readLine("$b/status")) {
            "Full" -> Status.Full
            "Charging" -> Status.Charging
            else -> if (plugged && level >= 100) Status.Full else if (plugged) Status.Charging else Status.Discharging
        }
        return BatteryState(
            level = level.coerceIn(0, 100),
            status = status,
            plugged = plugged,
            currentMa = readLine("$b/current_now")?.toLongOrNull()?.let { (abs(it) / 1000).toInt() },
            voltageMv = readLine("$b/voltage_now")?.toLongOrNull()?.let { (it / 1000).toInt() },
            tempC = readLine("$b/temp")?.toIntOrNull()?.let { it / 10f },
        )
    }

    companion object {
        fun present() = readLine("/sys/class/power_supply/battery/capacity") != null
    }
}

/**
 * For QEMU and other boards without a battery: charges from 8 % to full in
 * about 25 s, stays full for a while, and starts over. UP/DOWN change the
 * level (and stop the cycle), ENTER plugs/unplugs.
 */
internal class SimulatedBattery : Battery {
    override val simulated = true
    private val clock = TimeSource.Monotonic
    private var start = clock.markNow()
    private var manual: Int? = null
    private var plugged = true

    override fun read(): BatteryState {
        val level = manual ?: run {
            val s = start.elapsedNow().inWholeMilliseconds / 1000f
            val l = 8 + (s * 3.7f).toInt()
            if (l > 100 + 18) { start = clock.markNow(); 8 } else l.coerceAtMost(100)
        }
        val status = when {
            !plugged -> Status.Discharging
            level >= 100 -> Status.Full
            else -> Status.Charging
        }
        val current = if (status == Status.Charging) 2100 - level * 14 else 0
        return BatteryState(level, status, plugged, current, 3600 + level * 6, 31.5f)
    }

    override fun onKey(code: Int) {
        val now = read().level
        when (code) {
            KEY_UP -> manual = (now + 5).coerceAtMost(100)
            KEY_DOWN -> manual = (now - 5).coerceAtLeast(0)
            KEY_ENTER -> plugged = !plugged
        }
    }

    private companion object {
        const val KEY_ENTER = 28
        const val KEY_UP = 103
        const val KEY_DOWN = 108
    }
}
