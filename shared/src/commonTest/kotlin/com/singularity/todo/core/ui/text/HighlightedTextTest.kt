package com.singularity.todo.core.ui.text

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatchRange
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The matched word is drawn over the word.
 *
 * The span comes from the index, in the characters of the text — so the drawing
 * has nothing to decide. It only has to put the style over the characters the
 * index named, and a range that does not fit the text must not draw a span
 * that runs past the end of it.
 */
@Tag("fast")
class HighlightedTextTest {

    private val style = SpanStyle(fontWeight = FontWeight.Bold)

    @Test
    fun `the span covers the matched characters`() {
        val text = "Architecture of the house"

        val drawn = highlightedText(text, listOf(TextMatchRange(SearchColumn.TITLE, 0, 12)), style)

        assertEquals(1, drawn.spanStyles.size)
        assertEquals(0, drawn.spanStyles.first().start)
        assertEquals(12, drawn.spanStyles.first().end)
        assertEquals("Architecture", drawn.text.substring(0, 12))
    }

    @Test
    fun `the span covers the whole word of a title with an accent`() {
        // The range is in characters, so "Café" is four of them — the span
        // covers the word and stops at the space.
        val text = "Café au lait"

        val drawn = highlightedText(text, listOf(TextMatchRange(SearchColumn.TITLE, 0, 4)), style)

        assertEquals(1, drawn.spanStyles.size)
        assertEquals(0, drawn.spanStyles.first().start)
        assertEquals(4, drawn.spanStyles.first().end)
        assertEquals("Café", drawn.text.substring(0, 4))
    }

    @Test
    fun `no range draws no span`() {
        val drawn = highlightedText("Architecture", emptyList(), style)

        assertTrue(drawn.spanStyles.isEmpty())
    }

    @Test
    fun `a range that runs past the text is drawn over the text it covers`() {
        // The range comes from the index, and the index does not draw. A range
        // that does not fit the text must not draw a span past the end of it.
        val drawn = highlightedText("Architecture", listOf(TextMatchRange(SearchColumn.TITLE, 0, 900)), style)

        assertEquals(1, drawn.spanStyles.size)
        assertEquals(0, drawn.spanStyles.first().start)
        assertEquals(12, drawn.spanStyles.first().end)
    }
}
