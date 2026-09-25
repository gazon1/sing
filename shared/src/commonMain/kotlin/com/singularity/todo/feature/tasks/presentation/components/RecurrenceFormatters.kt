package com.singularity.todo.feature.tasks.presentation.components

import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate

/**
 * Pure UI string formatters for [RecurrenceSpec].
 *
 * Internal — called only from UI/component layer.
 * All strings are user-visible labels (English).
 *
 * @see RecurrenceParser for the inverse operation (string → spec).
 */
object RecurrenceFormatters {

    private val WEEKDAY_NAMES = listOf(
        "",
        "Mon",
        "Tue",
        "Wed",
        "Thu",
        "Fri",
        "Sat",
        "Sun",
    )

    private val MONTH_NAMES = listOf(
        "", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /**
     * Short label suitable for a chip or badge (e.g. `"↑ Weekly"`).
     *
     * Uses the "↑" symbol to indicate "from due" mode for FROM_DUE/CATCH_UP,
     * and no symbol for FROM_COMPLETION (the most common mode).
     */
    fun label(spec: RecurrenceSpec): String {
        val prefix = when (spec.base) {
            RecurrenceBase.FROM_DUE -> "↑ "
            RecurrenceBase.FROM_COMPLETION -> ""
            RecurrenceBase.CATCH_UP -> "↺ "
        }
        val content = when (spec) {
            is Interval -> intervalLabel(spec.amount, spec.unit)

            is Weekly -> {
                val days = spec.weekdays.sorted()
                    .joinToString(",") { WEEKDAY_NAMES.getOrElse(it) { it.toString() } }
                "Weekly($days)"
            }

            is Monthly -> "Monthly(${spec.dayOfMonth})"

            is Yearly -> "Yearly(${MONTH_NAMES.getOrElse(spec.month) { spec.month.toString() }} ${spec.day})"
        }
        return prefix + content
    }

    private fun intervalLabel(amount: Int, unit: DateTimeUnit.DateBased): String = when (unit) {
        DateTimeUnit.DAY -> if (amount == 1) "Daily" else "Every $amount days"
        DateTimeUnit.WEEK -> if (amount == 1) "Weekly" else "Every $amount weeks"
        DateTimeUnit.MONTH -> if (amount == 1) "Monthly" else "Every $amount months"
        DateTimeUnit.YEAR -> if (amount == 1) "Yearly" else "Every $amount years"
        else -> "Every $amount days" // QUARTER, CENTURY, etc.
    }

    /**
     * Base mode label for the picker UI.
     */
    fun baseLabel(base: RecurrenceBase): String = when (base) {
        RecurrenceBase.FROM_DUE -> "From due date"
        RecurrenceBase.FROM_COMPLETION -> "From completion"
        RecurrenceBase.CATCH_UP -> "Catch-up (fill gaps)"
    }

    /**
     * Preview string showing the next occurrence given the anchor date.
     */
    fun nextOccurrencePreview(anchor: LocalDate, spec: RecurrenceSpec): String {
        val next = RecurrenceCalculator.nextOccurrence(spec, anchor)
        return "Next: $next"
    }
}
