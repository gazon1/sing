package com.singularity.todo.core.database

import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatchRange
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The position of a match, read out of the index's answer.
 *
 * ## Why the bytes are the interesting part
 *
 * SQLite counts the bytes of the UTF-8 text, and a Kotlin string counts UTF-16
 * code units. The two agree for ASCII and disagree for everything else, and the
 * disagreement is invisible until a title carries an accent — and then the
 * highlight lands on the wrong word.
 *
 * The offsets the index reports are always the *original* text: a search for
 * "cafe" finds "Café au lait" and reports five bytes, because "Café" is five
 * bytes in the text even though the index stores it folded as "cafe".
 *
 * ## What the answer looks like
 *
 * `offsets()` answers with a flat list of integers, four per matched token: the
 * column, the number of the term, the byte offset and the byte length.
 */
@Tag("fast")
class FtsOffsetsTest {

    private val title = FtsColumn(SearchColumn.TITLE, "Architecture of the house")
    private val description = FtsColumn(SearchColumn.DESCRIPTION, "the architecture plan")

    @Test
    fun `a byte offset in an ASCII text is the character offset`() {
        val ranges = FtsOffsets.ranges("0 0 0 12", listOf(title, description))

        assertEquals(listOf(TextMatchRange(SearchColumn.TITLE, 0, 12)), ranges)
    }

    @Test
    fun `a byte length in an ASCII text is the character length`() {
        // "of" stands at byte 13 in "Architecture of the house".
        val ranges = FtsOffsets.ranges("0 0 13 2", listOf(title, description))

        assertEquals(listOf(TextMatchRange(SearchColumn.TITLE, 13, 15)), ranges)
        assertEquals("of", title.text.substring(ranges.single().start, ranges.single().end))
    }

    @Test
    fun `an accented character is two bytes and one character`() {
        // "Café au lait" — the accent is two bytes, so the token is five bytes
        // long and four characters.
        val text = FtsColumn(SearchColumn.TITLE, "Café au lait")

        val ranges = FtsOffsets.ranges("0 0 0 5", listOf(text))

        assertEquals(listOf(TextMatchRange(SearchColumn.TITLE, 0, 4)), ranges)
        assertEquals("Café", text.text.substring(ranges.single().start, ranges.single().end))
    }

    @Test
    fun `an offset after an accented character counts the character, not the byte`() {
        // "naïve plan" — "plan" starts at byte 7 and at character 6.
        val text = FtsColumn(SearchColumn.TITLE, "naïve plan")

        val ranges = FtsOffsets.ranges("0 0 7 4", listOf(text))

        assertEquals(listOf(TextMatchRange(SearchColumn.TITLE, 6, 10)), ranges)
        assertEquals("plan", text.text.substring(ranges.single().start, ranges.single().end))
    }

    @Test
    fun `an emoji is four bytes and two characters`() {
        // "Run 🏃 now" — the runner is one code point and two UTF-16 units, so
        // "now" starts at byte 9 and at character 7.
        val text = FtsColumn(SearchColumn.TITLE, "Run 🏃 now")

        val ranges = FtsOffsets.ranges("0 0 9 3", listOf(text))

        assertEquals(listOf(TextMatchRange(SearchColumn.TITLE, 7, 10)), ranges)
        assertEquals("now", text.text.substring(ranges.single().start, ranges.single().end))
    }

    @Test
    fun `a match in the description is not a range of the title`() {
        val ranges = FtsOffsets.ranges("1 0 4 12", listOf(title, description))

        assertEquals(listOf(TextMatchRange(SearchColumn.DESCRIPTION, 4, 16)), ranges)
        assertTrue(ranges.none { it.column == SearchColumn.TITLE })
    }

    @Test
    fun `the ranges of both columns are told apart`() {
        val ranges = FtsOffsets.ranges("0 0 0 12 1 0 4 12", listOf(title, description))

        assertEquals(
            listOf(
                TextMatchRange(SearchColumn.TITLE, 0, 12),
                TextMatchRange(SearchColumn.DESCRIPTION, 4, 16),
            ),
            ranges,
        )
    }

    @Test
    fun `an empty answer is no ranges`() {
        assertEquals(emptyList(), FtsOffsets.ranges(null, listOf(title, description)))
        assertEquals(emptyList(), FtsOffsets.ranges("", listOf(title, description)))
    }

    @Test
    fun `half an answer is not an answer`() {
        // A group of four that is not there is not a range that starts at zero.
        assertEquals(emptyList(), FtsOffsets.ranges("0 0 0", listOf(title, description)))
    }

    @Test
    fun `a range that runs past the text is dropped`() {
        val ranges = FtsOffsets.ranges("0 0 900 12", listOf(title, description))

        assertEquals(emptyList(), ranges)
    }
}
