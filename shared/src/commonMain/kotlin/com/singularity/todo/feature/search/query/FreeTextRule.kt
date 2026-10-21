package com.singularity.todo.feature.search.query

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
 * ## The rule
 *
 * - A **term** matches a record when the record's searchable text contains it,
 *   case-insensitively. Which text is searchable belongs to the kind of record: a
 *   task is searched by title and description (`TaskFilter.Search`), a note by its
 *   title, a project or a tag by its name (F3).
 * - The terms are combined by the query's connective (F4). A conjunction — the
 *   default, because §3 G2 makes juxtaposition a conjunction — requires **every**
 *   term to match (F2). A query that contains an `OR` makes the terms alternatives:
 *   **any** of them may match.
 * - A term under `NOT` must **not** match.
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

    /**
     * True when the record's searchable text satisfies this rule.
     *
     * A rule with no terms selects nothing: a query with no free text has no
     * free-text part to match, and the structured terms of a query are filters over
     * tasks (F5). Tasks are not filtered by an empty rule — the resolver adds no task
     * predicate for it at all, so the structured conditions decide on their own.
     *
     * @param matchesTerm Answers whether one term occurs in the record's searchable
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
        // F2 — every term has to match. Matching is case-insensitive, which is the
        // caller's half of the rule (F3).
        return if (combinesWithOr) satisfied.any { it } else satisfied.all { it }
    }
}
