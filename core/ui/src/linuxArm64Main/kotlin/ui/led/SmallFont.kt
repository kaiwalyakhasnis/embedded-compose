package ui.led

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ui.Color
import ui.DrawScope
import ui.IntSize
import ui.Layout
import ui.MeasurePolicy
import ui.MeasureResult
import ui.Modifier
import ui.Offset

// A 3x5 pixel font, drawn here pixel by pixel, for when the board's font is
// too big: "12:34:56" fits on a 32x8 strip. Capitals, digits and some
// punctuation; small letters are drawn as capitals, anything else as '?'.
// Characters are 4x6 cells: one blank column and row.

private val GLYPHS: Map<Char, LedBitmap> = mapOf(
    ' ' to "... ... ... ... ...",
    '0' to "### #.# #.# #.# ###", '1' to ".#. ##. .#. .#. ###", '2' to "### ..# ### #.. ###",
    '3' to "### ..# .## ..# ###", '4' to "#.# #.# ### ..# ..#", '5' to "### #.. ### ..# ###",
    '6' to "### #.. ### #.# ###", '7' to "### ..# .#. .#. .#.", '8' to "### #.# ### #.# ###",
    '9' to "### #.# ### ..# ###",
    'A' to ".#. #.# ### #.# #.#", 'B' to "##. #.# ##. #.# ##.", 'C' to ".## #.. #.. #.. .##",
    'D' to "##. #.# #.# #.# ##.", 'E' to "### #.. ##. #.. ###", 'F' to "### #.. ##. #.. #..",
    'G' to ".## #.. #.# #.# .##", 'H' to "#.# #.# ### #.# #.#", 'I' to "### .#. .#. .#. ###",
    'J' to "..# ..# ..# #.# .#.", 'K' to "#.# #.# ##. #.# #.#", 'L' to "#.. #.. #.. #.. ###",
    'M' to "#.# ### ### #.# #.#", 'N' to "##. #.# #.# #.# #.#", 'O' to ".#. #.# #.# #.# .#.",
    'P' to "##. #.# ##. #.. #..", 'Q' to ".#. #.# #.# ##. .##", 'R' to "##. #.# ##. #.# #.#",
    'S' to ".## #.. .#. ..# ##.", 'T' to "### .#. .#. .#. .#.", 'U' to "#.# #.# #.# #.# ###",
    'V' to "#.# #.# #.# .#. .#.", 'W' to "#.# #.# ### ### #.#", 'X' to "#.# #.# .#. #.# #.#",
    'Y' to "#.# #.# .#. .#. .#.", 'Z' to "### ..# .#. #.. ###",
    ':' to "... .#. ... .#. ...", '.' to "... ... ... ... .#.", ',' to "... ... ... .#. #..",
    '-' to "... ... ### ... ...", '+' to "... .#. ### .#. ...", '=' to "... ### ... ### ...",
    '/' to "..# ..# .#. #.. #..", '%' to "#.. ..# .#. #.. ..#", '!' to ".#. .#. .#. ... .#.",
    '?' to "##. ..# .#. ... .#.", '\'' to ".#. .#. ... ... ...", '"' to "#.# #.# ... ... ...",
    '(' to "..# .#. .#. .#. ..#", ')' to "#.. .#. .#. .#. #..", '<' to "..# .#. #.. .#. ..#",
    '>' to "#.. .#. ..# .#. #..", '*' to "... #.# .#. #.# ...", '#' to "#.# ### #.# ### #.#",
    '_' to "... ... ... ... ###", '°' to "### #.# ### ... ...",
).mapValues { ledBitmap(it.value) }

private fun glyph(c: Char) = GLYPHS[c] ?: GLYPHS[c.uppercaseChar()] ?: GLYPHS.getValue('?')

/** The size of [text] in the 3x5 font: 4 x 6 pixels a character at scale 1, lines split at '\n'. */
fun measureSmallText(text: String, scale: Int = 1): IntSize {
    val lines = text.split('\n')
    return IntSize(lines.maxOf { it.length } * 4 * scale, lines.size * 6 * scale)
}

/** [text] in the 3x5 font with its top-left at [topLeft], cut at the edges of this box: it can scroll smoothly anywhere. */
fun DrawScope.drawSmallText(text: String, topLeft: Offset, color: Color, scale: Int = 1) {
    var y = topLeft.y
    for (line in text.split('\n')) {
        var x = topLeft.x
        for (c in line) {
            if (x >= size.width) break
            if (x + 4 * scale > 0) drawLedBitmap(glyph(c), Offset(x, y), color, scale)
            x += 4 * scale
        }
        y += 6 * scale
    }
}

/** [text] in the 3x5 font, as big as it is. */
@Composable
fun SmallText(text: String, modifier: Modifier = Modifier, color: Color = Color.White, scale: Int = 1) {
    val policy = remember(text, scale) {
        MeasurePolicy { _, c ->
            val s = measureSmallText(text, scale)
            MeasureResult(c.constrainWidth(s.width), c.constrainHeight(s.height))
        }
    }
    Layout(modifier, policy, drawContent = { drawSmallText(text, Offset.Zero, color, scale) })
}
