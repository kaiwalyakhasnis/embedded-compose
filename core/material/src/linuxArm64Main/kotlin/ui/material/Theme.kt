package ui.material

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import ui.Color
import ui.Theme
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Material 3's color roles. */
class ColorScheme(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val error: Color, val onError: Color, val errorContainer: Color, val onErrorContainer: Color,
    val surface: Color, val onSurface: Color, val onSurfaceVariant: Color,
    val surfaceContainerLowest: Color, val surfaceContainerLow: Color, val surfaceContainer: Color,
    val surfaceContainerHigh: Color, val surfaceContainerHighest: Color,
    val outline: Color, val outlineVariant: Color,
    val inverseSurface: Color, val inverseOnSurface: Color, val inversePrimary: Color,
)

private fun c(rgb: Long) = Color(0xff000000 or rgb)

/** Material 3's dark scheme: the baseline one (seed #6750A4), or tones of [seed]. */
fun darkColorScheme(seed: Color? = null): ColorScheme {
    if (seed == null) return ColorScheme(
        c(0xD0BCFF), c(0x381E72), c(0x4F378B), c(0xEADDFF),
        c(0xCCC2DC), c(0x332D41), c(0x4A4458), c(0xE8DEF8),
        c(0xEFB8C8), c(0x492532), c(0x633B48), c(0xFFD8E4),
        c(0xF2B8B5), c(0x601410), c(0x8C1D18), c(0xF9DEDC),
        c(0x141218), c(0xE6E0E9), c(0xCAC4D0),
        c(0x0F0D13), c(0x1D1B20), c(0x211F26), c(0x2B2930), c(0x36343B),
        c(0x938F99), c(0x49454F),
        c(0xE6E0E9), c(0x322F35), c(0x6750A4),
    )
    val p = Palettes(seed)
    return ColorScheme(
        p.primary(80), p.primary(20), p.primary(30), p.primary(90),
        p.secondary(80), p.secondary(20), p.secondary(30), p.secondary(90),
        p.tertiary(80), p.tertiary(20), p.tertiary(30), p.tertiary(90),
        c(0xF2B8B5), c(0x601410), c(0x8C1D18), c(0xF9DEDC),
        p.neutral(6), p.neutral(90), p.variant(80),
        p.neutral(4), p.neutral(10), p.neutral(12), p.neutral(17), p.neutral(22),
        p.variant(60), p.variant(30),
        p.neutral(90), p.neutral(20), p.primary(40),
    )
}

/** Material 3's light scheme: the baseline one (seed #6750A4), or tones of [seed]. */
fun lightColorScheme(seed: Color? = null): ColorScheme {
    if (seed == null) return ColorScheme(
        c(0x6750A4), c(0xFFFFFF), c(0xEADDFF), c(0x4F378B),
        c(0x625B71), c(0xFFFFFF), c(0xE8DEF8), c(0x4A4458),
        c(0x7D5260), c(0xFFFFFF), c(0xFFD8E4), c(0x633B48),
        c(0xB3261E), c(0xFFFFFF), c(0xF9DEDC), c(0x8C1D18),
        c(0xFEF7FF), c(0x1D1B20), c(0x49454F),
        c(0xFFFFFF), c(0xF7F2FA), c(0xF3EDF7), c(0xECE6F0), c(0xE6E0E9),
        c(0x79747E), c(0xCAC4D0),
        c(0x322F35), c(0xF5EFF7), c(0xD0BCFF),
    )
    val p = Palettes(seed)
    return ColorScheme(
        p.primary(40), p.primary(100), p.primary(90), p.primary(30),
        p.secondary(40), p.secondary(100), p.secondary(90), p.secondary(30),
        p.tertiary(40), p.tertiary(100), p.tertiary(90), p.tertiary(30),
        c(0xB3261E), c(0xFFFFFF), c(0xF9DEDC), c(0x8C1D18),
        p.neutral(98), p.neutral(10), p.variant(30),
        p.neutral(100), p.neutral(96), p.neutral(94), p.neutral(92), p.neutral(90),
        p.variant(50), p.variant(80),
        p.neutral(20), p.neutral(95), p.primary(80),
    )
}

/**
 * Tonal palettes of a seed color, like Material's (tone = L*), with CIELAB
 * hue and chroma standing in for HCT's: close, not identical.
 */
