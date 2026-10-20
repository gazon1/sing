package com.singularity.todo.feature.search.query

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The free-text rule, on its own.
 *
 * The rule is what every kind of record is matched against — tasks, notes, projects
 * and tags — so it is asserted here once, in the place that owns it, rather than
 * four times in the use case. The per-kind coverage is in
 * [com.singularity.todo.feature.search.SearchFreeTextRuleTest].
 */
@Tag("fast")
class FreeTextRuleTest {

    @Test
    fun `a rule with no terms selects nothing`() {
        val rule = FreeTextRule()

        assertTrue(rule.isEmpty)
        assertFalse(rule.selects { true }, "a query with no free text has no free-text part to match")
    }

    @Test
    fun `every term has to match`() {
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha"), FreeTextRule.Term("beta")))

        assertTrue(rule.selects { it == "alpha" || it == "beta" })
        assertFalse(rule.selects { it == "alpha" }, "a term that does not match rules the record out")
        assertFalse(rule.selects { it == "beta" }, "a term that does not match rules the record out")
        assertFalse(rule.selects { false })
    }

    @Test
    fun `any term may match when the query makes them alternatives`() {
        val rule = FreeTextRule(
            terms = listOf(FreeTextRule.Term("alpha"), FreeTextRule.Term("beta")),
            combinesWithOr = true,
        )

        assertTrue(rule.selects { it == "alpha" })
        assertTrue(rule.selects { it == "beta" })
        assertFalse(rule.selects { false })
    }

    @Test
    fun `a negated term has not to match`() {
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha", negated = true)))

        assertFalse(rule.selects { it == "alpha" }, "the negated term is present, so the record is ruled out")
        assertTrue(rule.selects { it == "beta" })
    }

    @Test
    fun `a negated term is a requirement in a conjunction`() {
        val rule = FreeTextRule(
            terms = listOf(FreeTextRule.Term("alpha"), FreeTextRule.Term("beta", negated = true)),
        )

        assertTrue(rule.selects { it == "alpha" })
        assertFalse(rule.selects { it == "alpha" || it == "beta" }, "beta is present, so the record is ruled out")
    }

    @Test
    fun `the terms are kept in the order the query states them`() {
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha"), FreeTextRule.Term("beta")))

        assertEquals(listOf("alpha", "beta"), rule.terms.map { it.text })
        assertEquals(listOf(false, false), rule.terms.map { it.negated })
    }
}
