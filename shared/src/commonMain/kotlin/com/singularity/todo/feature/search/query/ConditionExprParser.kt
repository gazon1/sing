package com.singularity.todo.feature.search.query

/**
 * Pratt parser for search condition expressions.
 *
 * Operator precedence: NOT > AND > OR.
 * Consumes tokens that have already had sort/option/desc directives removed.
 *
 * @param tokens Remaining condition tokens after sort/option extraction.
 * @param conditionMatches Regex rules that map a word to a [Condition].
 * @param parseDate Parses a `due:` or `scheduled:` token into a [Condition].
 */
class ConditionExprParser(
    private val tokens: List<QueryTokenizer.Token>,
    private val conditionMatches: List<QueryParser.ConditionMatch>,
    private val parseDate: (prefix: String, spec: String) -> Condition,
) {

    /**
     * Parse all tokens as a condition expression.
     * Returns a single [Condition] (possibly [Condition.And] with multiple parts).
     */
    fun parse(): Condition {
        if (tokens.isEmpty()) return Condition.And(emptyList())
        return parseOr(0).first
    }

    /**
     * Parse an OR-expression starting at [startPos].
     * orExpr → andExpr (OR andExpr)*
     */
    private fun parseOr(startPos: Int): Pair<Condition, Int> {
        val (left, pos) = parseAnd(startPos)
        if (pos >= tokens.size || tokens[pos] !is QueryTokenizer.Token.Or) {
            return left to pos
        }
        // OR — collect all OR-separated parts
        val parts = mutableListOf<Condition>(left)
        var p = pos
        while (p < tokens.size && tokens[p] is QueryTokenizer.Token.Or) {
            p++ // skip OR
            val (andExpr, np) = parseAnd(p)
            parts.add(andExpr)
            p = np
        }
        return flattenOr(parts) to p
    }

    /**
     * Parse an AND-expression starting at [startPos].
     * andExpr → atom (AND? atom)*   -- AND is optional between atoms
     */
    private fun parseAnd(startPos: Int): Pair<Condition, Int> {
        val (first, p) = parseAtom(startPos)
        val parts = mutableListOf<Condition>(first)
        var pos = p

        while (pos < tokens.size) {
            val tok = tokens[pos]
            // Explicit AND
            if (tok is QueryTokenizer.Token.And) {
                pos++ // consume AND
                val (atom, np) = parseAtom(pos)
                parts.add(atom)
                pos = np
                continue
            }
            // Implicit AND: adjacent atom tokens
            if (tok is QueryTokenizer.Token.Word || tok is QueryTokenizer.Token.Quoted ||
                tok is QueryTokenizer.Token.LParen || tok is QueryTokenizer.Token.Not
            ) {
                val (atom, np) = parseAtom(pos)
                parts.add(atom)
                pos = np
                continue
            }
            break
        }

        return flattenAnd(parts) to pos
    }

    /**
     * Parse a single atom starting at [startPos]: NOT? (word | quoted | '(' orExpr ')').
     */
    private fun parseAtom(startPos: Int): Pair<Condition, Int> {
        if (startPos >= tokens.size) return Condition.And(emptyList()) to startPos

        val tok = tokens[startPos]

        return when (tok) {
            is QueryTokenizer.Token.Not -> {
                // Recurse into the expression that follows NOT
                val innerStart = startPos + 1
                val (inner, np) = parseOr(innerStart)
                Condition.Not(inner) to np
            }

            is QueryTokenizer.Token.LParen -> {
                val (inner, np) = parseOr(startPos + 1)
                // consume closing )
                val closing = if (np < tokens.size && tokens[np] is QueryTokenizer.Token.RParen) np + 1 else np
                inner to closing
            }

            is QueryTokenizer.Token.Word -> {
                val matched = matchCondition(tok.text) ?: Condition.HasText(tok.text)
                matched to (startPos + 1)
            }

            is QueryTokenizer.Token.Quoted -> {
                Condition.HasText(tok.text) to (startPos + 1)
            }

            else -> Condition.And(emptyList()) to startPos
        }
    }

    private fun matchCondition(text: String): Condition? {
        for (match in conditionMatches) {
            val result = match.regex.find(text) ?: continue
            return try {
                match.build(result)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun flattenAnd(conditions: List<Condition>): Condition {
        val flat = mutableListOf<Condition>()
        for (c in conditions) {
            when (c) {
                is Condition.And -> flat.addAll(c.parts)
                else -> flat.add(c)
            }
        }
        return when {
            flat.isEmpty() -> Condition.And(emptyList())
            flat.size == 1 -> flat[0]
            else -> Condition.And(flat)
        }
    }

    private fun flattenOr(conditions: List<Condition>): Condition {
        val flat = mutableListOf<Condition>()
        for (c in conditions) {
            when (c) {
                is Condition.Or -> flat.addAll(c.parts)
                else -> flat.add(c)
            }
        }
        return when {
            flat.isEmpty() -> Condition.Or(emptyList())
            flat.size == 1 -> flat[0]
            else -> Condition.Or(flat)
        }
    }
}
