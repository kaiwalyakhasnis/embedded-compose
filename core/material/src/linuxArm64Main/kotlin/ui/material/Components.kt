package ui.material

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import ui.Alignment
import ui.Arrangement
import ui.Box
import ui.Canvas
import ui.Color
import ui.Column
import ui.Dp
import ui.Interaction
import ui.Layout
import ui.MeasurePolicy
import ui.MeasureResult
import ui.Modifier
import ui.Row
import ui.background
import ui.clickable
import ui.dp
import ui.drawBehind
import ui.fillMaxWidth
import ui.height
import ui.heightIn
import ui.padding
import ui.size
import ui.weight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

// Material 3 components, drawn to its specs (sizes in dp, colors by role).
// Without touch, the keys move a focus: every control shows Material's
// focus indicator and its focused state layer.

/** A container: a rounded rectangle in [color], with an outline in [border] if it isn't transparent. */
fun Modifier.container(color: Color, radius: Dp, border: Color = Color.Transparent, borderWidth: Dp = 1.dp) = drawBehind {
    val r = radius.toPx().toFloat()
    roundRect(color, r)
    if (border.alpha > 0) roundRect(border, r, stroke = borderWidth.toPx().toFloat())
}

/** The state layer (pressed, focused) over a control of corner [radius], and the focus indicator around it. */
fun Modifier.indication(interaction: Interaction, radius: Dp, content: Color, ring: Color) = drawBehind {
    val r = radius.toPx().toFloat()
    if (interaction.pressed || interaction.focused) roundRect(content.copy(alpha = if (interaction.pressed) 0.12f else 0.10f), r)
    if (interaction.focused) {
        val o = 5.dp.toPx()  // 2 dp from the control, 3 dp wide
        roundRect(ring, r + o, -o, -o, size.width.toInt() + 2 * o, size.height.toInt() + 2 * o, 3.dp.toPx().toFloat())
    }
}

/** A surface of [color] with corner [radius]; content on it gets [contentColor]. */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colors.surface,
    contentColor: Color = MaterialTheme.colors.onSurface,
    radius: Dp = 0.dp,
    border: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(modifier.container(color, radius, border)) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

enum class ButtonStyle { Filled, Tonal, Outlined, Text, Elevated }

/** The container and content colors of a button in [style]. */
@Composable
private fun buttonColors(style: ButtonStyle, enabled: Boolean): Triple<Color, Color, Color> {
    val cs = MaterialTheme.colors
    val (bg, fg) = when (style) {
        ButtonStyle.Filled -> cs.primary to cs.onPrimary
        ButtonStyle.Tonal -> cs.secondaryContainer to cs.onSecondaryContainer
        ButtonStyle.Elevated -> cs.surfaceContainerLow to cs.primary
        ButtonStyle.Outlined -> Color.Transparent to cs.primary
        ButtonStyle.Text -> Color.Transparent to cs.primary
    }
    val border = if (style == ButtonStyle.Outlined) (if (enabled) cs.outlineVariant else cs.onSurface.copy(alpha = 0.12f)) else Color.Transparent
    if (!enabled) return Triple(if (bg.alpha == 0) bg else cs.onSurface.copy(alpha = 0.12f), cs.onSurface.copy(alpha = 0.38f), border)
    return Triple(bg, fg, border)
}

/** Material's common button: 40 dp high, fully rounded, label in labelLarge; [icon] before it. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
    enabled: Boolean = true,
    icon: String? = null,
    interaction: Interaction = remember { Interaction() },
    content: @Composable () -> Unit,
) {
    val (bg, fg, border) = buttonColors(style, enabled)
    val side = if (style == ButtonStyle.Text) 12.dp else 24.dp
    Box(
        modifier.heightIn(min = 40.dp).container(bg, 20.dp, border).indication(interaction, 20.dp, fg, MaterialTheme.colors.secondary)
            .clickable(enabled, interaction, onClick)
            .padding(start = if (icon != null) Dp(side.value - 8) else side, end = side, top = 10.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides Typography.labelLarge) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Icon(icon, size = 18.dp)
                content()
            }
        }
    }
}

/** A 40 dp round icon button; [ButtonStyle.Text] is the standard (no container) one. */
@Composable
fun IconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Text,
    enabled: Boolean = true,
    interaction: Interaction = remember { Interaction() },
) {
    val cs = MaterialTheme.colors
    var (bg, fg, border) = buttonColors(style, enabled)
    if (style == ButtonStyle.Text && enabled) fg = cs.onSurfaceVariant
    Box(
        modifier.size(40.dp).container(bg, 20.dp, border).indication(interaction, 20.dp, fg, cs.secondary)
            .clickable(enabled, interaction, onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, size = 24.dp, color = fg) }
}

enum class CardStyle { Filled, Elevated, Outlined }

