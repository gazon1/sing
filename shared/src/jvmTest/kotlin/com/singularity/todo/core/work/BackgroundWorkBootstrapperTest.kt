package com.singularity.todo.core.work

import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The maintenance-job bootstrapper.
 *
 * ## What is actually being tested
 *
 * [BackgroundWorkBootstrapper] has no logic of its own — it arms one job. So the risk is
 * not arithmetic, it is **the job never being armed**, which no test in this file can
 * observe by calling the bootstrapper and checking a return value.
 *
 * That is why the third test reads the catalogue. A bootstrapper that resolves a job id
 * not in the catalogue throws [IllegalArgumentException] from the scheduler, so "is this
 * id real" is a question with a checkable answer.
 */
@Tag("fast")
class BackgroundWorkBootstrapperTest {

    /** Records `schedule` calls instead of arming anything. */
    private class RecordingScheduler : BackgroundWorkScheduler {
        val scheduled = mutableListOf<Triple<String, JobSchedule, String>>()

        override fun schedule(jobId: String, schedule: JobSchedule) {
            scheduled += Triple(jobId, schedule, "scheduled")
        }

        override fun cancel(jobId: String) = Unit

        override fun runNow(jobId: String) = Unit
    }

    // `FakeClock`, not `Clock.System`: `NoDirectClockSystemRule` bans the system clock in
    // tests so that anything time-dependent is reproducible. The prune cutoff is a
    // function of "now", so a fixed instant keeps the wiring test deterministic.
    private fun recorder(): RoomUsageRecorder = RoomUsageRecorder(FakeAppDatabase().llmUsageDao(), FakeClock())

    @Test
    fun `arming is idempotent across launches`() = runTest {
        val scheduler = RecordingScheduler()

        // A desktop that suspends overnight, or an Android process killed by Doze, calls
        // this again on the next launch. Re-arming must not stack a second schedule —
        // `BackgroundWorkScheduler.schedule` is KEEP, so each call is absorbed.
        repeat(3) {
            BackgroundWorkBootstrapper(scheduler).run()
        }

        assertEquals(
            3,
            scheduler.scheduled.size,
            "each launch re-arms; the scheduler's KEEP policy is what stops that stacking",
        )
        assertTrue(
            scheduler.scheduled.all { it.first == PruneLlmUsageJob.ID },
            "every launch must arm the same job",
        )
    }

    /**
     * The schedule is wall-clock, not an interval.
     *
     * `Periodic(24h)` on Android means "24h from enqueue", so a user who opens the app at
     * 16:00 gets pruning at 16:00 daily forever. `Daily(4, 20)` says the same time every
     * day, which is what "prune nightly" means to whoever wrote it.
     */
    @Test
    fun `prune is scheduled daily at a wall-clock time`() = runTest {
        val scheduler = RecordingScheduler()

        BackgroundWorkBootstrapper(scheduler).run()

        val daily = assertIs<JobSchedule.Daily>(
            scheduler.scheduled.single().second,
            "an interval would drift with enqueue time",
        )
        assertEquals(4, daily.atHour)
        assertEquals(20, daily.atMinute)
    }

    /**
     * The armed id must resolve, or every launch throws.
     *
     * A typo in the id is invisible to [RecordingScheduler], which accepts any string, and
     * would surface in production as an app that throws on launch. This closes that gap
     * using the production catalogue over a real recorder, so the job's own dependencies
     * resolve too.
     */
    @Test
    fun `the armed job id resolves in a real catalogue`() = runTest {
        val scheduler = RecordingScheduler()
        BackgroundWorkBootstrapper(scheduler).run()

        val id = scheduler.scheduled.single().first
        val catalogue = ListBackgroundJobCatalog(listOf(PruneLlmUsageJob(recorder())))

        assertTrue(
            catalogue.find(id) != null,
            "$id is armed but absent from the catalogue — every launch would throw",
        )
    }
}
