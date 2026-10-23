package com.singularity.todo.core.database

import com.singularity.todo.core.search.FreeTextRule
import com.singularity.todo.core.search.TextMatchQuery
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `MATCH` expression the search builds.
 *
 * The expression is SQL, and the terms of a search are typed by a person. The
 * builder therefore has to do two things: turn a term into something the index
 * can look up, and make sure that nothing a person types can become syntax.
 *
 * The expression is a *phrase*, because that is what the rule asks for: the
 * words of a term stand next to each other, and only the last one may be
 * unfinished. See [FreeTextRule] and
 * `docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`.
 */
@Tag("fast")
class FtsMatchQueryTest {

    @Test
    fun `a term becomes a prefix lookup`() {
        assertEquals("\"quarter*\"", FtsMatchQuery.expression(TextMatchQuery(listOf("quarter"))))
    }

    @Test
    fun `several terms are a conjunction`() {
        assertEquals(
            "\"quarter*\" \"report*\"",
            FtsMatchQuery.expression(TextMatchQuery(listOf("quarter", "report"))),
        )
    }

    @Test
    fun `alternatives are a disjunction`() {
        assertEquals(
            "\"quarter*\" OR \"annual*\"",
            FtsMatchQuery.expression(TextMatchQuery(listOf("quarter", "annual"), combinesWithOr = true)),
        )
    }

    @Test
    fun `a term of several words is one phrase`() {
        // The words stand next to each other, so the expression is one quoted
        // phrase and not a conjunction of two lookups.
        assertEquals(
            "\"quarterly report*\"",
            FtsMatchQuery.expression(TextMatchQuery(listOf("quarterly report"))),
        )
    }

    @Test
    fun `only the last word of a phrase may be unfinished`() {
        // The rule matches the words before the last one exactly, so the star
        // goes on the last word only.
        assertEquals(
            "\"quarterly repo*\"",
            FtsMatchQuery.expression(TextMatchQuery(listOf("quarterly repo"))),
        )
    }

    @Test
    fun `punctuation cannot become syntax`() {
        // A quote, a parenthesis or a star typed by the user is not syntax: the
        // expression is built from tokens only.
        val expression = FtsMatchQuery.expression(TextMatchQuery(listOf("\" OR * ( )")))

        assertEquals("\"or*\"", expression)
    }

    @Test
    fun `a keyword is a term and not an operator`() {
        // `not` is an FTS operator, and a quoted phrase is never syntax: the
        // word is a word.
        assertEquals("\"not*\"", FtsMatchQuery.expression(TextMatchQuery(listOf("not"))))
    }

    @Test
    fun `a query with no searchable term has no expression`() {
        assertNull(FtsMatchQuery.expression(TextMatchQuery()))
        assertNull(FtsMatchQuery.expression(TextMatchQuery(listOf("!!!"))))
    }
}
