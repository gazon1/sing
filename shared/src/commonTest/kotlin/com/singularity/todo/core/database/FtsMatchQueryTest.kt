package com.singularity.todo.core.database

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
 */
@Tag("fast")
class FtsMatchQueryTest {

    @Test
    fun `a term becomes a prefix lookup`() {
        assertEquals("quarter*", FtsMatchQuery.expression(TextMatchQuery(listOf("quarter"))))
    }

    @Test
    fun `several terms are a conjunction`() {
        assertEquals(
            "quarter* report*",
            FtsMatchQuery.expression(TextMatchQuery(listOf("quarter", "report"))),
        )
    }

    @Test
    fun `alternatives are a disjunction`() {
        assertEquals(
            "quarter* OR annual*",
            FtsMatchQuery.expression(TextMatchQuery(listOf("quarter", "annual"), combinesWithOr = true)),
        )
    }

    @Test
    fun `a phrase asks for both of its words`() {
        assertEquals(
            "quarterly* report*",
            FtsMatchQuery.expression(TextMatchQuery(listOf("quarterly report"))),
        )
    }

    @Test
    fun `punctuation cannot become syntax`() {
        // A quote, a parenthesis or a star typed by the user is not syntax: the
        // expression is built from tokens only.
        val expression = FtsMatchQuery.expression(TextMatchQuery(listOf("\" OR * ( )")))

        assertEquals("or*", expression)
    }

    @Test
    fun `a keyword is a term and not an operator`() {
        // `not` is an FTS operator, but a prefixed token is always a term.
        assertEquals("not*", FtsMatchQuery.expression(TextMatchQuery(listOf("not"))))
    }

    @Test
    fun `a query with no searchable term has no expression`() {
        assertNull(FtsMatchQuery.expression(TextMatchQuery()))
        assertNull(FtsMatchQuery.expression(TextMatchQuery(listOf("!!!"))))
    }
}
