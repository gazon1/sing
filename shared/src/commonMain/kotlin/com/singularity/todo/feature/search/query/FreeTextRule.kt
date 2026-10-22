package com.singularity.todo.feature.search.query

import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.core.search.TextMatchQuery

/**
 * The free-text part of a query — the terms that match no condition rule (§3 G5) —
 * and the one rule that every kind of record is matched against.
 *
 * ## Why this type exists
 *
 * A query used to mean two different things at once. Tasks were matched per term:
 * every `HasText` leaf became a task predicate of its own. Notes, projects and tags
 * were matched against the *joined* free text — the whole query as one phrase. The
 * same two words therefore found a task and missed a note carrying the same two
 * words, so the answer depended on which kind of thing the user was looking for.
 *
 * The rule has one owner now: the resolver builds this object from the parsed query
 * (§3 G5, G6), and the use case applies it to every kind.
 *
 * ## The rule, and the two halves of it
 *
 * - A **term** matches a record when one of the record's words begins with it,
 *   case-insensitively. Which text is searchable belongs to the kind of record: a
 *   task is searched by title and description (`TaskFilter.Search`), a note by its
 *   title, a project or a tag by its name (F3).
 *
 *   The rule is a *word-prefix* rule because that is what the full-text index
 *   implements — the index stores words, so it can only find the beginning of one
 *   (F2a). The matcher lives in
 *   [com.singularity.todo.core.search.TextMatch], and both the index and this
 *   rule use it, so a record found by one is found by the other.
 * - The terms are combined by the query's connective (F4). A conjunction — the
 *   default, because §3 G2 makes juxtaposition a conjunction — requires **every**
 *   term to match (F2). A query that contains an `OR` makes the terms alternatives:
 *   **any** of them may match.
 * - A term under `NOT` must **not** match.
 *
 * ## Who answers what
 *
 * F2b — the index answers the positive terms, the rule the negated ones.
 *
 * The rule has two halves, and they are answered by two different things.
 *
 * - The **positives** are answered by the full-text index. The index knows the
 *   tokenizer, it knows what a word is, and it is the one that narrows the rows.
 *   Applying the rule to the index's candidates a second time was work done twice
 *   — and it made the rule a second source of the answer to "does this record
 *   carry the word", next to the index that had already answered it.
 * - The **negatives** are answered by the rule, in memory. FTS has no `NOT`
 *   operator, so nothing else can answer them.
 *
 * Which half applies to a candidate depends on where the candidate came from, and
 * that is what [plan] says. [accepts] is the one place that reads the plan and
 * applies the matching half — a caller that filtered the candidates by the whole
 * rule would be filtering twice, and a caller that filtered them by the positives
 * would throw away the records the index could not have found.
 *
 * @param terms The free-text terms, in the order the query states them (F1: one term
 *   is one `HasText` condition, and a quoted string is one term however many words it
 *   holds).
 * @param combinesWithOr True when the query contains an `OR`, so the terms are
 *   alternatives rather than requirements.
 */
data class FreeTextRule(val terms: List<Term> = emptyList(), val combinesWithOr: Boolean = false) {
    /** One free-text term, and whether the query negates it. */
    data class Term(val text: String, val negated: Boolean = false)

    /** True when the query states no free text at all. */
    val isEmpty: Boolean get() = terms.isEmpty()

    /** The terms that must match — the half the index answers. */
    val positives: List<Term> get() = terms.filter { !it.negated }

    /** The terms that must not match — the half only the rule can answer. */
    val negations: List<Term> get() = terms.filter { it.negated }

