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

    // From-due (+1w) or catch-up (!+1w) short forms.
    // Group 1: leading dot (.)
    // Group 2: one or two + signs (++ = FROM_DUE, + = FROM_COMPLETION or CATCH_UP if ! prefix)
    // Group 3: amount
    // Group 4: unit
    private val SHORT_FORM = Regex(
        """^([.!]?)(\++)(\d+)([dwmy])$"""
    )

    // Catch-up prefix: ! before the + signs
    private val CATCH_UP_SHORT_FORM = Regex(
        """^(!)(\++)(\d+)([dwmy])$"""
    )

    private val EVERY_WEEKDAY = Regex(
        """^every\s+([A-Za-z, ]+)$"""
    )

    private val EVERY_INTERVAL = Regex(
        """^every\s+(\d+)?\s*(days?|weeks?|months?|years?)$"""
    )

    private val MONTHLY_ORDINAL = Regex(
        """^(\d+)(st|nd|rd|th)\s+of\s+month$"""
    )

    private val YEARLY_DATE = Regex(
        """^every\s+([A-Za-z]+)\s+(\d+)$"""
    )

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

        // 1. Short form: .+1w, ++1w, +1d, !+1w, etc.
        parseShortForm(s)?.let { return it }

        // 2. Every weekday: "every Mon,Wed,Fri"
        parseEveryWeekday(s)?.let { return it }

        // 3. Every N days/weeks/months/years
        parseEveryInterval(s)?.let { return it }

        // 4. Monthly: "1st of month"
        parseMonthly(s)?.let { return it }

        // 5. Yearly: "every Jan 1"
        parseYearly(s)?.let { return it }

        throw IllegalArgumentException("Unrecognised recurrence rule: '$s'")
    }

    private fun parseShortForm(s: String): RecurrenceSpec? {
        // Try catch-up form first: !+1w, !++1w
        val catchUpMatch = CATCH_UP_SHORT_FORM.matchEntire(s)
        if (catchUpMatch != null) {
            val amount = catchUpMatch.groupValues[3].toInt()
            val unit = catchUpMatch.groupValues[4]
            val dtu = parseUnit(unit)
            return RecurrenceSpec.Interval(RecurrenceBase.CATCH_UP, amount, dtu)
        }

        // Try normal/from-due form: +1w, ++1w, .+1m, .++1m
        val m = SHORT_FORM.matchEntire(s) ?: return null
        val signPart = m.groupValues[2] // "+" or "++"
        val amount = m.groupValues[3].toInt()
        val unit = m.groupValues[4]

        val base: RecurrenceBase = when (signPart) {
            "++" -> RecurrenceBase.FROM_DUE
            else -> RecurrenceBase.FROM_COMPLETION
        }

        val dtu: DateTimeUnit.DateBased = parseUnit(unit)

        return RecurrenceSpec.Interval(base, amount, dtu)
    }

    private fun parseUnit(unit: String): DateTimeUnit.DateBased {
        return when (unit) {
            "d" -> DateTimeUnit.DAY
            "w" -> DateTimeUnit.WEEK
            "m" -> DateTimeUnit.MONTH
            "y" -> DateTimeUnit.YEAR
            else -> throw IllegalArgumentException("Unknown unit: $unit")
        }
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

        val dtu: DateTimeUnit.DateBased = when (unitStr.removeSuffix("s")) {
            "day" -> DateTimeUnit.DAY
            "week" -> DateTimeUnit.WEEK
            "month" -> DateTimeUnit.MONTH
            "year" -> DateTimeUnit.YEAR
            else -> return null
        }

        return Interval(RecurrenceBase.FROM_DUE, amount, dtu)
    }

    private fun parseMonthly(s: String): RecurrenceSpec? {
        val m = MONTHLY_ORDINAL.matchEntire(s.lowercase()) ?: return null
        val day = m.groupValues[1].toInt()
        return Monthly(RecurrenceBase.FROM_DUE, day)
    }

    private fun parseYearly(s: String): RecurrenceSpec? {
        val m = YEARLY_DATE.matchEntire(s.lowercase()) ?: return null
        val monthName = m.groupValues[1]
        val day = m.groupValues[2].toInt()
        val month = MONTH_MAP[monthName] ?: return null
        return Yearly(RecurrenceBase.FROM_DUE, month, day)
    }
}
