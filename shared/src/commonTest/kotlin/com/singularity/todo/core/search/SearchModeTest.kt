package com.singularity.todo.core.search

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The mode, and what it can ask.
 *
 * The mode is the escape-hatch of the word-prefix rule: the word mode finds a
 * word by its beginning, the contains mode finds the term wherever it occurs.
 * The mode also says what the index can do — and the answer for the contains
 * mode is always "nothing", because the index stores words and cannot say
 * whether a term occurs in the middle of one.
 *
 * The decision about the index belongs to the rule, so it is the rule that
 * answers it: `FreeTextRule.plan(mode)`. This test asks the rule in both
 * modes, because the question is the rule's and the mode is one of its
 * arguments.
 *
 * See `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`
 * and `docs/decisions/2026-10-23-the-mode-is-a-property-of-every-search-surface.md`.
 */
@Tag("fast")
class SearchModeTest {

    @Test
    fun `the word mode finds a word by its beginning`() {
        assertTrue(SearchMode.Word.matches("architecture", "arch"))
        assertFalse(SearchMode.Word.matches("airport", "port"))
    }

    @Test
    fun `the contains mode finds the term wherever it occurs`() {
        assertTrue(SearchMode.Contains.matches("airport", "port"))
        assertTrue(SearchMode.Contains.matches("Airport transfer", "port"))
        assertFalse(SearchMode.Contains.matches("architecture", "port"))
    }

    @Test
    fun `the contains mode ignores the case`() {
        assertTrue(SearchMode.Contains.matches("Airport transfer", "PORT"))
        assertTrue(SearchMode.Word.matches("Architecture", "arch"))
    }

    @Test
    fun `the contains mode folds the diacritics the way the index folds them`() {
        // F7 — the two modes answer the same question the same way about the
        // same text. The surfaces that filter the rows they hold use the
        // contains mode, so this is what "cafe" finds in a project's name.
        assertTrue(SearchMode.Contains.matches("Café transfer", "cafe"))
        assertTrue(SearchMode.Word.matches("Café transfer", "cafe"))
        assertFalse(SearchMode.Contains.matches("Café transfer", "caffe"))
    }

    @Test
    fun `a record is matched on any of its surfaces`() {
        assertTrue(SearchMode.Word.matchesAny(listOf("Trip", "Airport transfer"), "air"))
        assertFalse(SearchMode.Word.matchesAny(listOf("Trip", "Airport transfer"), "port"))
        assertTrue(SearchMode.Contains.matchesAny(listOf("Trip", "Airport transfer"), "port"))
    }

    @Test
    fun `the word mode narrows the rule and the index answers it`() {
        val plan = rule("port").plan(SearchMode.Word)

        assertIs<TextMatchPlan.Narrowed>(plan)
    }

    @Test
    fun `the contains mode never narrows the rule`() {
        val plan = rule("port").plan(SearchMode.Contains)

        assertEquals(TextMatchPlan.BroadScan, plan)
        assertEquals(
            TextMatchPlan.BroadScan,
            rule("port", negated = true).plan(SearchMode.Contains),
            "the index has no answer for a term that occurs anywhere — not even for a negated one",
        )
    }

    @Test
    fun `the rule is the one that decides, in the mode it is given`() {
        // The decision used to be made twice: once in the rule for the word
        // mode, and once in `SearchMode.plan(rule)` for the contains mode. Two
        // places that answer one question are two answers — and this test is
        // the one that says the rule answers it.
        //
        // The same rule, asked in two modes, gets two answers; and the answer
        // comes with the question, not beside it.
        val word = rule("port")
        val contains = rule("port")

        assertIs<TextMatchPlan.Narrowed>(word.plan(SearchMode.Word))
        assertEquals(TextMatchPlan.BroadScan, contains.plan(SearchMode.Contains))
        assertEquals(
            TextMatchQuery(terms = listOf("port")),
            word.indexQuery(SearchMode.Word),
            "in the word mode the index is asked about the term",
        )
        assertEquals(
            TextMatchQuery.EMPTY,
            word.indexQuery(SearchMode.Contains),
            "in the contains mode the index is asked nothing — it cannot answer it",
        )
    }

    @Test
    fun `the contains mode asks the index nothing`() {
        // An empty question finds nothing — the caller reads the rows itself.
        assertEquals(TextMatchQuery.EMPTY, rule("port").indexQuery(SearchMode.Contains))
    }

    private fun rule(text: String, negated: Boolean = false): FreeTextRule =
        FreeTextRule(terms = listOf(FreeTextRule.Term(text = text, negated = negated)))
}
