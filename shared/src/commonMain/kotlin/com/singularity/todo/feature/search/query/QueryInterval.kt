package com.singularity.todo.feature.search.query

/**
 * A relative time interval expressed as a signed number of days.
 *
 * Positive values represent future intervals (e.g. `+3d`), negative values
 * represent past intervals (e.g. `-1w`). Zero represents "today".
 *
 * Parsed from user input such as `3d`, `-1w`, `today`, `tomorrow`, etc.
 *
 * @property days The signed day count. Positive = future, negative = past, 0 = today.
 */
@JvmInline
value class QueryInterval internal constructor(private val _days: Int) {

    companion object {
        /** Sentinel representing "no date constraint" — matches all dates. */
        val NONE = QueryInterval(INT_MIN)
        val NOW = QueryInterval(0)

        // Named aliases — all resolve to a concrete 0-day offset
        val TODAY = NOW
        val TOMORROW = QueryInterval(1)
        val YESTERDAY = QueryInterval(-1)

        private const val INT_MIN = Int.MIN_VALUE

        /**
         * Parse a relative interval string.
         *
         * Supported forms:
         * - Numeric with unit suffix: `3d`, `-5d`, `1w`, `-2w`, `1m`, `3m`, `1y`, `-1y`
         * - Named aliases: `today` / `tod`, `tomorrow` / `tom`, `yesterday`, `now`
         * - `none` / `no` → [NONE] (sentinel: no date filter)
         *
         * @return The parsed interval, or `null` if the string does not match any known form.
         */
        fun parse(s: String): QueryInterval? {
            val input = s.trim().lowercase()
            return when (input) {
                "now", "today", "tod" -> NOW
                "tomorrow", "tom" -> TOMORROW
                "yesterday" -> YESTERDAY
                "none", "no" -> NONE
                else -> parseNumeric(input)
            }
        }

        private fun parseNumeric(s: String): QueryInterval? {
            val m = NUMERIC_REGEX.matchEntire(s) ?: return null
            val value = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2]
            val days = when (unit) {
                "d" -> value
                "w" -> value * 7
                "m" -> value * 30
                "y" -> value * 365
                else -> return null
            }
            return QueryInterval(days)
        }

        private val NUMERIC_REGEX = Regex("""^([+-]?\d+)([dwmy])$""")
    }

    /** Returns the signed day count. */
    val days: Int get() = _days

    /** Returns `true` if this is the [NONE] sentinel (no date constraint). */
    val isNone: Boolean get() = this == NONE
}
