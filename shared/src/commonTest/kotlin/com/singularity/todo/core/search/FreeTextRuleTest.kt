package com.singularity.todo.core.search

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    // ── The two halves ───────────────────────────────────────────────────────
    //
    // The index answers the positive terms and the rule answers the negated
    // ones. These are the assertions that hold that split in place: a rule that
    // asked the index's question again would be filtering twice.

    @Test
    fun `the positives and the negations are two halves of the rule`() {
        val rule = FreeTextRule(
            terms = listOf(
                FreeTextRule.Term("alpha"),
                FreeTextRule.Term("beta", negated = true),
            ),
        )

        assertEquals(listOf("alpha"), rule.positives.map { it.text })
        assertEquals(listOf("beta"), rule.negations.map { it.text })
    }

    @Test
    fun `a rule with no negated term satisfies the negations trivially`() {
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha")))

        assertTrue(rule.satisfiesNegations { true })
        assertTrue(rule.satisfiesNegations { false })
    }

    @Test
    fun `a record that carries the negated term does not satisfy the negations`() {
        val rule = FreeTextRule(
            terms = listOf(
                FreeTextRule.Term("alpha"),
                FreeTextRule.Term("beta", negated = true),
            ),
        )

        assertFalse(rule.satisfiesNegations { it == "beta" }, "the negated term is there, so the record is ruled out")
        assertTrue(rule.satisfiesNegations { it == "alpha" })
    }

    @Test
    fun `a candidate the index found is not asked again about the positives`() {
        // The index has already answered "does this record carry the word" — and
        // it answered it better, because it knows the tokenizer. The rule is asked
        // only about the negated terms.
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha")))
        val plan = rule.plan()

        assertTrue(
            rule.accepts(plan) { false },
            "the rule must not second-guess the index about the positive terms",
        )
    }

    @Test
    fun `a candidate the index found is still held against the negated terms`() {
        val rule = FreeTextRule(
            terms = listOf(
                FreeTextRule.Term("alpha"),
                FreeTextRule.Term("beta", negated = true),
            ),
        )
        val plan = rule.plan()

        assertFalse(rule.accepts(plan) { it == "beta" }, "the negated term is the rule's business")
        assertTrue(rule.accepts(plan) { it == "alpha" })
    }

    @Test
    fun `a candidate read row by row is held against the whole rule`() {
        // The index could not answer this rule at all — it says only what a
        // record must *not* carry — so the rule answers it alone.
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha", negated = true)))
        val plan = rule.plan()

        assertIs<TextMatchPlan.BroadScan>(plan)
        assertFalse(rule.accepts(plan) { it == "alpha" })
        assertTrue(rule.accepts(plan) { it == "beta" })
    }

    // ─── The question the index is asked ───────────────────────────────────────

    @Test
    fun `the question holds the positive terms`() {
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha"), FreeTextRule.Term("beta")))

        assertEquals(
            TextMatchQuery(terms = listOf("alpha", "beta")),
            rule.indexQuery(),
            "the index is asked about the terms it can answer",
        )
    }

    @Test
    fun `the question keeps the alternatives of an OR apart`() {
        val rule = FreeTextRule(
            terms = listOf(FreeTextRule.Term("alpha"), FreeTextRule.Term("beta")),
            combinesWithOr = true,
        )

        assertEquals(
            TextMatchQuery(terms = listOf("alpha", "beta"), combinesWithOr = true),
            rule.indexQuery(),
            "terms that are alternatives are joined the other way",
        )
    }

    @Test
    fun `the question holds no negated term`() {
        val rule = FreeTextRule(
            terms = listOf(FreeTextRule.Term("alpha"), FreeTextRule.Term("beta", negated = true)),
        )

        assertEquals(
            TextMatchQuery(terms = listOf("alpha")),
            rule.indexQuery(),
            "FTS has no NOT operator, so a negated term is not a question the index can answer",
        )
    }

    @Test
    fun `a rule the index cannot answer asks it nothing`() {
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha", negated = true)))

        assertEquals(
            TextMatchQuery.EMPTY,
            rule.indexQuery(),
            "an empty question finds nothing — the caller reads the rows itself",
        )
    }

    @Test
    fun `a plain search text is one term`() {
        val rule = FreeTextRule.singleTerm("port")

        assertEquals(listOf(FreeTextRule.Term("port")), rule.terms)
        assertEquals(TextMatchQuery(terms = listOf("port")), rule.indexQuery())
    }

    @Test
    fun `the positives are what the ranking asks about`() {
        // The ranking asks whether the *title* carries the word — a question about
        // one surface of the record, asked to order the records the index found.
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term("alpha")))

        assertTrue(rule.satisfiesPositives { it == "alpha" })
        assertFalse(rule.satisfiesPositives { it == "beta" })
    }
}
