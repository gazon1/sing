package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The task feature draws its icons from the icon set, never from typed glyphs.
 *
 * ## Why this gate exists
 *
 * The subtasks section shipped a checklist header typed as a literal emoji —
 * `Text("\uD83D\uDCCB", …)` — which is a glyph the app cannot tint, cannot scale
 * with the theme, and cannot draw in the user's own colour. It is also invisible
 * to a reader of the source: the escape reads as an escape, not as a picture.
 *
 * Two forms have to be caught, because the regression came back in both:
 *
 * | Form | Example |
 * |---|---|
 * | a literal glyph in the source | `"📋"` |
 * | an escaped one | `"\uD83D\uDCCB"` |
 *
 * The escaped form is the nastier of the two: it survives a search for emoji
 * characters, and it hides a pictograph behind four hex digits.
 *
 * ## What is checked
 *
 * Every production file under `feature/tasks` is read with its comments
 * stripped, so a mention of a glyph in a KDoc is not a violation — only code
 * that would paint one is. A violation is any code point in the emoji ranges,
 * whether it is written literally or as a `\uXXXX` escape (surrogate pairs
 * included).
 *
 * ## The ranges
 *
 * `1F300–1FAFF` is the pictographic plane — the emoji proper. `2600–27BF` is
 * the dingbat block, which carries the pictographs people reach for as
 * icons — check marks, warning signs, flags. Both are excluded here: an
 * affordance drawn in the app's own icon set is one the theme can tint and
 * the user can read, and a dingbat is neither.
 */
@Tag("fast")
class NoEmojiInTaskIconsTest {
    @Test
    fun `no production file in the task feature paints an emoji glyph`() {
        val violations =
            SourceScan.productionSources
                .filter {
                    it.file.path
                        .replace('\\', '/')
                        .contains("/feature/tasks/")
                }.flatMap { source ->
                    emojiCodePoints(source.text).map { codePoint ->
                        "${source.file.name}: ${describe(codePoint)}"
                    }
                }.distinct()

        assertTrue(
            violations.isEmpty(),
            "Emoji glyphs in the task feature (draw icons from the icon set instead):\n" +
                violations.joinToString("\n"),
        )
    }

    /**
     * Every emoji code point in [text], literal or escaped.
     *
     * Escapes are decoded first — including surrogate pairs, because the emoji
     * that matters here is written as two of them — and the decoded code points
     * are checked against the same ranges as the literal ones.
     */
    private fun emojiCodePoints(text: String): List<Int> {
        val codePoints = codePointsOf(text)
        return codePoints.filter { codePoint -> EMOJI_RANGES.any { codePoint in it } }
    }

    /**
     * Every code point in [text], escapes decoded.
     *
     * A code point is not a character: an emoji outside the BMP is a surrogate
     * *pair*, two UTF-16 units standing for one code point. Working in characters
     * would see two halves of a pictograph where the pictograph is, and neither
     * half is in an emoji range — so the check would pass over exactly the glyph
     * it exists to catch.
     */
    private fun codePointsOf(text: String): List<Int> {
        val out = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            val escape = escapeAt(text, index)
            if (escape == null) {
                val codePoint = text.codePointAt(index)
                out.add(codePoint)
                index += Character.charCount(codePoint)
                continue
            }
            index += 6
            if (escape.value in 0xD800..0xDBFF) {
                // A high surrogate: the low half follows as a second escape.
                val low = escapeAt(text, index)
                if (low != null && low.value in 0xDC00..0xDFFF) {
                    index += 6
                    out.add(0x10000 + ((escape.value - 0xD800) shl 10) + (low.value - 0xDC00))
                } else {
                    out.add(escape.value)
                }
            } else {
                out.add(escape.value)
            }
        }
        return out
    }

    /** The code point named by the `\uXXXX` escape at [index], or null. */
    private fun escapeAt(
        text: String,
        index: Int,
    ): Escape? {
        if (index + 6 > text.length) return null
        if (text[index] != '\\' || text[index + 1] != 'u') return null
        val digits = text.substring(index + 2, index + 6)
        val value = digits.toIntOrNull(16) ?: return null
        return Escape(value)
    }

    private data class Escape(val value: Int)

    private fun describe(codePoint: Int): String {
        val hex = "U+" + codePoint.toString(16).uppercase().padStart(4, '0')
        return "$hex ${Character.getType(codePoint)}"
    }

    private companion object {
        val EMOJI_RANGES: List<IntRange> =
            listOf(
                0x2600..0x27BF, // Dingbats: check marks, warning signs, flags.
                0x2B00..0x2BFF, // Miscellaneous symbols and arrows.
                0x1F300..0x1FAFF, // Pictographs: the emoji plane.
                0xFE0F..0xFE0F, // Variation selector-16, the emoji presentation switch.
            )
    }
}
