package com.singularity.todo.core.search

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The word-prefix rule of the search — the semantics the full-text index
 * implements.
 *
 * The matcher and the index have to agree: the index produces the candidates,
 * this matcher decides among them. If the two disagreed, a record could be
 * found by one and not by the other, and the search would answer a question
 * nobody asked.
 */
@Tag("fast")
class TextMatchTest {

    @Test
    fun `a term matches the word that starts with it`() {
        assertTrue(TextMatch.matches("Write the quarterly report", "quarterly"))
        assertTrue(TextMatch.matches("Write the quarterly report", "quarter"))
        assertTrue(TextMatch.matches("Write the quarterly report", "quar"))
    }

    @Test
    fun `a term does not match a word that does not start with it`() {
        // The word-prefix rule, and not "contains": the index stores words, so
        // it can only find the beginning of one.
        assertFalse(TextMatch.matches("airport logistics", "port"))
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(TextMatch.matches("Quarterly Report", "quarterly"))
        assertTrue(TextMatch.matches("quarterly report", "QUARTERLY"))
    }

    @Test
    fun `a phrase matches when its words follow each other`() {
        assertTrue(TextMatch.matches("Write the quarterly report today", "quarterly report"))
        assertFalse(TextMatch.matches("Write the report of the quarter", "quarterly report"))
    }

    @Test
    fun `the last word of a phrase may be unfinished`() {
        assertTrue(TextMatch.matches("Write the quarterly report today", "quarterly rep"))
        assertFalse(TextMatch.matches("Write the quarterly report today", "quarterly quar"))
    }

    @Test
    fun `a term with no letters or digits matches nothing`() {
        assertFalse(TextMatch.matches("Buy milk", "!!!"))
        assertFalse(TextMatch.matches("Buy milk", "   "))
    }

    @Test
    fun `an empty text matches nothing`() {
        assertFalse(TextMatch.matches("", "milk"))
        assertFalse(TextMatch.matches(null, "milk"))
    }

    @Test
    fun `letters outside ascii are token characters`() {
        // The index is tokenized with `unicode61`, which keeps letters of every
        // script. A matcher that only knew ascii would find nothing in a
        // Russian title.
        assertTrue(TextMatch.matches("Купить молоко", "мол"))
        assertTrue(TextMatch.matches("Купить молоко", "МОЛОКО"))
    }

    @Test
    fun `the diacritics are folded the way the index folds them`() {
        // The index folds "café" to "cafe", so the rule has to fold it too: the
        // two halves of one rule have to answer the same question the same way.
        assertTrue(TextMatch.matches("Café au lait", "cafe"))
        assertTrue(TextMatch.matches("café au lait", "café"))
        assertTrue(TextMatch.matches("Café au lait", "CAFÉ"))
    }

    @Test
    fun `a letter without a single base letter is not folded`() {
        // The index folds a character to one letter or not at all: "ß" is not
        // "ss" and "æ" is not "ae". A rule that folded them would answer a
        // question the index answers differently.
        assertFalse(TextMatch.matches("straße", "strasse"))
        assertTrue(TextMatch.matches("straße", "straße"))
    }

    @Test
    fun `the contains rule folds the diacritics too`() {
        // The two modes are two questions about the same text, and they have to
        // find the same text: a mode that found less would be a trapdoor.
        assertTrue(TextMatch.contains("Café au lait", "cafe"))
        assertTrue(TextMatch.contains("Café au lait", "Lait"))
        assertFalse(TextMatch.contains("Café au lait", "milk"))
    }

    @Test
    fun `matchesAny answers for a choice of surfaces`() {
        assertTrue(TextMatch.matchesAny(listOf("Title", "Description"), "desc"))
        assertFalse(TextMatch.matchesAny(listOf("Title", "Description"), "missing"))
    }

    @Test
    fun `tokens are runs of letters and digits`() {
        assertEquals(listOf("buy", "milk"), TextMatch.tokens("Buy milk!"))
        assertEquals(listOf("task42", "x"), TextMatch.tokens("task42_x"))
        assertEquals(emptyList(), TextMatch.tokens("---"))
    }
}
