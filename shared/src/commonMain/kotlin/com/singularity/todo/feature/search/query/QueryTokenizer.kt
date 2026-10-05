package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — reimplemented from
//   docs/specs/search-query-grammar.md per option A1. The ported implementation is gone.
//
//   NOT a clean room: the author read the Orgzly source. A1 removes verbatim
//   correspondence; it does not terminate a derivation. See the honesty clause in the
//   spec and docs/legal/PROVENANCE.md

/**
 * Lexer for the search query language — implementation of §1 of
 * [docs/specs/search-query-grammar.md][spec].
 *
 * Splits a query string into a flat list of [Token]s: words, quoted strings, parentheses,
 * and the logical operators `AND` / `OR` / `NOT`.
 *
 * [spec]: ../../../../../../../docs/specs/search-query-grammar.md
 *
 * ## Provenance
 *
 * A previous implementation of this class was ported from Orgzly (GPL-3.0). This one was
 * written from the grammar specification above, with the behavioural suite as the
 * contract. That is option **A1** in `docs/legal/PROVENANCE.md`.
 *
 * It is **not** a clean room: the author read the original source. A1 removes verbatim
 * correspondence; it does not terminate a derivation. See the honesty clause at the top
 * of the specification.
 *
 * ## The three lexical rules worth stating twice
 *
 * **R1 — an unterminated quote runs to end of input.** The user is typing; a trailing
 * quote is the normal state of a query mid-edit, and rejecting it would make the search
 * box feel broken for the half-second before they finish.
 *
 * **R2 — quoted contents are literal.** Never re-examined for operators or parentheses,
 * so `"AND"` is the text `AND`.
 *
 * **R3 — a word that case-insensitively equals a keyword *is* that keyword.** The cost is
 * that a user searching for the literal word `and` must quote it; the alternative is a
 * grammar where `And` sometimes means one thing and sometimes another.
 */
class QueryTokenizer(private val input: String) {

    /** The token stream for [input]. */
    fun tokens(): List<Token> {
        val out = mutableListOf<Token>()
        var pos = 0
        while (pos < input.length) {
            when {
                input[pos].isWhitespace() -> pos++

                input[pos] == '(' -> {
                    out += Token.LParen
                    pos++
                }

                input[pos] == ')' -> {
                    out += Token.RParen
                    pos++
                }

                input[pos] == '"' -> pos = readQuoted(pos, out)

                else -> pos = readWord(pos, out)
            }
        }
        return out
    }

    /**
     * Reads a `"…"` token starting at [start], which must be the opening quote.
     *
     * Returns the index just past what was consumed. Per R1, running out of input before
     * the closing quote is not an error — the token simply ends at the end of the input.
     */
    private fun readQuoted(start: Int, out: MutableList<Token>): Int {
        val text = StringBuilder()
        var pos = start + 1
        while (pos < input.length && input[pos] != '"') {
            // R4: a backslash escapes the next character, and only inside quotes.
            if (input[pos] == '\\' && pos + 1 < input.length) {
                text.append(input[pos + 1])
                pos += 2
            } else {
                text.append(input[pos])
                pos++
            }
        }
        if (pos < input.length) pos++ // consume the closing quote, if there was one
        out += Token.Quoted(text.toString())
        return pos
    }

    /**
     * Reads a bare word starting at [start]: everything up to whitespace or a parenthesis.
     *
     * A word is not a unit of meaning. `due:today` is one word here and is only
     * recognised as a condition later, in the parser — which is what lets §3 G5 fall
     * through to free text for anything unrecognised.
     */
    private fun readWord(start: Int, out: MutableList<Token>): Int {
        val text = StringBuilder()
        var pos = start
        while (pos < input.length && !input[pos].isWhitespace() && input[pos] != '(' && input[pos] != ')') {
            text.append(input[pos])
            pos++
        }
        val word = text.toString()
        // R3: keyword recognition is case-insensitive and happens here, at the lexical
        // layer, so no downstream rule has to re-check it.
        out += when {
            word.equals(KEYWORD_AND, ignoreCase = true) -> Token.And
            word.equals(KEYWORD_OR, ignoreCase = true) -> Token.Or
            word.equals(KEYWORD_NOT, ignoreCase = true) -> Token.Not
            else -> Token.Word(word)
        }
        return pos
    }

    private companion object {
        const val KEYWORD_AND = "AND"
        const val KEYWORD_OR = "OR"
        const val KEYWORD_NOT = "NOT"
    }

    // ─── Token types ────────────────────────────────────────────────────────

    /**
     * One lexical token.
     *
     * [CharSequence] is implemented so a token can be used directly in a message or a log
     * line without a `.text` at every call site — the common case is "show the user what
     * they typed", and the tokens are the typed thing.
     */
    sealed interface Token : CharSequence {
        val text: String

        /** A bare word. Not yet known to be a condition or free text — see [QueryTokenizer]. */
        data class Word(override val text: String) : Token

        /** A `"…"` string, escapes resolved. Always free text, per R2. */
        data class Quoted(override val text: String) : Token

        /** The `(` grouping token. */
        data object LParen : Token {
            override val text: String = "("
        }

        /** The `)` grouping token. */
        data object RParen : Token {
            override val text: String = ")"
        }

        /** Explicit conjunction. Never required — see G2. */
        data object And : Token {
            override val text: String = "AND"
        }

        /** Disjunction. */
        data object Or : Token {
            override val text: String = "OR"
        }

        /** Prefix negation. Binds the rest of the expression — see G3. */
        data object Not : Token {
            override val text: String = "NOT"
        }

        override val length: Int get() = text.length
        override fun get(index: Int): Char = text[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            text.subSequence(startIndex, endIndex)
    }
}