private class Palettes(seed: Color) {
    private val hue: Double
    private val chroma: Double

    init {
        val (_, a, b) = lab(seed)
        hue = atan2(b, a)
        chroma = hypot(a, b)
    }

    fun primary(tone: Int) = tone(tone, hue, maxOf(chroma, 45.0))
    fun secondary(tone: Int) = tone(tone, hue, 16.0)
    fun tertiary(tone: Int) = tone(tone, hue + PI_3, 24.0)
    fun neutral(tone: Int) = tone(tone, hue, 5.0)
    fun variant(tone: Int) = tone(tone, hue, 9.0)

    /** L* = [tone], at [h], with as much of [c] as sRGB allows. */
    private fun tone(tone: Int, h: Double, c: Double): Color {
        var lo = 0.0; var hi = c
        var best = fromLab(tone.toDouble(), 0.0, 0.0)!!
        repeat(14) {
            val mid = (lo + hi) / 2
            val rgb = fromLab(tone.toDouble(), mid * cos(h), mid * sin(h))
            if (rgb != null) { best = rgb; lo = mid } else hi = mid
        }
        fromLab(tone.toDouble(), c * cos(h), c * sin(h))?.let { best = it }
        return best
    }

    companion object {
        const val PI_3 = 1.0471975511965976
    }
}

private fun toLinear(v: Int): Double = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
private fun toSrgb(v: Double): Int = (255 * (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055)).roundToInt()

private fun lab(c: Color): Triple<Double, Double, Double> {
    val r = toLinear(c.red); val g = toLinear(c.green); val b = toLinear(c.blue)
    val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
}

/** The sRGB color of a CIELAB one, or null outside sRGB. */
private fun fromLab(l: Double, a: Double, b: Double): Color? {
    val fy = (l + 16) / 116
    val fx = fy + a / 500
    val fz = fy - b / 200
    fun inv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
    val x = inv(fx) * 0.95047; val y = inv(fy); val z = inv(fz) * 1.08883
    val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
    val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
    val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z
    val e = 1e-4
    if (r < -e || r > 1 + e || g < -e || g > 1 + e || bl < -e || bl > 1 + e) return null
    fun ch(v: Double) = toSrgb(v.coerceIn(0.0, 1.0)).coerceIn(0, 255)
    return Color((0xff shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(bl))
}

/** A text style of Material's type scale: size and line height in sp (= dp here), letter spacing in sp. */
class TextStyle(val size: Float, val lineHeight: Float, val medium: Boolean = false, val letterSpacing: Float = 0f)

/** Material 3's type scale. */
object Typography {
    val displaySmall = TextStyle(36f, 44f)
    val headlineLarge = TextStyle(32f, 40f)
    val headlineMedium = TextStyle(28f, 36f)
    val headlineSmall = TextStyle(24f, 32f)
    val titleLarge = TextStyle(22f, 28f)
    val titleMedium = TextStyle(16f, 24f, true, 0.15f)
    val titleSmall = TextStyle(14f, 20f, true, 0.1f)
    val bodyLarge = TextStyle(16f, 24f, false, 0.5f)
    val bodyMedium = TextStyle(14f, 20f, false, 0.25f)
    val bodySmall = TextStyle(12f, 16f, false, 0.4f)
    val labelLarge = TextStyle(14f, 20f, true, 0.1f)
    val labelMedium = TextStyle(12f, 16f, true, 0.5f)
    val labelSmall = TextStyle(11f, 16f, true, 0.5f)
}

val LocalColors = staticCompositionLocalOf { darkColorScheme() }
/** The color of text and icons on the surface around them. */
val LocalContentColor = compositionLocalOf { Color(0xffE6E0E9) }
val LocalTextStyle = compositionLocalOf { Typography.bodyLarge }

object MaterialTheme {
    val colors: ColorScheme @Composable get() = LocalColors.current
}

/**
 * Material 3 colors for [content]. Its components draw their own focus
 * indicator (Material's: a 3 dp ring in secondary, 2 dp outside), so the
 * toolkit's focus ring is switched off.
 */
@Composable
fun MaterialTheme(colors: ColorScheme = darkColorScheme(), content: @Composable () -> Unit) {
    remember { Theme.focus = Color.Transparent }
    CompositionLocalProvider(LocalColors provides colors, LocalContentColor provides colors.onSurface, content = content)
}
