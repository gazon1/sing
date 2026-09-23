package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.RecurrenceRule

/**
 * Best-effort parser: raw reminder recurrence pattern string → [RecurrenceRule].
 *
 * The raw pattern is a simplified cron-like string:
 * - `"DAILY"`                 → [RecurrenceRule.Daily]
 * - `"WEEKLY:MON,WED,FRI"`    → [RecurrenceRule.Weekly]([1, 3, 5])
 * - `"MONTHLY:15"`             → [RecurrenceRule.Monthly](15)
 * - `"YEARLY"`                → [RecurrenceRule.Yearly]
 * - `"0 9 * * 1-5"`           → [RecurrenceRule.Custom] (pass-through)
 * - anything else              → [RecurrenceRule.Custom]
 *
 * This is a **best-effort** mapping — if the pattern doesn't parse cleanly
 * into a sealed variant, it falls through to [Custom] and is passed verbatim.
 */
object RecurrenceRuleMapper {

    fun map(pattern: String): RecurrenceRule? {
        val trimmed = pattern.trim().uppercase()

        return when {
            trimmed == "DAILY" -> RecurrenceRule.Daily

            trimmed.startsWith("WEEKLY") -> {
                parseWeekdays(trimmed.removePrefix("WEEKLY:"))
                    ?.let { RecurrenceRule.Weekly(it) }
                    ?: RecurrenceRule.Custom(pattern)
            }

            trimmed.startsWith("MONTHLY") -> {
                trimmed.removePrefix("MONTHLY:")
                    .toIntOrNull()
                    ?.let { RecurrenceRule.Monthly(it) }
                    ?: RecurrenceRule.Custom(pattern)
            }

            trimmed == "YEARLY" -> RecurrenceRule.Yearly

            // Pass through any unrecognized pattern verbatim
            else -> RecurrenceRule.Custom(pattern)
        }
    }

    /**
     * Parses a comma-separated list of weekday names or numbers.
     * Accepts: "MON,WED,FRI", "1,3,5", "MONDAY,WEDNESDAY,FRIDAY"
     */
    private fun parseWeekdays(part: String): List<Int>? {
        if (part.isBlank()) return null
        val result = mutableListOf<Int>()
        for (token in part.split(",")) {
            val day = when (token.trim().lowercase()) {
                "mon", "monday", "1" -> 1
                "tue", "tuesday", "2" -> 2
                "wed", "wednesday", "3" -> 3
                "thu", "thursday", "4" -> 4
                "fri", "friday", "5" -> 5
                "sat", "saturday", "6" -> 6
                "sun", "sunday", "7" -> 7
                else -> return null
            }
            result.add(day)
        }
        return result.distinct().sorted()
    }
}