/** Material's card: 12 dp corners; [padding] around the content. */
@Composable
fun Card(modifier: Modifier = Modifier, style: CardStyle = CardStyle.Filled, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colors
    val shadow = if (style == CardStyle.Elevated) Modifier.drawBehind {
        val r = 12.dp.toPx().toFloat()
        val d = 1.dp.toPx()
        for (i in 1..3) roundRect(Color.Black.copy(alpha = 0.08f), r + i, -i, d - i, size.width.toInt() + 2 * i, size.height.toInt() + 2 * i)
    } else Modifier
    val (bg, border) = when (style) {
        CardStyle.Filled -> cs.surfaceContainerHighest to Color.Transparent
        CardStyle.Elevated -> cs.surfaceContainerLow to Color.Transparent
        CardStyle.Outlined -> cs.surface to cs.outlineVariant
    }
    Surface(modifier.then(shadow), bg, cs.onSurface, 12.dp, border) {
        Box(Modifier.fillMaxWidth().padding(padding)) { content() }
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colors.outlineVariant))

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, height: Dp = 24.dp) =
    Box(modifier.size(1.dp, height).background(MaterialTheme.colors.outlineVariant))

/** A checkbox (18 dp, in a 40 dp state layer) and its label; the whole row toggles it. */
@Composable
fun CheckboxRow(
    checked: Boolean,
    onToggle: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interaction: Interaction = remember { Interaction() },
) {
    val cs = MaterialTheme.colors
    Row(modifier.heightIn(min = 48.dp).clickable(enabled, interaction, onToggle), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        Canvas(Modifier.size(40.dp)) {
            val c = size.width / 2
            val layer = if (checked) cs.primary else cs.onSurface
            if (interaction.pressed || interaction.focused) circle(layer.copy(alpha = if (interaction.pressed) 0.12f else 0.10f), c, c, c)
            if (interaction.focused) circle(cs.secondary, c, c, c + 5.dp.toPx(), 3.dp.toPx().toFloat())
            val box = 18.dp.toPx()
            val at = (size.width.toInt() - box) / 2
            val r = 2.dp.toPx().toFloat()
            if (checked) {
                roundRect(cs.primary, r, at, at, box, box)
                val u = box / 18f
                drawVector(cs.onPrimary, at, at, box, box) {
                    line(4.2f * u, 9.4f * u, 7.4f * u, 12.6f * u, 2f * u, round = false)
                    line(6.7f * u, 12.6f * u, 14f * u, 5.3f * u, 2f * u, round = false)
                }
            } else {
                roundRect(cs.onSurfaceVariant, r, at, at, box, box, 2.dp.toPx().toFloat())
            }
        }
        Text(label, Modifier.weight(1f), Typography.bodyLarge, cs.onSurface)
    }
}

/** A filter chip: 32 dp, 8 dp corners; outlined, or filled with a check mark when [selected]. */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    interaction: Interaction = remember { Interaction() },
) {
    val cs = MaterialTheme.colors
    val bg = if (selected) cs.secondaryContainer else Color.Transparent
    val fg = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    Row(
        modifier.heightIn(min = 32.dp).container(bg, 8.dp, if (selected) Color.Transparent else cs.outlineVariant)
            .indication(interaction, 8.dp, fg, cs.secondary).clickable(true, interaction, onClick)
            .padding(start = if (selected) 8.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        if (selected) Icon("check", size = 18.dp, color = fg)
        Text(label, style = Typography.labelLarge, color = fg)
    }
}

/** Children left to right, wrapping onto new lines. */
@Composable
fun FlowRow(modifier: Modifier = Modifier, spacing: Dp = 8.dp, lineSpacing: Dp = 8.dp, content: @Composable () -> Unit) {
    val policy = remember(spacing, lineSpacing) {
        MeasurePolicy { children, c ->
            val gap = spacing.toPx(); val lineGap = lineSpacing.toPx()
            val loose = c.loosen()
            val xs = IntArray(children.size); val ys = IntArray(children.size)
            var x = 0; var y = 0; var lineH = 0; var w = 0
            children.forEachIndexed { i, child ->
                val s = child.measure(loose)
                if (x > 0 && x + s.width > c.maxWidth) { x = 0; y += lineH + lineGap; lineH = 0 }
                xs[i] = x; ys[i] = y
                x += s.width + gap
                lineH = max(lineH, s.height)
                w = max(w, x - gap)
            }
            MeasureResult(c.constrainWidth(w), c.constrainHeight(y + lineH)) {
                children.forEachIndexed { i, child -> child.placeAt(xs[i], ys[i]) }
            }
        }
    }
    Layout(modifier, policy, content = content)
}

/**
 * Material's slider (2024): a 16 dp track, active in primary up to a
 * 4 x 44 dp handle, 6 dp gaps, and a stop dot at the end. [fraction] is
 * from 0 to 1; changing it is up to the controls around it.
 */
@Composable
fun Slider(fraction: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colors
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val w = size.width
        val h = size.height
        val track = 16.dp.toPx().toFloat()
        val handle = 4.dp.toPx().toFloat()
        val gap = 6.dp.toPx().toFloat()
        val outer = track / 2
        val inner = 2.dp.toPx().toFloat()
        val hx = handle / 2 + fraction.coerceIn(0f, 1f) * (w - handle)
        val top = ((h - track) / 2).toInt()
        val activeEnd = hx - handle / 2 - gap
        if (activeEnd > inner * 2) drawVector(cs.primary, 0, top, activeEnd.toInt() + 1, track.toInt() + 1) {
            roundRect(0f, 0f, activeEnd, track, outer, inner, inner, outer)
        }
        val inactiveStart = hx + handle / 2 + gap
        if (w - inactiveStart > inner * 2) {
            val x0 = inactiveStart.toInt()
            drawVector(cs.secondaryContainer, x0, top, (w - x0).toInt(), track.toInt() + 1) {
                roundRect(inactiveStart - x0, 0f, w - inactiveStart, track, inner, outer, outer, inner)
            }
            circle(cs.primary, w - outer, h / 2, 2.dp.toPx().toFloat())
        }
        drawVector(cs.primary, (hx - handle / 2).toInt(), 0, handle.toInt() + 1, h.toInt()) {
            roundRect(0f, 0f, handle, h, handle / 2)
        }
    }
}

