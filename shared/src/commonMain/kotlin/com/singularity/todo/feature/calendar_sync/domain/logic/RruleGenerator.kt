package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.RecurrenceRule

/**
 * Pure RRULE string generator: [RecurrenceRule] → RFC 5545 RRULE string.
 *
 * Only generates standard RRULE; the [Custom] variant is returned as-is.
 */
object RruleGenerator {

    /**
     * Converts [rule] to an RRULE string suitable for Android CalendarContract.
     * Returns null only when [rule] is [RecurrenceRule.Custom] with a blank string
     * or when [rule] is [RecurrenceRule.Weekly] with an unrecognized weekday code.
     */
    fun generate(rule: RecurrenceRule): String? {
        return when (rule) {
            is RecurrenceRule.Daily -> "FREQ=DAILY"

            is RecurrenceRule.Weekly -> {
                val symbols = rule.weekdays.mapNotNull { ISO_DAY_SYMBOL[it] }
                if (symbols.size != rule.weekdays.size) null
                else "FREQ=WEEKLY;BYDAY=${symbols.joinToString(",")}"
            }

            is RecurrenceRule.Monthly -> "FREQ=MONTHLY;BYMONTHDAY=${rule.day}"

            is RecurrenceRule.Yearly -> "FREQ=YEARLY"

            is RecurrenceRule.Custom -> {
                if (rule.rrule.isBlank()) null else rule.rrule
            }
        }
    }

    private val ISO_DAY_SYMBOL = mapOf(
        1 to "MO",
        2 to "TU",
        3 to "WE",
        4 to "TH",
        5 to "FR",
        6 to "SA",
        7 to "SU",
    )
}
