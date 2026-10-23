package com.singularity.todo.core.search

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the full-text index can do for a rule — one case per shape of rule.
 *
 * The index answers positive questions: it finds the records that carry a term.
 * It has no `NOT`, so the shape of the rule decides whether it can be asked
 * anything at all.
 *
 * Each test here is one shape, and the shapes are the ones the query language
 * can produce. A new shape of rule that the index cannot answer is a rule that
 * reads the whole profile — and the only way to notice is a case that says so.
 */
@Tag("fast")
class TextMatchPlanTest {

    @Test
    fun `a rule of positive terms is narrowed by them`() {
        val rule = FreeTextRule(
            terms = listOf(
                FreeTextRule.Term(text = "arch"),
                FreeTextRule.Term(text = "report"),
            ),
        )

        val plan = rule.plan()

        assertEquals(
            TextMatchPlan.Narrowed(TextMatchQuery(terms = listOf("arch", "report"))),
            plan,
        )
    }

    @Test
    fun `a rule of alternatives is narrowed by them, as alternatives`() {
        val rule = FreeTextRule(
            terms = listOf(
                FreeTextRule.Term(text = "arch"),
                FreeTextRule.Term(text = "report"),
            ),
            combinesWithOr = true,
        )

        val plan = rule.plan()

        assertEquals(
            TextMatchPlan.Narrowed(TextMatchQuery(terms = listOf("arch", "report"), combinesWithOr = true)),
            plan,
        )
    }

    @Test
    fun `a rule that requires a negated term is still narrowed by the positive one`() {
        // The index finds the records that carry "arch"; the rule then removes
        // the ones that carry "draft". The negation is the rule's business, and
        // it does not stop the index from narrowing.
        val rule = FreeTextRule(
            terms = listOf(
                FreeTextRule.Term(text = "arch"),
                FreeTextRule.Term(text = "draft", negated = true),
            ),
        )

        val plan = rule.plan()

        assertEquals(
            TextMatchPlan.Narrowed(TextMatchQuery(terms = listOf("arch"))),
            plan,
        )
    }

    @Test
    fun `a rule that makes a negated term an alternative reads the rows`() {
        // "arch OR NOT draft" — the records that match by *not* carrying
        // "draft" are exactly the ones a positive lookup throws away, so there
        // is nothing to look up and the rows have to be read.
        val rule = FreeTextRule(
            terms = listOf(
                FreeTextRule.Term(text = "arch"),
                FreeTextRule.Term(text = "draft", negated = true),
            ),
            combinesWithOr = true,
        )

        assertIs<TextMatchPlan.BroadScan>(rule.plan())
    }

    @Test
    fun `a rule that says only what a record must not carry reads the rows`() {
        val rule = FreeTextRule(terms = listOf(FreeTextRule.Term(text = "draft", negated = true)))

        assertIs<TextMatchPlan.BroadScan>(rule.plan())
    }

    @Test
    fun `a rule with no terms reads the rows`() {
        // There is nothing to look up. The use case answers a rule with no
        // terms with no records at all — the plan only says how the candidates
        // are found.
        assertIs<TextMatchPlan.BroadScan>(FreeTextRule().plan())
    }
}
