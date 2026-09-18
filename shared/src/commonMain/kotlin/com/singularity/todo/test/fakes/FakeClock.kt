package com.singularity.todo.test.fakes

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Fake [Clock] for deterministic tests.
 *
 * Default epoch is `1970-01-01T00:00:00Z`.
 * Advance time with [advance], or set an explicit [Instant] with [setNow].
 *
 * Example:
 * ```
 * val clock = FakeClock(Instant.fromEpochMilliseconds(0))
 * clock.advance(7.days)
 * assertEquals(LocalDate(1970, 1, 8), clock.now().toLocalDateTime(UTC).date)
 * ```
 */
class FakeClock(
    private var currentInstant: Instant = Instant.fromEpochMilliseconds(0),
) : Clock {

    override fun now(): Instant = currentInstant

    /** Advances time by the given [duration]. */
    fun advance(duration: Duration) {
        currentInstant += duration
    }

    /** Sets the clock to an explicit [Instant]. */
    fun setNow(instant: Instant) {
        currentInstant = instant
    }

    /** Returns the current date in the given [zone]. */
    fun today(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
        currentInstant.toLocalDateTime(zone).date
}
