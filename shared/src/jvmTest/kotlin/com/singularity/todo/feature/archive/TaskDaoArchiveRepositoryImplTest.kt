
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.archive

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.archive.data.TaskDaoArchiveRepositoryImpl
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.singularity.todo.test.fakes.FakeUnitOfWork

/**
 * Bulk archive has to reach the sync outbox, not just the database.
 *
 * `archiveCompletedTasks` is a single `UPDATE` touching N rows. Nothing about it
 * enqueues, so before the fix every archived task stayed local and the next pull
 * restored all of them — the same resurrection as the single-task delete path,
 * just triggered by the Archive screen instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
class TaskDaoArchiveRepositoryImplTest {

    private val user = UserId("u1")

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: FakeSyncRepository
    private lateinit var repo: TaskDaoArchiveRepositoryImpl

    @BeforeTest
    fun setUp() = runTest {
        tempDir = Files.createTempDirectory("singularity-archive-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
        sync = FakeSyncRepository(this)
        repo = TaskDaoArchiveRepositoryImpl(
            taskDao = db.taskDao(),
            clock = Clock.System,
            currentUser = FakeProfileAwareCurrentUser(
                FakeAuthRepository(Session.Anonymous(user)),
                scope = backgroundScope,
            ),
            syncRepository = sync,
            unitOfWork = FakeUnitOfWork(),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun task(id: String, uid: String, completed: Boolean) = TaskEntity(
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
        completedAt = if (completed) Clock.System.now().toEpochMilliseconds() else null,
        someday = false,
        archivedAt = null,
        isPinned = false,
        createdAt = Clock.System.now().toEpochMilliseconds(),
        updatedAt = Clock.System.now().toEpochMilliseconds(),
        userId = uid,
    )

    @Test
    fun `bulk archive enqueues every newly archived task`() = runTest {
        db.taskDao().upsert(task("a", user.value, completed = true))
        db.taskDao().upsert(task("b", user.value, completed = true))
        db.taskDao().upsert(task("open", user.value, completed = false))

        val result = repo.archiveCompletedTasks()

        assertTrue(result.isSuccess, "archive failed: ${result.exceptionOrNull()}")
        assertEquals(2, result.getOrThrow(), "only completed tasks should be archived")
        val enqueuedIds = sync.enqueuedEntities.map { it.syncId }.toSet()
        assertEquals(setOf("a", "b"), enqueuedIds, "each newly archived task must be pushed")
    }

    @Test
    fun `bulk archive does not enqueue another user's tasks`() = runTest {
        db.taskDao().upsert(task("mine", user.value, completed = true))
        db.taskDao().upsert(task("theirs", "u2", completed = true))

        val result = repo.archiveCompletedTasks()

        assertEquals(1, result.getOrThrow())
        assertEquals(
            listOf("mine"),
            sync.enqueuedEntities.map { it.syncId },
            "another user's task must neither be archived nor pushed",
        )
        assertEquals(null, db.taskDao().getById("theirs")?.archivedAt)
    }

    @Test
    fun `already archived tasks are not re-pushed`() = runTest {
        db.taskDao().upsert(task("old", user.value, completed = true))
        db.taskDao().archiveCompletedForUser(Clock.System.now().toEpochMilliseconds(), user.value)
        sync.enqueuedEntities.clear()

        // A second sweep with nothing new to archive should push nothing.
        val result = repo.archiveCompletedTasks()

        assertEquals(0, result.getOrThrow())
        assertTrue(sync.enqueuedEntities.isEmpty(), "no new rows means nothing to push")
    }
}