    /**
     * True when the record's searchable text satisfies this rule in full.
     *
     * This is the rule as it was written: every positive has to match and every
     * negated term must not. It is what a candidate is held against when the
     * index could not answer the question at all — see [plan] and [accepts].
     *
     * A rule with no terms selects nothing: a query with no free text has no
     * free-text part to match, and the structured terms of a query are filters over
     * tasks (F5). Tasks are not filtered by an empty rule — the resolver adds no task
     * predicate for it at all, so the structured conditions decide on their own.
     *
     * @param matchesTerm Answers whether one term matches the record's searchable
     *   text. The caller supplies it because the searchable text belongs to the kind
     *   of record, not to the rule (F3).
     */
    fun selects(matchesTerm: (String) -> Boolean): Boolean {
        // F5 — a query with no free text selects no note, project or tag. The rule
        // is what says so.
        if (terms.isEmpty()) return false
        val satisfied = terms.map { term ->
            val contained = matchesTerm(term.text)
            if (term.negated) !contained else contained
        }
        // F4 — the terms are combined by the query's connective. `OR` makes the terms
        // alternatives, a conjunction makes them requirements.
        // F2 — every term has to match. Matching is a word-prefix match and is
        // case-insensitive, which is the caller's half of the rule (F3).
        return if (combinesWithOr) satisfied.any { it } else satisfied.all { it }
    }

    /**
     * True when the record carries none of the negated terms.
     *
     * This is the half of the rule the index cannot answer, so it is the half the
     * rule keeps. It is deliberately *not* a filter on the positives: the index has
     * already answered those, and answering them again is the work this split
     * exists to remove.
     *
     * A rule with no negated terms satisfies this trivially — there is nothing to
     * hold a candidate against.
     */
    fun satisfiesNegations(matchesTerm: (String) -> Boolean): Boolean {
        if (negations.isEmpty()) return true
        return negations.all { term -> !matchesTerm(term.text) }
    }

    /**
     * True when the record carries the positive terms — the half the index answers.
     *
     * This is *not* a filter: it is a question about one surface of the record
     * ("does the title carry the word?"), asked to rank the records that the index
     * already found. A record the index found is in the answer whatever this says;
     * this only says whether it comes first.
     */
    fun satisfiesPositives(matchesTerm: (String) -> Boolean): Boolean {
        if (positives.isEmpty()) return false
        return if (combinesWithOr) {
            positives.any { term -> matchesTerm(term.text) }
        } else {
            positives.all { term -> matchesTerm(term.text) }
        }
    }

    /**
     * What the rule says about a record, given how the record was found.
     *
     * This is the one place that reads the plan and applies the matching half of
     * the rule:
     *
     * - a candidate that came from the index has already been matched against the
     *   positives, so only the negations are left to answer;
     * - a candidate that was read row by row — because the index could not answer
     *   the rule at all — is held against the whole rule.
     *
     * A caller that held the candidates against the whole rule would filter twice
     * whenever the index had answered, and a caller that held them against the
     * positives alone would keep records the index could not have found.
     *
     * @param plan What the index could do for this rule — see [plan].
     * @param matchesTerm Answers whether one term matches the record's searchable
     *   text (F3).
     */
    fun accepts(plan: TextMatchPlan, matchesTerm: (String) -> Boolean): Boolean = when (plan) {
        is TextMatchPlan.Narrowed -> satisfiesNegations(matchesTerm)
        TextMatchPlan.BroadScan -> selects(matchesTerm)
    }

    /**
     * What the full-text index can do for this rule.
     *
     * ## Why the decision lives here
     *
     * This is the one place that knows what the index can answer. It used to
     * live in the resolver, which meant every other caller of the index had to
     * answer the same question again — and answer it again the same way, by
     * remembering to copy the rule. The rule is what the index is asked about,
     * so the rule is where the answer belongs.
     *
     * ## The three answers
     *
     * - A rule of positive terms is narrowed by them. The terms are looked up
     *   as they are.
     * - A rule that *requires* a negated term is still narrowed: the index
     *   finds the records that carry the positive terms, and the rule then
     *   removes the ones that carry the negated one. The negation stays the
     *   rule's business — the index has no `NOT`.
     * - A rule the index cannot answer at all reads the rows: a rule that says
     *   only what a record must *not* carry, and a rule that makes a negated
     *   term an alternative of a positive one. The records that match by *not*
     *   carrying the negated term are exactly the ones a positive lookup throws
     *   away, so there is nothing to look up.
     */
    fun plan(): TextMatchPlan {
        val positive = positives.map { it.text }
        if (positive.isEmpty()) return TextMatchPlan.BroadScan
        if (combinesWithOr && negations.isNotEmpty()) return TextMatchPlan.BroadScan
        return TextMatchPlan.Narrowed(
            TextMatchQuery(terms = positive, combinesWithOr = combinesWithOr),
        )
    }
}
