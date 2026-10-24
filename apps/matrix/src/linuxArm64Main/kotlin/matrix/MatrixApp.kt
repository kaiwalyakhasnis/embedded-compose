package matrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ui.Box
import ui.Device
import ui.Modifier
import ui.PointerEventType
import ui.fillMaxSize
import ui.led.PageDots
import ui.pointerInput

// An app for LED matrices, made for one 64x32 HUB75 panel (one dp per LED)
// and laid out from the screen size, so bigger matrices work too. Four
// pages: a clock, a plasma, Conway's Game of Life and a scrolling message.
// Buttons (or a keyboard): Left/Right change the page, Enter the page's
// style. A touch on the left or right half (on a matrix with a touch
// overlay) does the same as Left/Right.

private enum class Page { Clock, Plasma, Life, Marquee }

private const val KEY_ENTER = 28
private const val KEY_SPACE = 57
private const val KEY_KPENTER = 96
private const val KEY_UP = 103
private const val KEY_LEFT = 105
private const val KEY_RIGHT = 106
private const val KEY_DOWN = 108
private const val KEY_VOLUMEDOWN = 114
private const val KEY_VOLUMEUP = 115

@Composable
internal fun MatrixApp() {
    var page by remember { mutableIntStateOf(0) }
    var style by remember { mutableIntStateOf(0) }
    val pages = Page.entries
    val go = { delta: Int ->
        page = (page + delta).mod(pages.size)
        style = 0
    }

    DisposableEffect(Unit) {
        Device.onKey = { code, value ->
            if (value == 1) when (code) {
                KEY_RIGHT, KEY_DOWN, KEY_VOLUMEDOWN -> go(1)
                KEY_LEFT, KEY_UP, KEY_VOLUMEUP -> go(-1)
                KEY_ENTER, KEY_KPENTER, KEY_SPACE -> style++
            }
        }
        onDispose { Device.onKey = null }
    }

    Box(Modifier.fillMaxSize().pointerInput { e ->
        if (e.type == PointerEventType.Press) go(if (e.position.x < Device.width / 2) -1 else 1)
    }) {
        when (pages[page]) {
            Page.Clock -> ClockPage(style)
            Page.Plasma -> PlasmaPage(style)
            Page.Life -> LifePage(style)
            Page.Marquee -> MarqueePage(style)
        }
        PageDots(page, pages.size)
    }
}
