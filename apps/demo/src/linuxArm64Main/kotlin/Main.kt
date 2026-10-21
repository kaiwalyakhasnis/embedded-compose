@file:OptIn(ExperimentalNativeApi::class)

import demo.DemoApp
import kotlinx.cinterop.toKString
import platform.posix.getenv
import ui.Color
import ui.Trace
import ui.runApp
import kotlin.experimental.ExperimentalNativeApi

/** The app's entry point, called from the backend's main() (core/hal/ec_hal.h). */
@CName("ec_app_main")
fun kotlinMain(): Int {
    Trace.enabled = getenv("EC_TRACE")?.toKString() == "1"
    return runApp(background = Color(0xff121722)) { DemoApp() }
}
