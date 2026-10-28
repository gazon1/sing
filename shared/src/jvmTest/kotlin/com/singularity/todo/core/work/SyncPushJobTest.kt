package com.singularity.todo.core.work

import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.SyncOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import org.junit.jupiter.api.Tag

@Tag("fast")
class SyncPushJobTest {
    @Test
    fun partialFailure_returnsFailed() = runTest {
        val repo = FakeSyncRepository()
        repo.syncOnceResult = kotlin.Result.success(
            SyncOutcome.Completed(
                kotlin.Result.failure(IllegalStateException("push boom")),
                kotlin.Result.success(com.singularity.todo.core.sync.PullSummary(0, 0, 0)),
            ),
        )
        val job = SyncPushJob(repo)
        val outcome = job.run()
        assertTrue(outcome is JobOutcome.Failed)
    }

    @Test
    fun bothSucceed_returnsSuccess() = runTest {
        val repo = FakeSyncRepository()
        val job = SyncPushJob(repo)
        assertEquals(JobOutcome.Success, job.run())
    }

    @Test
    fun nothingToDo_returnsSkipped() = runTest {
        val repo = FakeSyncRepository()
        repo.syncOnceResult = kotlin.Result.success(com.singularity.todo.core.sync.SyncOutcome.NothingToDo)
        val job = SyncPushJob(repo)
        assertTrue(job.run() is JobOutcome.Skipped)
    }
}
