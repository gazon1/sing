package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — reimplemented from
//   docs/specs/search-query-grammar.md per option A1. The ported implementation is gone.
//
//   NOT a clean room: the author read the Orgzly source. A1 removes verbatim
//   correspondence; it does not terminate a derivation. See the honesty clause in the
//   spec and docs/legal/PROVENANCE.md

/**
 * Base of the search query parser: supplies the token stream and the rule tables, and
 * declares the entry point.
 *
 * The expression grammar lives in [ConditionExprParser] and the concrete word rules in
 * [SingularityQueryParser]; this class exists to give both a shared vocabulary and a
 * single abstract entry point.
 *
 * Implements the structure described by §2 and §3 of
 * [docs/specs/search-query-grammar.md][spec].
 *
 * [spec]: ../../../../../../../docs/specs/search-query-grammar.md
 *
 * ## Provenance
 *
 * The previous implementation was ported from Orgzly (GPL-3.0). This one was written from
 * the specification above with the behavioural suite as the contract — option **A1** in
 * `docs/legal/PROVENANCE.md`. It is not a clean room; see the honesty clause in the
 * specification.
 *
 * ## A vestigial second parser was removed
 *
 * The ported version carried a private `parseAtom` / `parseGroup` pair — 105 lines
 * implementing the same grammar a *second* time, reachable only from each other, because
 * `parse()` threw [UnsupportedOperationException] and every real caller overrode it. It
 * was not merely dead: it **disagreed** with the live parser. It threw on an unclosed
 * parenthesis (contradicting G9), required at least two operands for an `OR`, and made
 * `NOT` bind only the following atom (contradicting G3).
 *
 * Leaving it in place would have been worse than leaving it out. A second implementation
 * of a grammar that contradicts the first is not dead code, it is a trap with a
 * comment above it. It is gone, and this paragraph is the only thing left of it.
 *
 * @param tokens Pre-tokenized input from [QueryTokenizer].
 */
abstract class QueryParser(protected val tokens: List<QueryTokenizer.Token>) {

    /** Word rules that produce a [Condition]; first match wins (§4). */
    protected abstract val conditionMatches: List<ConditionMatch>

    /** `sort:` rules (§2). */
    protected abstract val sortOrderMatches: List<SortOrderMatch>

    /** `limit:`/`offset:` rules (§2). */
    protected abstract val optionMatches: List<OptionMatch>

    /**
     * Parses the token stream into a [Query].
     *
     * Abstract in practice: [SingularityQueryParser] is the only implementation, and it
     * does the work by delegating to [ConditionExprParser]. Throwing here rather than
     * providing a default keeps a half-parseable base class from being instantiable by
     * accident.
     */
    open fun parse(): Query = throw UnsupportedOperationException(
        "QueryParser.parse() must be overridden by a concrete implementation",
    )

    // ─── Match data classes ─────────────────────────────────────────────────

    /**
     * A regex that produces a [Condition] when it matches a word.
     *
     * [build] is allowed to fail — a rule can match textually and then reject the value,
     * as `due:` does for `due:soon`. The caller treats that as "this rule does not apply"
     * and lets the word fall through to free text (D4).
     */
    data class ConditionMatch(val regex: Regex, val build: (MatchResult) -> Condition)

    /** A regex that selects a [SortOrder]. */
    data class SortOrderMatch(val regex: Regex, val sortOrder: SortOrder)

    /** A regex that adjusts [Options]. */
    data class OptionMatch(val regex: Regex, val apply: (MatchResult, Options) -> Options)
}
