@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.checklist

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.checklist.data.ChecklistRepositoryImpl
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Read-isolation for checklist observation.
 *
 * `ChecklistRepositoryImpl.watchByTask` read items by `task_id` alone, so a task id belonging
 * to another profile returned that profile's items. Same defect class as
 * `TagsReadIsolationTest` and the `TaskDao.getById` leak closed in
 * `2026-09-27-write-layer-soundness` (ledger #1).
 *
 * Asserted through the repository, not the DAO, because the repository is what hides the
 * unscoped read: the DAO twin is still there for the two tests that use it directly.
 */
@Tag("fast")
class ChecklistReadIsolationTest {

    private val currentUserId = UserId("u1")
    private val otherUserId = UserId("u2")

    private suspend fun setup() = FakeAppDatabase().also { db ->
        db.seedTasks(listOf(task("t-mine", currentUserId), task("t-theirs", otherUserId)))
        db.checklistDao().upsert(item("c-mine", "t-mine"))
        db.checklistDao().upsert(item("c-theirs", "t-theirs"))
    }

    private fun task(id: String, userId: UserId) = TaskEntity(
        id = id, title = "T", description = null, projectId = null,
        dueDate = null, dueTime = null,
        startDate = null, startTime = null, endDate = null, endTime = null,
        accentColor = null, emoji = null,
        completedAt = null, someday = false,
        archivedAt = null, isPinned = false, createdAt = 1_000_000_000L, updatedAt = 1_000_000_000L,
        userId = userId.value,
    )

    private fun item(id: String, taskId: String) = ChecklistItemEntity(
        id = id,
        taskId = taskId,
        title = id,
        isCompleted = false,
        sortOrder = 0,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        checkedBy = null,
        checkedAt = null,
        rowVersion = 1,
    )

    private fun repository(db: FakeAppDatabase) = ChecklistRepositoryImpl(
        dao = db.checklistDao(),
        clock = FakeClock(),
        currentUser = FakeProfileAwareCurrentUser(
            authRepository = FakeAuthRepository(Session.Anonymous(currentUserId)),
        ),
    )

    @Test
    fun `another profile's checklist items are not read`() = runTest {
        val repo = repository(setup())

        assertEquals(emptyList(), repo.watchByTask("t-theirs").first().map { it.title })
    }

    @Test
    fun `the current profile's own items are still read`() = runTest {
        val repo = repository(setup())

        assertEquals(listOf("c-mine"), repo.watchByTask("t-mine").first().map { it.title })
    }
}
