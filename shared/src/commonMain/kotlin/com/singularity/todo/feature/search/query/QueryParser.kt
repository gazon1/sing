package com.singularity.todo.feature.search.query

/**
 * Abstract query parsing engine.
 *
 * Subclassed by [SingularityQueryParser] to provide concrete regex rules.
 * Drives the [QueryTokenizer] token stream into a [Query] AST,
 * respecting operator precedence (AND > OR) and grouping with parentheses.
 *
 * Architecture (lifted from Orgzly, stripped of IntelliJ annotations):
 * - Two-phase consumption: conditions are collected under AND semantics,
 *   and whenever OR is encountered, the accumulated AND-group becomes one
 *   arm of an OR node — producing a left-associative tree.
 * - A `lastTokenWasCondition` flag handles the precedence hack:
 *   `a or b and c` parses as `OR(a, AND(b, c))` because `and` is higher-precedence.
 *
 * @param tokens Pre-tokenized input from [QueryTokenizer].
 */
abstract class QueryParser(protected val tokens: List<QueryTokenizer.Token>) {

    /** Regex-driven condition match: tries each pattern in order, returns first match. */
    protected abstract val conditionMatches: List<ConditionMatch>

    /** Sort-order match rules. */
    protected abstract val sortOrderMatches: List<SortOrderMatch>

    /** Option (limit/offset) match rules. */
    protected abstract val optionMatches: List<OptionMatch>

    /**
     * Parse the token stream into a [Query].
     *
     * The base implementation throws [UnsupportedOperationException].
     * Subclasses must override this with a concrete Pratt-parser implementation.
     *
     * @throws QueryParseException if the token stream cannot be consumed.
     */
    open fun parse(): Query = throw UnsupportedOperationException(
        "QueryParser.parse() must be overridden by a concrete implementation",
    )

    /**
     * Parse a single condition atom (word, quoted, or parenthesized group).
     * Consumes one or more tokens that form a single condition.
     */
    private fun parseAtom(startPos: Int): Pair<Condition, Int> {
        if (startPos >= tokens.size) {
            throw QueryParseException(
            "Unexpected end of input",
            tokenPosition(startPos),
        )
        }
        val tok = tokens[startPos]

        return when (tok) {
            is QueryTokenizer.Token.LParen -> {
                val (inner, nextPos) = parseGroup(startPos + 1)
                Condition.And(listOf(inner)) to nextPos
            }

            is QueryTokenizer.Token.Not -> {
                val (inner, nextPos) = parseAtom(startPos + 1)
                Condition.Not(inner) to nextPos
            }

            is QueryTokenizer.Token.Word -> {
                val matched = matchCondition(tok.text, startPos)
                    ?: Condition.HasText(tok.text)
                matched to (startPos + 1)
            }

            is QueryTokenizer.Token.Quoted -> {
                Condition.HasText(tok.text) to (startPos + 1)
            }

            else -> throw QueryParseException(
                "Unexpected token: ${tok.text}",
                tokenPosition(startPos),
            )
        }
    }

    /**
     * Parse a parenthesized group, stopping at the matching RParen.
     */
    private fun parseGroup(startPos: Int): Pair<Condition, Int> {
        val conditions = mutableListOf<Condition>()
        var pos = startPos
        var lastWasCond = false

        while (pos < tokens.size) {
            when (val tok = tokens[pos]) {
                is QueryTokenizer.Token.RParen -> {
                    val cond = when {
                        conditions.isEmpty() -> return Condition.And(emptyList()) to (pos + 1)
                        conditions.size == 1 -> conditions[0]
                        else -> flattenAnd(conditions)
                    }
                    return cond to (pos + 1)
                }

                is QueryTokenizer.Token.And -> {
                    if (conditions.isEmpty()) {
                        throw QueryParseException(
                        "Unexpected AND inside group",
                        tokenPosition(pos),
                    )
                    }
                    pos++
                    lastWasCond = false
                }

                is QueryTokenizer.Token.Or -> {
                    if (conditions.size < 2) {
                        throw QueryParseException(
                        "OR inside group requires at least two operands",
                        tokenPosition(pos),
                    )
                    }
                    val left = flattenAnd(conditions)
                    conditions.clear()
                    pos++
                    var rightPos = pos
                    while (rightPos < tokens.size && tokens[rightPos] !is QueryTokenizer.Token.RParen) {
                        val (c, np) = parseAtom(rightPos)
                        conditions.add(c)
                        rightPos = np
                    }
                    val right = if (conditions.size == 1) conditions[0] else flattenAnd(conditions)
                    return Condition.Or(listOf(left, right)) to rightPos
                }

                else -> {
                    val (cond, nextPos) = parseAtom(pos)
                    conditions.add(cond)
                    pos = nextPos
                    lastWasCond = true
                }
            }

            if (!lastWasCond && pos < tokens.size) {
                val (cond, np) = parseAtom(pos)
                conditions.add(cond)
                pos = np
                lastWasCond = true
            }
        }

        throw QueryParseException("Unclosed parenthesis", tokenPosition(startPos - 1))
    }

    /**
     * Try to match a word against registered condition patterns.
     * Returns the first matching [Condition], or `null` if no pattern applies.
     */
    protected open fun matchCondition(text: String, position: Int): Condition? {
        for (match in conditionMatches) {
            val result = match.regex.find(text) ?: continue
            return try {
                match.build(result)
            } catch (e: Exception) {
                throw QueryParseException(
                    "Failed to parse '$text': ${e.message}",
                    tokenPosition(position),
                )
            }
        }
        return null
    }

    /**
     * Flatten a list of conditions into a single AND condition.
     * Single-element lists are returned as-is (not wrapped).
     * Empty lists return `Condition.And(emptyList())` — matches everything.
     */
    private fun flattenAnd(conditions: List<Condition>): Condition {
        val flat = mutableListOf<Condition>()
        for (c in conditions) {
            when (c) {
                is Condition.And -> flat.addAll(c.parts)
                else -> flat.add(c)
            }
        }
        return if (flat.size == 1) flat[0] else Condition.And(flat)
    }

    /**
     * Approximate character position for an error message.
     * Returns 0 if the token index is out of range.
     */
    protected open fun tokenPosition(tokenIndex: Int): Int = 0

    // ─── Match data classes ─────────────────────────────────────────────────

    /** A regex pattern that produces a [Condition] when it matches. */
    data class ConditionMatch(val regex: Regex, val build: (MatchResult) -> Condition)

    /** A regex pattern that produces a [SortOrder] when it matches. */
    data class SortOrderMatch(val regex: Regex, val sortOrder: SortOrder)

    /** A regex pattern that modifies [Options] when it matches. */
    data class OptionMatch(val regex: Regex, val apply: (MatchResult, Options) -> Options)
}
