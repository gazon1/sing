package com.singularity.todo.core.platform

import kotlinx.datetime.LocalDate

/**
 * Returns the number of days from today until [target].
 * Positive = future, negative = past, zero = today.
 */
fun daysUntil(target: LocalDate): Int {
    val today = todayInSystemZone()
    return (target.toEpochDays() - today.toEpochDays()).toInt()
}
