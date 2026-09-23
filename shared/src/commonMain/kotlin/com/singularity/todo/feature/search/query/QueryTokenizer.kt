package com.singularity.todo.feature.search.query

/**
 * Lexer for the search query language.
 *
 * Splits an input string into a flat list of [Token]s — words, quoted strings,
 * parentheses, and logical operators (AND / OR / NOT).
 *
 * Design notes (lifted from Orgzly, stripped of IntelliJ annotations):
 * - Regex-based tokenizer: double-quoted strings, grouped parens, and bare words.
 * - AND / OR / NOT are recognized as keywords (not bare text) when they appear
 *   as isolated whitespace-separated tokens.
 * - Quoting with `"…"` allows spaces, parens, and reserved characters in text.
 *
 * @param input The raw query string to tokenize.
 */
class QueryTokenizer(private val input: String) {

    /** Token stream produced from the input. */
    fun tokens(): List<Token> {
        val result = mutableListOf<Token>()
        var pos = 0

        while (pos < input.length) {
            // Skip whitespace
            if (input[pos].isWhitespace()) {
                pos++
                continue
            }

            when (input[pos]) {
                '(' -> {
                    result.add(Token.LParen);
                    pos++
                }
                ')' -> {
                    result.add(Token.RParen);
                    pos++
                }
                '"' -> {
                    pos = scanQuoted(pos, result)
                }
                else -> {
                    pos = scanWord(pos, result)
                }
            }
        }
        return result
    }

    private fun scanQuoted(start: Int, out: MutableList<Token>): Int {
        // Opening quote already at start
        var pos = start + 1
        val sb = StringBuilder()
        while (pos < input.length && input[pos] != '"') {
            if (input[pos] == '\\' && pos + 1 < input.length) {
                sb.append(input[pos + 1])
                pos += 2
            } else {
                sb.append(input[pos])
                pos++
            }
        }
        // Consume closing quote or end of input
        if (pos < input.length && input[pos] == '"') pos++
        out.add(Token.Quoted(sb.toString()))
        return pos
    }

    private fun scanWord(start: Int, out: MutableList<Token>): Int {
        var pos = start
        val sb = StringBuilder()
        while (pos < input.length && !input[pos].isWhitespace() && input[pos] != '(' && input[pos] != ')') {
            sb.append(input[pos])
            pos++
        }
        val word = sb.toString()
        out.add(
            when (word.uppercase()) {
                "AND" -> Token.And
                "OR" -> Token.Or
                "NOT" -> Token.Not
                else -> Token.Word(word)
            },
        )
        return pos
    }

    // ─── Token types ────────────────────────────────────────────────────────

    /** A token in the token stream. */
    sealed interface Token : CharSequence {
        val text: String

        /** Word token: any non-keyword text. */
        data class Word(override val text: String) : Token

        /** Quoted string token: contents of `"…"` with escape sequences resolved. */
        data class Quoted(override val text: String) : Token

        /** Left parenthesis `(`. */
        data object LParen : Token {
            override val text: String = "("
        }

        /** Right parenthesis `)`. */
        data object RParen : Token {
            override val text: String = ")"
        }

        /** Logical AND operator. */
        data object And : Token {
            override val text: String = "AND"
        }

        /** Logical OR operator. */
        data object Or : Token {
            override val text: String = "OR"
        }

        /** Logical NOT operator (prefix). */
        data object Not : Token {
            override val text: String = "NOT"
        }

        // CharSequence impl
        override val length: Int get() = text.length
        override fun get(index: Int): Char = text[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
    }
}
