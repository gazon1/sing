package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — reimplemented from
//   docs/specs/search-query-grammar.md per option A1. The ported implementation is gone.
//
//   NOT a clean room: the author read the Orgzly source. A1 removes verbatim
//   correspondence; it does not terminate a derivation. See the honesty clause in the
//   spec and docs/legal/PROVENANCE.md

/**
 * Recursive-descent parser for condition expressions — implementation of §3 of
 * [docs/specs/search-query-grammar.md][spec].
 *
 * [spec]: ../../../../../../../docs/specs/search-query-grammar.md
 *
 * Grammar, with precedence `NOT` > `AND` > `OR` (G1):
 *
 * ```
 * expression  := disjunction
 * disjunction := conjunction ( OR conjunction )*
 * conjunction := atom ( AND? atom )*
 * atom        := NOT expression | '(' expression ')' | word | quoted
 * ```
 *
 * ## Provenance
 *
 * The previous implementation was ported from Orgzly (GPL-3.0). This one was written from
 * the specification above with the behavioural suite as the contract — option **A1** in
 * `docs/legal/PROVENANCE.md`. It is not a clean room; see the honesty clause in the
 * specification.
 *
 * ## Two rules that a conventional parser gets wrong
 *
 * **G2 — conjunction is implicit.** `due:today priority:high` is two atoms, not a syntax
 * error. This is the shape users actually type, and requiring an explicit `AND` would
 * make the language read like a programming language rather than a search box. The loop
 * in [parseConjunction] therefore continues on a bare `Word`/`Quoted`/`LParen`/`Not`
 * token, not only on an explicit [QueryTokenizer.Token.And].
 *
 * **G3 — `NOT` binds the whole remaining expression.** `NOT a OR b` is `NOT(OR(a, b))`,
 * not `OR(NOT(a), b)`. The obvious recursive-descent shortcut — making `NOT` recurse into
 * the next *atom* — silently returns a superset of what the user asked for, which is the
 * worst failure mode a filter can have. [parseAtom] therefore recurses into
 * [parseDisjunction].
 *
 * ## Flattening
 *
 * [flatten] implements G8: `AND(a, AND(b, c))` becomes `AND(a, b, c)`, and a one-element
 * node collapses to that element. This is observable, not cosmetic — structural equality
 * on the AST is what makes saved-search comparison and the test suite possible.
 *
 * @param tokens Condition tokens, with the `sort:`/`desc`/limit directives already removed.
 * @param conditionMatches Rules mapping a word to a [Condition]; first match wins.
 * @param parseDate Builds a `due:`/`scheduled:` condition from its prefix and value.
 */
