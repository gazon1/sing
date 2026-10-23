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
 * Beside it, [contains] holds the other rule the search can be asked with —
 * "does the text carry the term *somewhere*" — which no index of words can
 * answer, and which the contains mode uses where it reads the rows.
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
 * - A term is split into tokens — runs of letters and digits, case-folded and
 *   stripped of their diacritics.
 * - The term matches a text when the text's token sequence contains the term's
 *   token sequence, where the **last** token of the term matches by prefix and
 *   the ones before it match exactly. A one-word term is therefore a word-prefix
 *   match; a quoted phrase is a phrase whose last word may be unfinished.
 *
 * The tokenizer is deliberately the same shape as SQLite's `unicode61`: letters
 * and digits are token characters, everything else separates them.
 *
 * ## Where this rule is used, and where it is not
 *
 * The index answers the positive terms of a query — it knows the tokenizer, and
 * it is what narrows the rows. This matcher answers the terms the index cannot:
 * the **negated** ones, and the whole rule of a query the index could not
 * narrow at all. See `FreeTextRule`.
 *
 * ## The diacritics
 *
 * `unicode61` removes the diacritics of a word — "café" is indexed as "cafe" —
 * and so does this matcher.
 *
 * F7 — the diacritics are folded the way the index folds them.
 *
 * The two halves of one rule therefore answer the same question the same way:
 * a search for "cafe" finds "Café au lait", and `NOT cafe` rules out exactly
 * the records "cafe" finds.
 *
 * Before this, the matcher kept the accent while the index removed it, and the
 * two halves of a rule contradicted each other: "cafe" found the record, and
 * "NOT cafe" did not rule it out. The difference was in the direction that
 * *finds more* — the index's — which is why no record was ever lost, and why
 * the contradiction went unnoticed for as long as it did.
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
     * True when [term] occurs **anywhere** in [text] — the escape-hatch rule.
     *
     * ## What this is for
     *
     * The word-prefix rule above is the rule of the indexed mode: the index
     * stores words, so it can find the beginning of one and nothing else
     * ("port" does not find "airport"). This function answers the other
     * question — "does the text carry the term *somewhere*" — and it is
     * answered by reading the rows, because no index of words can answer it.
     *
     * ## Why it lives next to the word-prefix rule and not instead of it
     *
     * A matcher that said "contains" would quietly disagree with the index
     * that produced its candidates. This one never has to: it is used where
     * the index produces no candidates at all — the contains mode reads the
     * rows and filters them with this rule. The two rules are two questions,
     * and the mode says which one is being asked.
     *
     * ## The comparison is the word rule's comparison
     *
     * The text and the term are folded and case-folded the way the word rule
     * folds them, and then compared. So the two modes answer the same way
     * about the same text: "cafe" finds "Café au lait" in the word mode and in
     * the contains mode alike, and "istanbul" finds "İstanbul" in both.
     *
     * @param text the searchable text of a record; `null` matches nothing.
     * @param term the term to look for. An empty term matches nothing.
     */
    fun contains(text: String?, term: String): Boolean {
        if (text.isNullOrEmpty() || term.isEmpty()) return false
        return fold(text).contains(fold(term))
    }

    /**
     * The text, folded and case-folded the way the tokenizer folds it.
     *
     * @param text the text to fold.
     */
    private fun fold(text: String): String =
        buildString(text.length) {
            text.forEach { character -> append(DiacriticFold.fold(character).lowercaseChar()) }
        }

    /**
     * The tokens of [text]: runs of letters and digits, folded and case-folded.
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
     *
     * The diacritics are folded the same way, and for the same reason: the
     * index folds them, and a rule that did not would answer the negated terms
     * of a query differently from its positive ones.
     */
    fun tokens(text: String): List<String> =
        buildString(text.length) {
            text.forEach { character -> append(DiacriticFold.fold(character).lowercaseChar()) }
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
