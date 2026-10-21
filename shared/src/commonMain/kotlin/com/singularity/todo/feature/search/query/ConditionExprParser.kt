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
 * Grammar, with precedence `NOT` > `AND` > `OR`:
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
 * **G1 — precedence is `NOT` > `AND` > `OR`.** `a or b and c` is `OR(a, AND(b, c))`, not
 * `AND(OR(a, b), c)`.
 *
 * **G2 — conjunction is implicit.** `due:today priority:high` is two atoms, not a syntax
 * error. This is the shape users actually type, and requiring an explicit `AND` would
 * make the language read like a programming language rather than a search box. The loop
 * in [parseConjunction] therefore continues on a bare `Word`/`Quoted`/`LParen`/`Not`
 * token, not only on an explicit [QueryTokenizer.Token.And].
 *
 * **G3 — `NOT` binds the whole expression that follows it.** `NOT a OR b` is
 * `NOT(OR(a, b))`, not `OR(NOT(a), b)`. The obvious recursive-descent shortcut — making
 * `NOT` recurse into the next *atom* — silently returns a superset of what the user asked
 * for, which is the worst failure mode a filter can have. [parseAtom] therefore recurses
 * into [parseDisjunction].
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
        // G7 — an empty conjunction is a no-op, and matches everything.
        if (tokens.isEmpty()) return Condition.And(emptyList())
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
            // G3 — `NOT` binds the whole expression that follows it. The expression,
            // not just the next atom.
            is QueryTokenizer.Token.Not ->
                parseDisjunction(from + 1).let { Cursor(Condition.Not(it.condition), it.position) }

            is QueryTokenizer.Token.LParen -> {
                val inner = parseDisjunction(from + 1)
                // G4 — parentheses override precedence. The group is parsed as a
                // whole expression, so `(a or b) and c` is `AND(OR(a, b), c)`.
                // G9 — an unclosed `(` is tolerated. The expression is parsed as far
                // as it goes.
                val afterClose = if (inner.peek() is QueryTokenizer.Token.RParen) {
                    inner.advanced().position
                } else {
                    inner.position
                }
                Cursor(inner.condition, afterClose)
            }

            // G5 — a word that matches no condition rule is free text. A rule that
            // claims it makes it a condition instead.
            is QueryTokenizer.Token.Word ->
                Cursor(conditionFor(token.text) ?: Condition.HasText(token.text), from + 1)

            // G6 — a quoted string is always free text, whatever it contains (R2).
            // F1 — the free text of a query is a list of terms. A quoted string is one
            // term, however many words it holds.
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

    /**
     * G8 — nested same-operator nodes are flattened.
     *
     * `AND(a, AND(b, c))` becomes `AND(a, b, c)`, and a one-element node collapses to
     * that element.
     */
    private fun flatten(parts: List<Condition>, op: BoolOp): Condition {
        val flat = mutableListOf<Condition>()
        for (part in parts) {
            when {
                op == BoolOp.AND && part is Condition.And -> flat += part.parts
                op == BoolOp.OR && part is Condition.Or -> flat += part.parts
                else -> flat += part
            }
        }
        val merged = if (op == BoolOp.AND) mergeTagConditions(flat) else flat
        // G7 — an empty conjunction is a no-op, and matches everything.
        if (merged.isEmpty()) return Condition.And(emptyList())
        if (merged.size == 1) return merged[0]
        return if (op == BoolOp.AND) Condition.And(merged) else Condition.Or(merged)
    }

    /**
     * C1 — `tag:` accumulates.
     *
     * `tag:work tag:urgent` is one `HasAllTags`, not an implicit `AND` of two
     * `HasTag`s. The distinction is visible in the AST and it matters to saved
     * searches: a saved search compares conditions, and two conditions that mean the
     * same thing are two conditions that compare unequal.
     *
     * ## What is merged, and what is not
     *
     * Only the *direct* tag conditions of one conjunction are merged, and only where
     * the merge cannot change what the query means:
     *
     * - A negated tag is a `Not(HasTag)`, and `Not` is not a tag condition — so
     *   `-tag:work tag:urgent` keeps two conditions. Merging them would say "has both
     *   tags", which is the opposite of what the query says.
     * - A disjunction is not a conjunction — `tag:work OR tag:urgent` is two
     *   alternatives, and merging them would turn "either" into "both".
     * - A single `tag:` stays a `HasTag`. The rule is about accumulation: one name is
     *   not an accumulation.
     *
     * The merge is a normalisation, not a new meaning: `AND(HasTag(a), HasTag(b))` and
     * `HasAllTags({a, b})` select the same tasks, so the query means the same thing
     * before and after.
     */
    private fun mergeTagConditions(parts: List<Condition>): List<Condition> {
        val tagParts = parts.filter { it is Condition.HasTag || it is Condition.HasAllTags }
        if (tagParts.size < 2) return parts

        val names = linkedSetOf<String>()
        for (part in tagParts) {
            when (part) {
                is Condition.HasTag -> names += part.tagName
                is Condition.HasAllTags -> names += part.tagNames
                else -> Unit
            }
        }
        val merged = Condition.HasAllTags(names)

        val result = mutableListOf<Condition>()
        var replaced = false
        for (part in parts) {
            if (part is Condition.HasTag || part is Condition.HasAllTags) {
                if (!replaced) {
                    result += merged
                    replaced = true
                }
            } else {
                result += part
            }
        }
        return result
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
