package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit

/**
 * Parses Orgzly / Tasks.org compatible recurrence DSL strings into [RecurrenceSpec].
 *
 * ## Grammar (declarative)
 *
 * Rules are declared as named [Regex] constants with explicit capture groups.
 * The [parse] function applies them in priority order.
 *
 * ```
 * GRAMMAR
 *   = CATCH_UP        : '!' '+'+  NUMBER UNIT
 *   | EVERY_WEEKDAY   : 'every' WEEKDAY (',' WEEKDAY)*
 *   | EVERY_INTERVAL  : 'every' [NUMBER] INTERVAL_UNIT
 *   | MONTHLY_ORDINAL : NUMBER ORD_SUFFIX 'of' 'month'
 *   | YEARLY_DATE     : 'every' MONTH_NAME NUMBER
 *   | SHORT_INTERVAL  : ['.'] '+'+ NUMBER UNIT
 *
 * WEEKDAY       = 'monday'..'sunday' | 'mon'..'sun'
 * INTERVAL_UNIT = 'day'['s'] | 'week'['s'] | 'month'['s'] | 'year'['s']
 * MONTH_NAME    = 'january'..'december' | 'jan'..'dec'
 * ORD_SUFFIX    = 'st' | 'nd' | 'rd' | 'th'
 * UNIT          = 'd' | 'w' | 'm' | 'y'
 * NUMBER        = [0-9]+
 * ```
 *
 * ## Supported syntax
 *
 * | Input | Output |
 * |-------|--------|
 * | `+1d` | Interval(FROM_COMPLETION, 1, DAY) |
 * | `+1w` | Interval(FROM_COMPLETION, 1, WEEK) |
 * | `+1m` | Interval(FROM_COMPLETION, 1, MONTH) |
 * | `+1y` | Interval(FROM_COMPLETION, 1, YEAR) |
 * | `++1w` | Interval(FROM_DUE, 1, WEEK) |
 * | `!+1w` | Interval(CATCH_UP, 1, WEEK) |
 * | `.+1m` | Interval(FROM_COMPLETION, 1, MONTH) |
 * | `.++1m` | Interval(FROM_DUE, 1, MONTH) |
 * | `every Mon,Wed,Fri` | Weekly(FROM_DUE, [1,3,5]) |
 * | `every week` | Interval(FROM_DUE, 1, WEEK) |
 * | `every 2 weeks` | Interval(FROM_DUE, 2, WEEK) |
 * | `every 3 days` | Interval(FROM_DUE, 3, DAY) |
 * | `1st of month` | Monthly(FROM_DUE, 1) |
 * | `15th of month` | Monthly(FROM_DUE, 15) |
 * | `every Jan 1` | Yearly(FROM_DUE, 1, 1) |
 * | `every December 25` | Yearly(FROM_DUE, 12, 25) |
 *
 * @throws IllegalArgumentException if the string does not match a known pattern.
 */
object RecurrenceParser {

    // ---------------------------------------------------------------------------
    // Grammar rules — declarative Regex with explicit capture groups
    // ---------------------------------------------------------------------------

    /**
     * `!+1w`, `!++1w` → CATCH_UP
     * Groups: (1) '!', (2) '+' signs, (3) amount, (4) unit
     */
    private val CATCH_UP = Regex("""^(!)(\++)(\d+)([dwmy])$""")

    /**
     * `.+1w`, `++1w`, `+1d`, `.++1m` → FROM_COMPLETION or FROM_DUE
     * Groups: (1) '.' or empty, (2) '+' signs, (3) amount, (4) unit
     */
    private val SHORT_INTERVAL = Regex("""^([.!]?)(\++)(\d+)([dwmy])$""")

    /**
     * `every Mon,Wed,Fri` → Weekly(FROM_DUE, weekdays)
     * Groups: (1) comma-separated weekday names
     */
    private val EVERY_WEEKDAY = Regex("""^every\s+([A-Za-z, ]+)$""")

    /**
     * `every week`, `every 2 weeks`, `every 3 days` → Interval(FROM_DUE)
     * Groups: (1) optional number, (2) unit word
     */
    private val EVERY_INTERVAL = Regex("""^every\s+(\d+)?\s*(days?|weeks?|months?|years?)$""")

    /**
     * `1st of month`, `15th of month` → Monthly(FROM_DUE)
     * Groups: (1) day number, (2) ordinal suffix
     */
    private val MONTHLY_ORDINAL = Regex("""^(\d+)(st|nd|rd|th)\s+of\s+month$""")

    /**
     * `every Jan 1`, `every December 25` → Yearly(FROM_DUE)
     * Groups: (1) month name, (2) day number
     */
    private val YEARLY_DATE = Regex("""^every\s+([A-Za-z]+)\s+(\d+)$""")

    // ---------------------------------------------------------------------------
    // Lookup tables — used by rule parsers
    // ---------------------------------------------------------------------------

    private val WEEKDAY_MAP = mapOf(
        "monday" to 1, "mon" to 1,
        "tuesday" to 2, "tue" to 2, "tues" to 2,
        "wednesday" to 3, "wed" to 3,
        "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4,
        "friday" to 5, "fri" to 5,
        "saturday" to 6, "sat" to 6,
        "sunday" to 7, "sun" to 7,
    )

