package com.singularity.todo.core.database

import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.core.search.TextMatchQuery

/**
 * Renders a [TextMatchQuery] as an FTS4 `MATCH` expression.
 *
 * ## Why the expression is built here and not by the caller
 *
 * A `MATCH` expression is *SQL*, and SQL built by string concatenation is how
 * injections happen. The terms of a search come from a person typing into a
 * search box, so the expression is built from parts that cannot carry SQL:
 *
 * - a term is split into tokens — runs of letters and digits — and everything
 *   else is dropped, so a quote, a parenthesis or a `*` typed by the user is
 *   not syntax;
 * - every token is emitted as `token*`, a prefix lookup. A bare token would be
 *   an operator when it happens to be a keyword (`and`, `or`, `not`, `near`),
 *   but a prefixed token is always a term.
 *
 * The result is therefore either a valid expression or nothing at all.
 *
 * ## The shape of the expression
 *
 * A term's tokens are joined by a space, which FTS4 reads as a conjunction: the
 * phrase "quarterly report" asks for both words. The terms are joined by a space
 * as well when they are requirements, and by `OR` when they are alternatives.
 * FTS4 binds the implicit conjunction tighter than `OR`, so
 * `a* b* OR c*` reads as `(a* AND b*) OR c*` — which is what the query means.
 *
 * A term is matched by its *first* token as a prefix and by its remaining
 * tokens as prefixes too, which makes the candidate set a superset of the
 * records the rule accepts. The rule itself is applied to the candidates
 * afterwards, in memory: the index narrows, the rule decides.
 */
object FtsMatchQuery {

    /**
     * The `MATCH` expression for [query], or `null` when the query cannot narrow
     * anything — no terms, or no term that yields a searchable token.
     *
     * `null` is not an error: it means the caller has to fall back to reading
     * the rows and matching them in memory.
     */
    fun expression(query: TextMatchQuery): String? {
        if (query.isEmpty) return null
        val termExpressions = query.terms.mapNotNull { term ->
            val tokens = TextMatch.tokens(term)
            if (tokens.isEmpty()) {
                null
            } else {
                tokens.joinToString(separator = " ") { token -> "$token*" }
            }
        }
        if (termExpressions.isEmpty()) return null
        val separator = if (query.combinesWithOr) " OR " else " "
        return termExpressions.joinToString(separator = separator)
    }
}
