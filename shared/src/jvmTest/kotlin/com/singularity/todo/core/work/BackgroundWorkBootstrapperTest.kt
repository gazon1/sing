package com.singularity.todo.core.work

import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeBackupRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The maintenance-job bootstrapper.
 *
 * ## What is actually being tested
 *
 * [BackgroundWorkBootstrapper] has no logic of its own — it arms three jobs. So the risk is
 * not arithmetic, it is **a job never being armed**, which no test in this file can
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
            9, // 3 jobs × 3 launches
            scheduler.scheduled.size,
            "each launch re-arms all jobs; the scheduler's KEEP policy is what stops that stacking",
        )
        assertTrue(
            scheduler.scheduled.all { it.first in listOf(
                PruneLlmUsageJob.ID,
                BackupJob.ID,
                ArchiveJob.ID,
            ) },
            "every launch must arm the same three maintenance jobs",
        )
    }

    @Test
    fun `prune and archive run at 04-20 and 04-35, backup at 04-30`() = runTest {
        val scheduler = RecordingScheduler()

        BackgroundWorkBootstrapper(scheduler).run()

        val byId = scheduler.scheduled.associate { it.first to it.second }
        assertEquals(3, byId.size)

        val pruneDaily = byId[PruneLlmUsageJob.ID] as? JobSchedule.Daily
        assertEquals(4, pruneDaily?.atHour)
        assertEquals(20, pruneDaily?.atMinute)

        val backupDaily = byId[BackupJob.ID] as? JobSchedule.Daily
        assertEquals(4, backupDaily?.atHour)
        assertEquals(30, backupDaily?.atMinute)

        val archiveDaily = byId[ArchiveJob.ID] as? JobSchedule.Daily
        assertEquals(4, archiveDaily?.atHour)
        assertEquals(35, archiveDaily?.atMinute)
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
    fun `all armed job ids resolve in a real catalogue`() = runTest {
        val scheduler = RecordingScheduler()
        BackgroundWorkBootstrapper(scheduler).run()

        val db = FakeAppDatabase()
        val settings = FakeSettingsRepository()
        val currentUser = FakeProfileAwareCurrentUser()
        val clock = FakeClock()

        val catalogue = ListBackgroundJobCatalog(
            listOf(
                PruneLlmUsageJob(recorder()),
                BackupJob(FakeBackupRepository(), settings),
                ArchiveJob(db.taskDao(), settings, currentUser, clock),
            ),
        )

        for ((jobId, _) in scheduler.scheduled) {
            assertContains(
                catalogue.all().map { it.id },
                jobId,
                "$jobId is armed but absent from the catalogue — every launch would throw",
            )
        }
    }
}
