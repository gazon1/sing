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
 * The row an undone delete puts back.
 *
 * A checklist item is deleted for real — the row leaves the table — so putting it
 * back is a write of a row that is not there any more. Without the creation time
 * and the version travelling with the item, the row that comes back is a new one:
 * it claims to have been created just now, and its count of completion toggles
 * starts over.
 *
 * Asserted against the real repository, because the row is what the repository
 * writes — the domain item alone cannot show what the table ended up holding.
 */
@Tag("fast")
class ChecklistRestoreTest {
    private val currentUserId = UserId("u1")

    private suspend fun setup(): FakeAppDatabase =
        FakeAppDatabase().also { db ->
            db.seedTasks(listOf(task("t-mine")))
            db.checklistDao().upsert(item("c-mine", "t-mine"))
        }

    private fun task(id: String) =
        TaskEntity(
            id = id,
            title = "T",
            description = null,
            projectId = null,
            dueDate = null,
            dueTime = null,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            completedAt = null,
            someday = false,
            archivedAt = null,
            isPinned = false,
            createdAt = 1_000_000_000L,
            updatedAt = 1_000_000_000L,
            userId = currentUserId.value,
        )

    private fun item(
        id: String,
        taskId: String,
    ) = ChecklistItemEntity(
        id = id,
        taskId = taskId,
        title = id,
        isCompleted = false,
        sortOrder = 0,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        checkedBy = null,
        checkedAt = null,
        rowVersion = 3,
    )

    private fun repository(db: FakeAppDatabase) =
        ChecklistRepositoryImpl(
            dao = db.checklistDao(),
            clock = FakeClock(),
            currentUser =
                FakeProfileAwareCurrentUser(
                    authRepository = FakeAuthRepository(Session.Anonymous(currentUserId)),
                ),
        )

    @Test
    fun `the row put back keeps its creation time and its version`() =
        runTest {
            val db = setup()
            val repo = repository(db)
            val snapshot = repo.watchByTask("t-mine").first().single()

            repo.delete(snapshot.id)
            assertEquals(emptyList(), repo.watchByTask("t-mine").first())

            repo.upsert(snapshot)

            val restored = repo.watchByTask("t-mine").first().single()
            assertEquals(1_000_000_000L, restored.createdAt, "the creation time must not restart")
            assertEquals(3, restored.rowVersion, "the version must not restart")
        }
}
