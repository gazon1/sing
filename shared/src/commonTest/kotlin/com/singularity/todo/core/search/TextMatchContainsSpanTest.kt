package com.singularity.todo.core.search

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the contains rule says the term is.
 *
 * The contains mode has no index to ask where a match is — the rows are read
 * and matched in memory — so the position of the match has to be found in the
 * text itself, with the same folding the rule folded with.
 *
 * The positions have to be the characters of the original text: the fold
 * replaces every character with exactly one character, so a position in the
 * folded text is the same position in the original one. That is what makes
 * "cafe" in "Café au lait" the span of the four characters a person sees.
 */
@Tag("fast")
class TextMatchContainsSpanTest {
    @Test
    fun `a term at the beginning of the text is found`() {
        assertEquals(0..3, TextMatch.containsSpan("milk", "mil"))
    }

    @Test
    fun `a term in the middle of the text is found`() {
        assertEquals(4..7, TextMatch.containsSpan("Buy milk", "milk"))
    }

    @Test
    fun `the span is measured in the characters of the original text`() {
        assertEquals(0..3, TextMatch.containsSpan("Café au lait", "cafe"))
    }

    @Test
    fun `the span ignores the case`() {
        assertEquals(0..3, TextMatch.containsSpan("MILK", "milk"))
    }

    @Test
    fun `a term that is not in the text has no span`() {
        assertNull(TextMatch.containsSpan("Buy milk", "port"))
    }

    @Test
    fun `an empty term has no span`() {
        assertNull(TextMatch.containsSpan("Buy milk", ""))
    }
}