class ConditionExprParser(
    private val tokens: List<QueryTokenizer.Token>,
    private val conditionMatches: List<QueryParser.ConditionMatch>,
    private val parseDate: (prefix: String, spec: String) -> Condition,
) {

    /** Parses the whole token list. Never throws — see G5 and G9. */
    fun parse(): Condition {
        if (tokens.isEmpty()) return Condition.And(emptyList()) // G7
        return parseDisjunction(0).condition
    }

    /** Grammar production: `disjunction := conjunction ( OR conjunction )*`. */
    private fun parseDisjunction(from: Int): Cursor {
        var cursor = parseConjunction(from)
        val parts = mutableListOf<Condition>(cursor.condition)
        while (cursor.peek() is QueryTokenizer.Token.Or) {
            cursor = cursor.advanced() // step over OR
            cursor = parseConjunction(cursor.position)
            parts += cursor.condition
        }
        return Cursor(flatten(parts, BoolOp.OR), cursor.position)
    }

    /** `conjunction := atom ( AND? atom )*` — note the optional `AND` (G2). */
    private fun parseConjunction(from: Int): Cursor {
        var cursor = parseAtom(from)
        val parts = mutableListOf<Condition>(cursor.condition)
        // The loop must continue on an explicit `AND` *and* on an implicit one. Testing only
        // `startsAtom()` — which by definition excludes `AND`, since AND is not an atom —
        // makes the explicit branch below unreachable, and `a AND b` silently parses as
        // `a`. That is what the first draft of this method did, and three tests caught it.
        while (cursor.peek()?.continuesConjunction() == true) {
            if (cursor.peek() is QueryTokenizer.Token.And) cursor = cursor.advanced()
            cursor = parseAtom(cursor.position)
            parts += cursor.condition
        }
        return Cursor(flatten(parts, BoolOp.AND), cursor.position)
    }

    /** Grammar production: `atom := NOT expression | '(' expression ')' | word | quoted`. */
    private fun parseAtom(from: Int): Cursor {
        if (from >= tokens.size) return Cursor(Condition.And(emptyList()), from)
        return when (val token = tokens[from]) {
            // G3 — the whole following expression, not just the next atom.
            is QueryTokenizer.Token.Not ->
                parseDisjunction(from + 1).let { Cursor(Condition.Not(it.condition), it.position) }

            is QueryTokenizer.Token.LParen -> {
                val inner = parseDisjunction(from + 1)
                // G9 — a missing `)` is tolerated; parse as far as we got.
                val afterClose = if (inner.peek() is QueryTokenizer.Token.RParen) {
                    inner.advanced().position
                } else {
                    inner.position
                }
                Cursor(inner.condition, afterClose)
            }

            // G5 — a word is a condition if a rule claims it, otherwise free text.
            is QueryTokenizer.Token.Word ->
                Cursor(conditionFor(token.text) ?: Condition.HasText(token.text), from + 1)

            // G6 — quoted is always free text, whatever it contains (R2).
            is QueryTokenizer.Token.Quoted -> Cursor(Condition.HasText(token.text), from + 1)

            else -> Cursor(Condition.And(emptyList()), from)
        }
    }

    /**
     * The first condition rule whose regex matches [word], or `null`.
     *
     * A rule that matches textually but then fails to build — `due:soon`, where `soon` is
     * not an interval — yields `null` rather than propagating, and the word falls through
     * to free text (D4). A search box that rejects input is a search box users stop using.
     */
    private fun conditionFor(word: String): Condition? {
        for (rule in conditionMatches) {
            val match = rule.regex.find(word) ?: continue
            val built = runCatching { rule.build(match) }.getOrNull()
            if (built != null) return built
        }
        return null
    }

    /** G8 — collapse nested same-operator nodes and single-element nodes. */
    private fun flatten(parts: List<Condition>, op: BoolOp): Condition {
        val flat = mutableListOf<Condition>()
        for (part in parts) {
            when {
                op == BoolOp.AND && part is Condition.And -> flat += part.parts
                op == BoolOp.OR && part is Condition.Or -> flat += part.parts
                else -> flat += part
            }
        }
        if (flat.isEmpty()) return Condition.And(emptyList()) // G7 — matches everything
        if (flat.size == 1) return flat[0]
        return if (op == BoolOp.AND) Condition.And(flat) else Condition.Or(flat)
    }

    /**
     * Which combinator is being flattened.
     *
     * An explicit enum rather than passing `(List<Condition>) -> Condition` and comparing
     * it with `===` against `Condition::And`. The first draft did the latter, and callable
     * references are not guaranteed to be reference-identical — so the comparison silently
     * never matched, nothing was ever flattened, and `parse("AND")` produced
     * `And([And([]), And([])])` instead of `And([])`. G8 is observable, so a flatten that
     * does not flatten is a correctness bug, not a tidy-up.
     */
    private enum class BoolOp { AND, OR }

    /**
     * Parse position: the condition produced so far, and the index of the next unconsumed
     * token.
     *
     * `inner` so [peek] can read the parser's [tokens] — a nested (non-inner) class has no
     * outer instance, and it is not `data` because `inner` and `data` cannot be combined.
     */
    private inner class Cursor(val condition: Condition, val position: Int) {
        fun peek(): QueryTokenizer.Token? = tokens.getOrNull(position)
        fun advanced(): Cursor = Cursor(condition, position + 1)
    }
}

/**
 * A token after which a conjunction may continue.
 *
 * True for an explicit [QueryTokenizer.Token.And] and for anything that can begin an atom
 * (implicit conjunction, G2). [QueryTokenizer.Token.Not] begins an atom, so `a NOT b` is
 * `AND(a, NOT(b))`. `null` — end of input — is neither, so a trailing `AND` ends the
 * conjunction instead of failing.
 *
 * Top-level rather than a member: an extension function inside a non-inner class cannot
 * take the outer class as its receiver, and this one needs nothing from the parser.
 */
private fun QueryTokenizer.Token?.continuesConjunction(): Boolean =
    this is QueryTokenizer.Token.And ||
        this is QueryTokenizer.Token.Word ||
        this is QueryTokenizer.Token.Quoted ||
        this is QueryTokenizer.Token.LParen ||
        this is QueryTokenizer.Token.Not
