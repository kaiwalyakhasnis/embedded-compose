package demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ui.Alignment
import ui.Arrangement
import ui.Box
import ui.Button
import ui.Canvas
import ui.CircleShape
import ui.Color
import ui.Column
import ui.DrawScope
import ui.Image
import ui.ImageBitmap
import ui.Modifier
import ui.Offset
import ui.PointerEventType
import ui.RoundedCornerShape
import ui.Row
import ui.Size
import ui.Spacer
import ui.Stroke
import ui.Text
import ui.animateFloatAsState
import ui.background
import ui.border
import ui.clickable
import ui.dp
import ui.fillMaxSize
import ui.fillMaxWidth
import ui.height
import ui.offset
import ui.padding
import ui.pointerInput
import ui.rememberFrameMillis
import ui.size
import ui.testTag
import ui.weight
import ui.width
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Surface = Color(0xff121722)
private val Card = Color(0xff1e2533)
private val Muted = Color(0xff9aa4b8)
private val Accents = listOf(Color(0xff26a69a), Color(0xffef5350), Color(0xffffca28), Color(0xff42a5f5), Color(0xffab47bc))

@Composable
internal fun DemoApp() {
    var tab by remember { mutableIntStateOf(0) }
    var accent by remember { mutableStateOf(Accents[0]) }

    Column(Modifier.fillMaxSize().background(Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(accent, CircleShape))
            Spacer(Modifier.width(12.dp))
            Text("Embedded Compose", scale = 2, bold = true)
        }
        Text("Jetpack Compose runtime on a tiny native display", color = Muted)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Basics", "Shapes", "Motion").forEachIndexed { i, title ->
                Button(
                    onClick = { tab = i },
                    modifier = Modifier.testTag("tab-${title.lowercase()}").weight(1f),
                    color = if (tab == i) accent else Card,
                ) { Text(title, color = if (tab == i) Color.Black else Color.White) }
            }
        }

        when (tab) {
            0 -> BasicsTab(accent) { accent = it }
            1 -> ShapesTab(accent)
            else -> MotionTab(accent)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Muted)
        content()
    }
}

// ------------------------------------------------------------------ Basics

@Composable
private fun BasicsTab(accent: Color, onAccent: (Color) -> Unit) {
    var count by remember { mutableIntStateOf(0) }
    Section("Counter (state + recomposition)") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { count-- }, modifier = Modifier.testTag("minus").width(88.dp)) { Text("-", scale = 2) }
            Text("$count", scale = 3, bold = true, color = accent)
            Button(onClick = { count++ }, modifier = Modifier.testTag("plus").width(88.dp)) { Text("+", scale = 2) }
        }
        Button(onClick = { count = 0 }, modifier = Modifier.testTag("reset").fillMaxWidth(), enabled = count != 0, color = Color(0xff455a64)) {
            Text("Reset")
        }
    }
    Section("Accent colour") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Accents.forEachIndexed { i, c ->
                val selected = c == accent
                Box(
                    Modifier.testTag("accent-$i").size(44.dp)
                        .background(c, CircleShape)
                        .border(if (selected) 4.dp else 0.dp, Color.White, CircleShape)
                        .clickable { onAccent(c) },
                )
            }
        }
    }
    val logo = remember { ImageBitmap.load("icon_error") }
    if (logo != null) {
        Section("Image (PNG decoded by minui)") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Image(logo) }
        }
    }
}

// ------------------------------------------------------------------ Shapes

private class ShapeDemo(val name: String, val draw: DrawScope.(Color) -> Unit)

private val shapeDemos = listOf(
    ShapeDemo("Rect") { drawRect(it, Offset(10f, 10f), Size(size.width - 20, size.height - 20)) },
    ShapeDemo("RoundRect") { drawRoundRect(it, Offset(10f, 10f), Size(size.width - 20, size.height - 20), cornerRadius = 36f) },
    ShapeDemo("Circle") { drawCircle(it, radius = size.minDimension / 2 - 10) },
    ShapeDemo("Oval") { drawOval(it, Offset(6f, size.height / 4), Size(size.width - 12, size.height / 2)) },
    ShapeDemo("Triangle") {
        drawPolygon(it, listOf(Offset(size.width / 2, 10f), Offset(size.width - 10, size.height - 10), Offset(10f, size.height - 10)))
    },
    ShapeDemo("Star") { drawPolygon(it, star(center, size.minDimension / 2 - 8, 5)) },
    ShapeDemo("Lines") {
        for (i in 0..4) drawLine(it, Offset(10f, 14f + i * 22), Offset(size.width - 10, size.height - 14 - i * 22), strokeWidth = 2f + i * 2)
    },
    ShapeDemo("Ring") {
        drawCircle(it, radius = size.minDimension / 2 - 10, style = Stroke(14f))
        drawRoundRect(it.copy(alpha = 0.6f), Offset(size.width / 2 - 30, size.height / 2 - 30), Size(60f, 60f), 12f, Stroke(6f))
    },
    ShapeDemo("Arc / pie") {
        drawArc(it, -90f, 250f, useCenter = true, Offset(14f, 14f), Size(size.width - 28, size.height - 28))
        drawArc(it.copy(alpha = 0.5f), -90f, 250f, useCenter = false, Offset(2f, 2f), Size(size.width - 4, size.height - 4), Stroke(8f))
    },
)

