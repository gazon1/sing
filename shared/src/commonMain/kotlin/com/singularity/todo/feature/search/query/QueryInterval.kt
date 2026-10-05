package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — reimplemented from
//   docs/specs/search-query-grammar.md per option A1. The ported implementation is gone.
//
//   NOT a clean room: the author read the Orgzly source. A1 removes verbatim
//   correspondence; it does not terminate a derivation. See the honesty clause in the
//   spec and docs/legal/PROVENANCE.md


/**
 * A relative time interval, as a signed whole number of days — implementation of §5 of
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
 * @property days Signed day count: positive is the future, negative the past, zero today.
 */
@JvmInline
value class QueryInterval internal constructor(private val _days: Int) {

    val days: Int get() = _days

    /** `true` when this is the [NONE] sentinel — "no date constraint". */
    val isNone: Boolean get() = this == NONE

    companion object {
        /** Unconstrained. Distinct from [NOW] — see D3. */
        val NONE = QueryInterval(Int.MIN_VALUE)
        val NOW = QueryInterval(0)
        val TODAY = NOW
        val TOMORROW = QueryInterval(1)
        val YESTERDAY = QueryInterval(-1)

        /**
         * Parses an interval, or returns `null` if [raw] matches no known form.
         *
         * `null` is not an error: the caller falls back to free text (D4), so `due:soon`
         * searches for the literal text `due:soon` rather than failing.
         */
        fun parse(raw: String): QueryInterval? {
            val input = raw.trim().lowercase()
            return when (input) {
                "now", "today", "tod" -> NOW
                "tomorrow", "tom" -> TOMORROW
                "yesterday" -> YESTERDAY
                "none", "no" -> NONE
                else -> fromSignedNumber(input)
            }
        }

        /**
         * `<signed number><unit>` — D1/D2.
         *
         * Unit lengths are fixed: a month is 30 days and a year is 365, deliberately.
         * Calendar-aware arithmetic was rejected because it would make the parsed value
         * depend on the date of parsing, so `due:1m` would mean different things in
         * different months and a saved search would not be reproducible.
         */
        private fun fromSignedNumber(input: String): QueryInterval? {
            val match = SIGNED_NUMBER_UNIT.matchEntire(input) ?: return null
            val magnitude = match.groupValues[1].toIntOrNull() ?: return null
            val days = when (match.groupValues[2]) {
                "d" -> magnitude
                "w" -> magnitude * 7
                "m" -> magnitude * 30
                "y" -> magnitude * 365
                else -> return null // unreachable: the regex admits only d/w/m/y
            }
            return QueryInterval(days)
        }

        private val SIGNED_NUMBER_UNIT = Regex("""^([+-]?\d+)([dwmy])$""")
    }
}