    private val MONTH_MAP = mapOf(
        "january" to 1, "jan" to 1,
        "february" to 2, "feb" to 2,
        "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4,
        "may" to 5,
        "june" to 6, "jun" to 6,
        "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8,
        "september" to 9, "sep" to 9, "sept" to 9,
        "october" to 10, "oct" to 10,
        "november" to 11, "nov" to 11,
        "december" to 12, "dec" to 12,
    )

    // ---------------------------------------------------------------------------
    // Parser — applies grammar rules in priority order
    // ---------------------------------------------------------------------------

    /**
     * Parses a recurrence DSL string.
     *
     * @param input A non-blank DSL string (e.g. `"+1w"`, `"every Mon,Wed,Fri"`).
     * @return The parsed [RecurrenceSpec].
     * @throws IllegalArgumentException if [input] is blank or unrecognised.
     */
    fun parse(input: String): RecurrenceSpec {
        val s = input.trim()
        require(s.isNotBlank()) { "Recurrence rule must not be blank" }

        // Priority order from GRAMMAR section above
        parseCatchUp(s)?.let { return it }
        parseEveryWeekday(s)?.let { return it }
        parseEveryInterval(s)?.let { return it }
        parseMonthlyOrdinal(s)?.let { return it }
        parseYearlyDate(s)?.let { return it }
        parseShortInterval(s)?.let { return it }

        throw IllegalArgumentException("Unrecognised recurrence rule: '$s'")
    }

    // ---------------------------------------------------------------------------
    // Rule parsers — each matches one GRAMMAR production
    // ---------------------------------------------------------------------------

    private fun parseCatchUp(s: String): RecurrenceSpec? {
        val m = CATCH_UP.matchEntire(s) ?: return null
        val amount = m.groupValues[3].toInt()
        val unit = parseUnit(m.groupValues[4])
        return Interval(RecurrenceBase.CATCH_UP, amount, unit)
    }

    private fun parseShortInterval(s: String): RecurrenceSpec? {
        val m = SHORT_INTERVAL.matchEntire(s) ?: return null
        val pluses = m.groupValues[2] // "+" or "++"
        val amount = m.groupValues[3].toInt()
        val unit = parseUnit(m.groupValues[4])
        // The dot prefix is captured but does NOT change the base.
        // ++ always means FROM_DUE; single + always means FROM_COMPLETION.
        val base = if (pluses == "++") RecurrenceBase.FROM_DUE else RecurrenceBase.FROM_COMPLETION
        return Interval(base, amount, unit)
    }

    private fun parseEveryWeekday(s: String): RecurrenceSpec? {
        val m = EVERY_WEEKDAY.matchEntire(s) ?: return null
        val names = m.groupValues[1].split(",").map { it.trim().lowercase() }
        val weekdays = names.mapNotNull { WEEKDAY_MAP[it] }.toSet()
        if (weekdays.isEmpty()) return null
        return Weekly(RecurrenceBase.FROM_DUE, weekdays)
    }

    private fun parseEveryInterval(s: String): RecurrenceSpec? {
        val m = EVERY_INTERVAL.matchEntire(s) ?: return null
        val numStr = m.groupValues[1]
        val unitStr = m.groupValues[2].lowercase()
        val amount = if (numStr.isBlank()) 1 else numStr.toInt()
        if (amount <= 0) return null
        val unit = parseIntervalUnit(unitStr) ?: return null
        return Interval(RecurrenceBase.FROM_DUE, amount, unit)
    }

    private fun parseMonthlyOrdinal(s: String): RecurrenceSpec? {
        val m = MONTHLY_ORDINAL.matchEntire(s.lowercase()) ?: return null
        val day = m.groupValues[1].toInt()
        return Monthly(RecurrenceBase.FROM_DUE, day)
    }

    private fun parseYearlyDate(s: String): RecurrenceSpec? {
        val m = YEARLY_DATE.matchEntire(s.lowercase()) ?: return null
        val monthName = m.groupValues[1]
        val day = m.groupValues[2].toInt()
        val month = MONTH_MAP[monthName] ?: return null
        return Yearly(RecurrenceBase.FROM_DUE, month, day)
    }

    // ---------------------------------------------------------------------------
    // Unit helpers
    // ---------------------------------------------------------------------------

    private fun parseUnit(s: String): DateTimeUnit.DateBased = when (s) {
        "d" -> DateTimeUnit.DAY
        "w" -> DateTimeUnit.WEEK
        "m" -> DateTimeUnit.MONTH
        "y" -> DateTimeUnit.YEAR
        else -> throw IllegalArgumentException("Unknown unit: $s")
    }

    private fun parseIntervalUnit(s: String): DateTimeUnit.DateBased? = when (s.removeSuffix("s")) {
        "day"   -> DateTimeUnit.DAY
        "week"  -> DateTimeUnit.WEEK
        "month" -> DateTimeUnit.MONTH
        "year"  -> DateTimeUnit.YEAR
        else -> null
    }
}