private fun star(c: Offset, r: Float, points: Int) = (0 until points * 2).map { i ->
    val a = -PI / 2 + i * PI / points
    val rr = if (i % 2 == 0) r else r * 0.42f
    Offset(c.x + (cos(a) * rr).toFloat(), c.y + (sin(a) * rr).toFloat())
}

@Composable
private fun ShapesTab(accent: Color) {
    var outlined by remember { mutableStateOf(false) }
    Section("Basic shapes (scanline spans -> gr_fill)") {
        for (row in shapeDemos.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (demo in row) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.fillMaxWidth().height(100.dp).background(Color(0xff2a3344), RoundedCornerShape(10.dp))) {
                            demo.draw(this, accent)
                            if (outlined) drawRect(Color.White.copy(alpha = 0.4f), style = Stroke(2f))
                        }
                        Text(demo.name, color = Muted)
                    }
                }
            }
        }
    }
    Button(onClick = { outlined = !outlined }, modifier = Modifier.testTag("bounds").fillMaxWidth(), color = Color(0xff455a64)) {
        Text(if (outlined) "Hide canvas bounds" else "Show canvas bounds")
    }
}

// ------------------------------------------------------------------ Motion

@Composable
private fun MotionTab(accent: Color) {
    var running by remember { mutableStateOf(true) }
    val millis by rememberFrameMillis(running)
    Section("Frame animation (withFrameNanos)") {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val t = millis / 1000f
            // A ball bouncing left/right and a clock hand going round.
            val phase = (t * 0.5f) % 1f
            val x = 30f + (size.width - 260f) * (1 - abs(1 - 2 * phase))
            drawCircle(accent, 28f, Offset(x, size.height / 2))
            val c = Offset(size.width - 100f, size.height / 2)
            drawCircle(Color(0xff2a3344), 56f, c)
            val a = t * 2 * PI.toFloat() / 4
            drawLine(Color.White, c, c + Offset(cos(a) * 48, sin(a) * 48), 6f)
            drawCircle(Color.White, 6f, c)
        }
        Button(onClick = { running = !running }, modifier = Modifier.testTag("pause").fillMaxWidth()) { Text(if (running) "Pause" else "Play") }
    }

    var wide by remember { mutableStateOf(false) }
    val fraction by animateFloatAsState(if (wide) 1f else 0.25f, durationMillis = 600)
    Section("animateFloatAsState") {
        Box(Modifier.fillMaxWidth().height(24.dp).background(Color(0xff2a3344), RoundedCornerShape(12.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(24.dp).background(accent, RoundedCornerShape(12.dp)))
        }
        Button(onClick = { wide = !wide }, modifier = Modifier.testTag("grow").fillMaxWidth(), color = Color(0xff455a64)) { Text(if (wide) "Shrink" else "Grow") }
    }

    var ballX by remember { mutableFloatStateOf(120f) }
    var ballY by remember { mutableFloatStateOf(110f) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    Section("Drag the ball (pointerInput)") {
        Canvas(
            Modifier.testTag("drag-area").fillMaxWidth().height(170.dp).background(Color(0xff2a3344), RoundedCornerShape(12.dp))
                .pointerInput { e ->
                    when (e.type) {
                        PointerEventType.Press -> { dragging = true; grab = e.position - Offset(ballX, ballY) }
                        PointerEventType.Move -> if (dragging) { ballX = e.position.x - grab.x; ballY = e.position.y - grab.y }
                        PointerEventType.Release -> dragging = false
                    }
                },
        ) {
            val r = 40f
            ballX = ballX.coerceIn(r, size.width - r)
            ballY = ballY.coerceIn(r, size.height - r)
            drawCircle(if (dragging) Color.White else accent, r, Offset(ballX, ballY))
        }
    }
}
