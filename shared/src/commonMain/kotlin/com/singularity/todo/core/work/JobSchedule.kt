package com.singularity.todo.core.work

import kotlin.time.Duration
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * When a background job should run.
 *
 * ## Why wall-clock variants exist at all
 *
 * The obvious design is a single `Periodic(interval: Duration)` — and it is wrong for
 * anything the user thinks of as "every day" or "every Monday", because **neither platform
 * can express a wall-clock time as a fixed interval**:
 *
 * - `PeriodicWorkRequestBuilder(24, HOURS)` on Android means "every 24 hours from enqueue
 *   time". Enqueued at 09:00, it fires at 09:00 daily, forever. It has no idea the job was
 *   meant for 03:00, and it drifts with Doze.
 * - A delay loop on Desktop has the same problem the moment the machine suspends.
 *
 * systemd's `OnCalendar=` *is* wall-clock accurate, which is why a systemd-backed reminder
 * needs none of the workaround below.
 *
 * So the schedule travels as an intent — "daily at 03:00" — and each platform realizes it
 * the way that platform can. Android answers with a one-shot that re-enqueues its own
 * successor, computing the next boundary from an injected clock. That is why this is a
 * sealed hierarchy and not an interval.
 */
sealed interface JobSchedule {

    /** Run once, now. Equivalent to `BackgroundWorkScheduler.runNow`. */
    data object OnDemand : JobSchedule

    /** Every day at [atHour]:[atMinute] in the job's zone. */
    data class Daily(val atHour: Int, val atMinute: Int) : JobSchedule

    /** Every [dayOfWeek] at [atHour]:[atMinute] in the job's zone. */
    data class Weekly(val dayOfWeek: DayOfWeek, val atHour: Int, val atMinute: Int) : JobSchedule

    /** Every [interval], regardless of wall-clock time. Correct for polling, not for "daily". */
    data class Periodic(val interval: Duration) : JobSchedule

    /**
     * When this schedule next fires, strictly after [now].
     *
     * Null for [OnDemand] and [Periodic]: the first has no future occurrence by definition,
     * and the second is counted by the platform's own tick rather than by wall clock.
     */
    fun nextOccurrence(now: LocalDateTime): LocalDateTime? =
        JobScheduleMath.nextOccurrence(this, now)
}

/**
 * Pure boundary arithmetic for [JobSchedule].
 *
 * Split out from the interface so the interesting part — "which day is the next Monday, and
 * what happens at 23:59" — is testable without a scheduler, a coroutine scope, or a
 * platform. Every function takes the current time as a parameter; none reads a clock, and
 * none touches the system zone, because `NoDirectClockSystem` and the injected-zone
 * convention exist for reasons.
 *
 * "Strictly after" is load-bearing throughout. A 03:00 job whose next boundary were
 * computed from a 03:00:00 clock would return 03:00:00 again and spin — and the loop
 * around it would then burn a core rather than fail loudly.
 */
object JobScheduleMath {

    /** The first firing of [schedule] strictly after [now], in the job's zone. */
    fun nextOccurrence(schedule: JobSchedule, now: LocalDateTime): LocalDateTime? = when (schedule) {
        JobSchedule.OnDemand -> null

        is JobSchedule.Periodic -> null

        is JobSchedule.Daily -> nextDaily(now, schedule.atHour, schedule.atMinute)

        is JobSchedule.Weekly -> nextWeekly(
            now = now,
            dayOfWeek = schedule.dayOfWeek,
            atHour = schedule.atHour,
            atMinute = schedule.atMinute,
        )
    }

    /**
     * How long to sleep from [now] until [schedule] next fires.
     *
     * Zero when there is no boundary to wait for ([OnDemand], [Periodic]) so a caller can
     * loop on this without special-casing.
     */
    fun delayUntil(schedule: JobSchedule, now: LocalDateTime, zone: TimeZone): Duration {
        val next = schedule.nextOccurrence(now) ?: return Duration.ZERO
        val delta = next.toInstant(zone) - now.toInstant(zone)
        return if (delta > Duration.ZERO) delta else Duration.ZERO
    }

    private fun nextDaily(now: LocalDateTime, atHour: Int, atMinute: Int): LocalDateTime {
        val todayAt = now.date.atTime(atHour, atMinute)
        // LocalDateTime is Comparable, so this compares wall-clock fields — which is what
        // a wall-clock schedule means. `<=` rather than `<` is the whole point: a job that
        // just fired at its own boundary must not be handed that same instant back, or the
        // loop around it spins at full speed instead of sleeping.
        return if (todayAt <= now) {
            now.date.plus(1, DateTimeUnit.DAY).atTime(atHour, atMinute)
        } else {
            todayAt
        }
    }

    private fun nextWeekly(
        now: LocalDateTime,
        dayOfWeek: DayOfWeek,
        atHour: Int,
        atMinute: Int,
    ): LocalDateTime {
        val todayAt = now.date.atTime(atHour, atMinute)
        val daysAhead = (dayOfWeek.ordinal - now.date.dayOfWeek.ordinal + 7) % 7
        // `>` for the same reason as the daily case: equality is "already fired".
        val date = if (daysAhead == 0 && todayAt > now) {
            now.date
        } else {
            // Same weekday but today's slot has passed → next week, not next year.
            now.date.plus(if (daysAhead == 0) 7 else daysAhead, DateTimeUnit.DAY)
        }
        return date.atTime(atHour, atMinute)
    }
}
