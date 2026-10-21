package com.singularity.todo.core.search

/**
 * The free-text part of a search, as the persistence layer receives it.
 *
 * This is the shape the *index* is asked for: the terms, and whether they are
 * alternatives. It is deliberately smaller than the query language's
 * [com.singularity.todo.feature.search.query.FreeTextRule], because a negated
 * term cannot be expressed as a positive index lookup — FTS has no `NOT`
 * operator — so the index only ever sees the terms that narrow a candidate set.
 *
 * @param terms the terms to match, in the order the query states them.
 * @param combinesWithOr true when the terms are alternatives (the query
 *   contains an `OR`) rather than requirements.
 */
data class TextMatchQuery(val terms: List<String> = emptyList(), val combinesWithOr: Boolean = false) {
    /** True when the query states no terms at all. */
    val isEmpty: Boolean get() = terms.isEmpty()

    companion object {
        /** A query that matches nothing by text. */
        val EMPTY = TextMatchQuery()
    }
}

/**
 * Word-prefix matching — the semantics the full-text index implements.
 *
 * ## Why prefix, and not "contains"
 *
 * The index is an inverted index: it stores *tokens* (words), not character
 * positions. A lookup is a dictionary hit, which is why it stays fast where
 * `LIKE '%needle%'` has to read every row. The price is that a token is matched
 * by its beginning, not by its middle: "arch" finds "architecture", and "port"
 * does not find "airport".
 *
 * That is the trade the full-text index makes, and it is the trade this matcher
 * makes too, so that the in-memory rule and the index answer the same
 * question. A matcher that said "contains" would quietly disagree with the
 * index that produced its candidates.
 *
 * ## The rule
 *
 * - A term is split into tokens — runs of letters and digits, case-folded.
 * - The term matches a text when the text's token sequence contains the term's
 *   token sequence, where the **last** token of the term matches by prefix and
 *   the ones before it match exactly. A one-word term is therefore a word-prefix
 *   match; a quoted phrase is a phrase whose last word may be unfinished.
 *
 * The tokenizer is deliberately the same shape as SQLite's `unicode61`: letters
 * and digits are token characters, everything else separates them.
 *
 * ## The one difference, and why it is safe
 *
 * `unicode61` removes the diacritics of a word — "café" is indexed as "cafe" —
 * and this matcher does not. So the index finds "café" for the term "cafe" and
 * the rule does not.
 *
 * That difference is in the safe direction: the index offers a candidate the
 * rule then rejects, and the search answers the same either way. The unsafe
 * direction — the rule accepting a record the index does not offer — is what
 * the corpus in `FtsMatcherAgreementTest` holds closed.
 */
object TextMatch {

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /**
     * True when [term] matches [text] under the word-prefix rule.
     *
     * @param text the searchable text of a record; `null` matches nothing.
     * @param term the term to look for.
     */
    fun matches(text: String?, term: String): Boolean {
        if (text.isNullOrEmpty()) return false
        val termTokens = tokens(term)
        val textTokens = tokens(text)
        if (termTokens.isEmpty() || textTokens.size < termTokens.size) return false
        for (start in 0..textTokens.size - termTokens.size) {
            if (matchesAt(textTokens, start, termTokens)) return true
        }
        return false
    }

    /**
     * True when [term] matches any of [texts].
     *
     * A record's searchable text is a choice of surfaces — a task has a title
     * and a description — and a term matches the record when it matches one of
     * them.
     */
    fun matchesAny(texts: Iterable<String?>, term: String): Boolean = texts.any { matches(it, term) }

    /**
     * The tokens of [text]: runs of letters and digits, case-folded.
     *
     * Empty runs are dropped, so a string of punctuation has no tokens and
     * matches no term.
     *
     * ## Why the fold is per character
     *
     * `String.lowercase()` is the *full* Unicode lowercase mapping, and for one
     * letter it is not one letter: `İ` (U+0130) maps to `i` followed by a
     * combining dot. The combining dot is not a letter, so the tokenizer split
     * "İstanbul" into two tokens — while SQLite's `unicode61` folds it into
     * one. A search for "stanbul" then matched the rule and not the index, and
     * the record was lost without a word.
     *
     * `Char.lowercaseChar()` is the *simple* mapping — one character in, one
     * character out — and it is what `unicode61` does. The corpus in
     * `FtsMatcherAgreementTest` is what keeps the two tokenizers in step.
     */
    fun tokens(text: String): List<String> =
        buildString(text.length) {
            text.forEach { character -> append(character.lowercaseChar()) }
        }
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }

    private fun matchesAt(textTokens: List<String>, start: Int, termTokens: List<String>): Boolean {
        for (offset in termTokens.indices) {
            val textToken = textTokens[start + offset]
            val termToken = termTokens[offset]
            val matches = if (offset == termTokens.lastIndex) {
                textToken.startsWith(termToken)
            } else {
                textToken == termToken
            }
            if (!matches) return false
        }
        return true
    }
}
