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
 * ## Why both parameters are required
 * The zone used to be defaulted and the clock was read from `Clock.System` through
 * the `todayAt(zone)` overload (#91), so this flow was a second, un-injectable
 * source of "today" — and `AgendaViewModel` composes its whole task stream from it.
 * A task dated tomorrow relative to a test's pinned clock was bucketed against the
 * host's date and matched no section at all.
 *
 * @param zone The time zone used to compute midnight.
 */
fun todayFlow(clock: Clock, zone: TimeZone): Flow<LocalDate> = flow {
    while (true) {
        val current = todayAt(clock, zone)
        emit(current)
        val delayMs = delayUntilNextMidnight(clock, current, zone)
        // Guards against zero/negative delay from a clock adjustment; the loop
        // will recompute the date and re-emit only if it actually changed.
        if (delayMs > 0) {
            delay(delayMs.milliseconds)
        }
    }
}.distinctUntilChanged()

/**
 * Today's [LocalDate] in [zone], read from [clock].
 *
 * **Both parameters are required, and that is the point** (2026-10-05). This used
 * to default [zone] to `TimeZone.currentSystemDefault()`, which meant a caller
 * could inject a clock and still get a date that moved with the machine's
 * region: deterministic over time, non-deterministic over machines. A scenario
 * asserting "this is in the Today bucket" needs both, and a default is a way of
 * forgetting the second one.
 *
 * Use [systemToday] where the system zone really is the answer — a preview, a
 * @Preview-only fixture, a log line. Its name says so, which is what
 * [todayInSystemZone] never did.
 */
fun todayAt(clock: Clock, zone: TimeZone): LocalDate =
    clock.now().toLocalDateTime(zone).date

/** Today's [LocalDate] in [zone], read from the system clock. */
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
private fun delayUntilNextMidnight(clock: Clock, today: LocalDate, zone: TimeZone): Long {
    val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY)
        .atStartOfDayIn(zone)
    val now = clock.now()
    return (tomorrowMidnight - now).inWholeMilliseconds
}

/**
 * Today's date **in the host's current time zone**, read from the system clock.
 *
 * Prefer [todayAt] with an injected clock and an explicit zone: this function is
 * the one way to ask "what is today here" that a test cannot answer, which is the
 * defect behind #91. It survives for the callers where the host's zone genuinely
 * is the subject — a @Preview rendered on a developer's machine, a log line — and
 * those callers are the ones the name is for. Renamed from `todayInSystemZone`
 * on 2026-10-05 to say what it costs the caller; the old name read like a neutral
 * accessor, and 17 sites used it as one.
 */
fun systemToday(): LocalDate = todayAt(TimeZone.currentSystemDefault())
