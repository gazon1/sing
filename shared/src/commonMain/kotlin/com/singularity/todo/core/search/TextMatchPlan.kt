package com.singularity.todo.core.search

/**
 * What the full-text index can do for a rule.
 *
 * The index answers positive questions: it finds the records that carry a term.
 * It has no way of saying "must not carry" — FTS has no `NOT` operator — so
 * some rules it cannot answer at all.
 *
 * ## Why this is a type and not a null
 *
 * The narrowing used to be a `TextMatchQuery?` on the resolved query, and a
 * `null` meant "the index cannot narrow this — read the rows". A null says
 * what the caller has to do, but not *why*, and the reason was decided in the
 * resolver. That is the wrong place for it: whether a rule can be narrowed is a
 * property of the index, and every other caller of the index — the note
 * search, the link autocompletion, whatever comes next — has to reach the same
 * answer. A rule that is narrowed in one place and read in another is a rule
 * with two answers.
 *
 * A named state says both: the caller reads what the index can do, and the
 * place that decides is the rule itself — see `FreeTextRule.plan(mode)`.
 */
sealed interface TextMatchPlan {

    /**
     * The index can narrow: [query] is a positive lookup.
     *
     * The query holds the positive terms of the rule. A term the index cannot
     * express — a negation — is not in it, and stays the rule's business: the
     * index finds the candidates, and the rule decides among them.
     */
    data class Narrowed(val query: TextMatchQuery) : TextMatchPlan

    /**
     * The index cannot narrow: the rows have to be read and matched in memory.
     *
     * This is the old path, and it reads every row of the profile. It is
     * correct — the rule decides among the rows that were read — and it is
     * slow, and it happens for the two shapes of rule the index cannot
     * express:
     *
     * - a rule that says only what a record must *not* carry, because the
     *   records that match by not carrying it are exactly the ones a positive
     *   lookup throws away;
     * - a rule that makes a negated term an alternative of a positive one, for
     *   the same reason.
     *
     * The state is named rather than left as a `null` so that the shape of the
     * search is visible where the search is built: a caller can see that this
     * query reads the profile, and the code that reads the profile says so.
     */
    data object BroadScan : TextMatchPlan
}
