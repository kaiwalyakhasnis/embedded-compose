@file:OptIn(ExperimentalNativeApi::class)

import charger.ChargerApp
import charger.SimulatedBattery
import charger.SysfsBattery
import kotlinx.cinterop.toKString
import platform.posix.getenv
import ui.Color
import ui.Trace
import ui.log
import ui.runApp
import kotlin.experimental.ExperimentalNativeApi

/** The app's entry point, called from the backend's main() (core/hal/ec_hal.h). */
@CName("ec_app_main")
fun kotlinMain(): Int {
    Trace.enabled = getenv("EC_TRACE")?.toKString() == "1"
    // A real battery when the kernel has one; a simulated one otherwise (QEMU)
    // or when asked for (EC_CHARGER_SIMULATE=1).
    val battery = if (SysfsBattery.present() && getenv("EC_CHARGER_SIMULATE")?.toKString() != "1") SysfsBattery() else SimulatedBattery()
    log("charger: ${if (battery.simulated) "simulated" else "sysfs"} battery")
    return runApp(background = Color(0xff05070d)) { ChargerApp(battery) }
}
