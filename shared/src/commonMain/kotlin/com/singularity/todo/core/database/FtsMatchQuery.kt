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
 * - every token is emitted inside a quoted phrase, so a token that happens to
 *   be a keyword (`and`, `or`, `not`, `near`) is a word and not an operator.
 *
 * The result is therefore either a valid expression or nothing at all.
 *
 * ## The shape of the expression — a phrase, not a conjunction
 *
 * A term is a *phrase*: its tokens have to stand next to each other, and only
 * its last token may be unfinished. The rule says exactly that — see
 * [TextMatch] — and FTS4 can say it too:
 *
 * - `"repo*"` — a one-word term is a word prefix;
 * - `"quarterly repo*"` — a two-word term is the word "quarterly" followed by
 *   a word that begins with "repo".
 *
 * The expression used to be a conjunction of prefixed tokens (`quarterly* repo*`),
 * which asks for both words *anywhere* in the record. That is a wider question
 * than the rule asks, so the index offered candidates the rule then threw away —
 * the rule filtered twice, and the index could not be the answer.
 *
 * A term with more than one word is rare — it is a quoted phrase in the query
 * language — but a term with one word is every ordinary word of every query,
 * and the difference between "these two words are next to each other" and "these
 * two words are both in here" is the difference between the index answering the
 * question and the index answering a nearby one.
 *
 * ## The terms of the query
 *
 * The terms are joined by a space when they are requirements — FTS4 reads a
 * juxtaposition as a conjunction, and binds it tighter than `OR` — and by `OR`
 * when they are alternatives. So `a* b* OR c*` reads as `(a AND b) OR c`,
 * which is what the query means.
 *
 * ## Where the rule still lives
 *
 * The expression is the *positive* half of the rule, and the index answers it.
 * The negative half — a term that must **not** be carried — has no expression:
 * FTS has no `NOT` operator. That half is the rule's business, in memory. See
 * `FreeTextRule`.
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
                phrase(tokens)
            }
        }
        if (termExpressions.isEmpty()) return null
        val separator = if (query.combinesWithOr) " OR " else " "
        return termExpressions.joinToString(separator = separator)
    }

    /**
     * One term as a phrase: the tokens next to each other, the last one a prefix.
     *
     * The star goes on the **last** token only, because the rule matches the
     * tokens before it exactly — "quarterly report" is the word "quarterly"
     * followed by a word that begins with "report", not two words that begin
     * with those letters.
     */
    private fun phrase(tokens: List<String>): String {
        val last = tokens.last()
        val head = tokens.dropLast(1)
        return if (head.isEmpty()) {
            "\"$last*\""
        } else {
            "\"${head.joinToString(separator = " ")} $last*\""
        }
    }
}
