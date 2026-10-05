package com.singularity.todo.test.helpers

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * A clock a test moves by hand.
 *
 * ## Why a movable one and not a fixed one
 *
 * A fixed clock is enough to make a timestamp assertion deterministic. It is not enough
 * for the sync backoff, which is the first thing in `core/sync` that needed a clock at
 * all: a test that wants to see a row deferred has to move time *forward* past the
 * deferral, or it has to wait in real time for it, and the second is a slow test that
 * says nothing extra. The plan's rows PU-05 and PU-08 are unwriteable without this.
 *
 * ## Why the clock does not follow the test scheduler
 *
 * `kotlinx.coroutines` virtual time and this are two different things. A test that
 * advances the scheduler does not advance this clock, and a test that advances this
 * clock does not make a `delay` return. That is deliberate: backoff is computed from
 * wall-clock timestamps and stored in the database, so it is wall-clock time a test has
 * to control. A test that expected virtual time here would be testing the scheduler.
 *
 * Not thread-safe, on purpose: a test drives it from one coroutine, and a clock shared
 * across threads is a race the test would have to reproduce deliberately.
 */
class MutableClock(private var current: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)) : Clock {

    override fun now(): Instant = current

    /** Moves the clock to [instant], forwards or backwards. */
    fun setTo(instant: Instant) {
        current = instant
    }

    /** Moves the clock by [duration], which may be negative. */
    fun advanceBy(duration: Duration) {
        current += duration
    }

    /** Moves the clock forward by [millis]. The common case is "past the deferral". */
    fun advanceBy(millis: Long) {
        current += millis.milliseconds
    }

    /** This clock's reading as epoch millis, which is what the sync layer stores. */
    val millis: Long get() = current.toEpochMilliseconds()
}