/** Material's indeterminate circular progress indicator, at 10 frames a second (the model needs the CPU more). */
@Composable
fun CircularProgressIndicator(modifier: Modifier = Modifier, size: Dp = 24.dp, stroke: Dp = 3.dp, color: Color = MaterialTheme.colors.primary) {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(100); t += 100 } }
    Canvas(modifier.size(size)) {
        val c = this.size.width / 2
        val sw = stroke.toPx().toFloat()
        val phase = (t % 1333) / 1333.0
        val sweep = 20f + 250f * (0.5f - 0.5f * cos(2 * PI * phase).toFloat())
        val start = (t * 0.30f + (t / 1333) * 125f) % 360f
        drawVector(color) { arc(c, c, c - sw / 2, sw, start, sweep, round = true) }
    }
}

/** Material's small top app bar: 64 dp, title in titleLarge (and a [subtitle]), [actions] at the end. */
@Composable
fun TopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    color: Color = MaterialTheme.colors.surfaceContainer,
    actions: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colors
    Row(
        modifier.fillMaxWidth().height(64.dp).background(color).padding(start = if (navigation != null) 4.dp else 16.dp, end = 4.dp),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
    ) {
        if (navigation != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { navigation() }
        Column(Modifier.weight(1f)) {
            Text(title, style = Typography.titleLarge, color = cs.onSurface, maxLines = 1)
            if (subtitle != null) Text(subtitle, style = Typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
        }
        actions()
    }
}

/** Material's primary tabs: equal widths, 48 dp, a 3 dp indicator under the selected title. */
@Composable
fun TabRow(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            titles.forEachIndexed { i, title ->
                val interaction = remember { Interaction() }
                val on = i == selected
                val fg = if (on) cs.primary else cs.onSurfaceVariant
                Box(
                    Modifier.weight(1f).height(48.dp).indication(interaction, 0.dp, fg, cs.secondary)
                        .clickable(true, interaction) { onSelect(i) }
                        .drawBehind {
                            if (!on) return@drawBehind
                            val tw = measureWidth(title, Typography.titleSmall).toInt().coerceIn(24.dp.toPx(), size.width.toInt())
                            val ih = 3.dp.toPx()
                            drawVector(cs.primary, (size.width.toInt() - tw) / 2, size.height.toInt() - ih, tw, ih) {
                                roundRect(0f, 0f, tw.toFloat(), ih.toFloat(), ih.toFloat(), ih.toFloat(), 0f, 0f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(title, style = Typography.titleSmall, color = fg, maxLines = 1) }
            }
        }
        HorizontalDivider()
    }
}

/** A filled text field showing [value] (no editing: it is for display), [label] above it. */
@Composable
fun TextFieldDisplay(label: String?, value: String?, modifier: Modifier = Modifier, placeholder: String = "") {
    val cs = MaterialTheme.colors
    Column(
        modifier.fillMaxWidth().heightIn(min = 56.dp).drawBehind {
            val r = 4.dp.toPx().toFloat()
            drawVector(cs.surfaceContainerHighest) { roundRect(0f, 0f, size.width, size.height, r, r, 0f, 0f) }
            drawRect(cs.onSurfaceVariant, ui.Offset(0f, size.height - 1.dp.toPx()), ui.Size(size.width, 1.dp.toPx().toFloat()))
        }.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (label != null) Text(label, style = Typography.bodySmall, color = cs.onSurfaceVariant)
        val text = value?.takeIf { it.isNotEmpty() }
        Text(text ?: placeholder, style = Typography.bodyLarge, color = if (text == null) cs.onSurfaceVariant else cs.onSurface)
    }
}
