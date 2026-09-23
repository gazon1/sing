package com.singularity.todo.core.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object Clock {
    fun now(): Instant
}

expect fun todayInSystemZone(): LocalDate

expect val isDesktop: Boolean

/**
 * Emits the current [LocalDate] in [zone] and re-emits exactly once each calendar
 * midnight, then suspends until the next midnight.
 *
 * The flow is cold — no work happens until a collector is active.
 * Back-pressure is handled naturally by [delay].
 *
 * @param zone The time zone used to compute midnight. Defaults to system default.
 */
fun todayFlow(zone: TimeZone = TimeZone.currentSystemDefault()): Flow<LocalDate> = flow {
    var current = todayAt(zone)
    while (true) {
        emit(current)
        val delayMs = delayUntilNextMidnight(current, zone)
        if (delayMs > 0) {
            delay(delayMs.milliseconds)
        }
        current = todayAt(zone)
    }
}.distinctUntilChanged()

/** Returns today's [LocalDate] in [zone] using the standard library [Clock.System]. */
private fun todayAt(zone: TimeZone): LocalDate = Clock.System.now().toLocalDateTime(zone).date

/**
 * Computes milliseconds until the next local midnight after [today].
 * [today] must be the current local date at the time of the call.
 */
private fun delayUntilNextMidnight(today: LocalDate, zone: TimeZone): Long {
    // Tomorrow at midnight in the zone, expressed as kotlinx-datetime epoch ms.
    val tomorrowMidnight = (today.toEpochDays() + 1L) * 86_400_000L
    val nowMillis = Clock.System.now().toEpochMilliseconds()
    return tomorrowMidnight - nowMillis
}
