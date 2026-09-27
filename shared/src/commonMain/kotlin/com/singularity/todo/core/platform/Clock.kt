package com.singularity.todo.core.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Emits the current [LocalDate] in [zone] immediately, then re-emits exactly once
 * at each local midnight.
 *
 * The flow is cold — no work happens until a collector is active. Back-pressure
 * is handled naturally by [delay].
 *
 * ## Time zone is captured at collection time
 * [zone] is resolved once when collection starts. If the device time zone changes
 * while the flow is active (user travels, changes OS settings), the flow continues
 * using the original zone. Re-collect after a time zone change if up-to-date
 * behavior is required.
 *
 * @param zone The time zone used to compute midnight. Defaults to system default.
 */
fun todayFlow(zone: TimeZone = TimeZone.currentSystemDefault()): Flow<LocalDate> = flow {
    while (true) {
        val current = todayAt(zone)
        emit(current)
        val delayMs = delayUntilNextMidnight(current, zone)
        // Guards against zero/negative delay from a clock adjustment; the loop
        // will recompute the date and re-emit only if it actually changed.
        if (delayMs > 0) {
            delay(delayMs.milliseconds)
        }
    }
}.distinctUntilChanged()

/** Returns today's [LocalDate] in [zone]. */
internal fun todayAt(zone: TimeZone): LocalDate = Clock.System.now()
    .toLocalDateTime(zone).date

/**
 * Computes milliseconds until the next local midnight after [today] in [zone].
 *
 * Uses [LocalDate.atStartOfDayIn] so that DST transitions (23/25-hour days) and
 * non-UTC offsets are handled correctly.
 *
 * @param today must be the current local date in [zone] at the time of the call.
 */
private fun delayUntilNextMidnight(today: LocalDate, zone: TimeZone): Long {
    val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY)
        .atStartOfDayIn(zone)
    val now = Clock.System.now()
    return (tomorrowMidnight - now).inWholeMilliseconds
}

fun todayInSystemZone(): LocalDate = todayAt(TimeZone.currentSystemDefault())
