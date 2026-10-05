package com.singularity.todo.core.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

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

/** Returns today's [LocalDate] in [zone], read from [clock]. */
fun todayAt(clock: Clock, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    clock.now().toLocalDateTime(zone).date

/** Returns today's [LocalDate] in [zone]. */
// NoDirectClockSystemRule exemption: this is the intentional single call site.
// If you move this function, update isAllowedFile() in NoDirectClockSystemRule.kt.
internal fun todayAt(zone: TimeZone): LocalDate = todayAt(Clock.System, zone)

/** The local wall-clock time at [instant] in [zone]. */
fun localTimeAt(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime =
    instant.toLocalDateTime(zone)

/**
 * The current local wall-clock time, to the second.
 *
 * For the places that stamp a name onto something — an exported log bundle, a
 * filename — where a [LocalDate] is too coarse but a full timestamp is noise.
 *
 * A caller that needs the clock *injected* so a test can assert the stamp should
 * take a [Clock] itself and use [localTimeAt]: "now" for a log file name is a
 * property of the moment the export ran, not a dependency of the exporter, and
 * asserting it is the test's job rather than the production object's.
 *
 * NoDirectClockSystemRule exemption: this is the intentional single call site.
 * If you move this function, update isAllowedFile() in NoDirectClockSystemRule.kt.
 */
fun nowInSystemZone(): LocalDateTime = localTimeAt(Clock.System.now())

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
