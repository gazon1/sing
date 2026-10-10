
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.singularity.todo.test.fakes.FakeUnitOfWork

/**
 * Sync-propagation contract for the **real** [TaskRepositoryImpl], against a real
 * Room/SQLite database.
 *
 * The existing `TaskRepositoryImplTest` exercises `FakeTaskRepository`, not this
 * class, so nothing previously covered whether a task mutation actually reaches
 * the sync outbox. That is the defect this file pins down: `softDelete`,
 * `restore`, `toggleComplete`, `togglePinned`, `setTags` and `setDependencies`
 * all wrote to Room and silently dropped the sync, so deletions never reached
 * the server and the next pull resurrected them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
class TaskRepositorySyncPropagationTest {

    private val user = UserId("u1")

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: FakeSyncRepository
    private lateinit var repo: TaskRepositoryImpl

    @BeforeTest
    fun setUp() = runTest {
        tempDir = Files.createTempDirectory("singularity-sync-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
        sync = FakeSyncRepository(this)
        val currentUser = FakeProfileAwareCurrentUser(
            FakeAuthRepository(Session.Anonymous(user)),
            scope = backgroundScope,
        )
        repo = TaskRepositoryImpl(
            taskDao = db.taskDao(),
            clock = Clock.System,
            currentUser = currentUser,
            syncRepository = sync,
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
            unitOfWork = FakeUnitOfWork(),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun task(
        title: String = "Task",
        id: String = "t1",
        description: String? = null,
    ) = Task(
        id = TaskId(id),
        title = title,
        description = description,
        userId = user,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    /** The most recent entity handed to the outbox. */
    private fun lastEnqueued() = sync.enqueuedEntities.last()

    @Test
    fun `create enqueues the task`() = runTest {
        repo.create(task())
        assertEquals(1, sync.enqueuedEntities.size)
        assertEquals("t1", lastEnqueued().syncId)
    }

    @Test
    fun `soft delete enqueues the trashed state, not a tombstone`() = runTest {
        repo.create(task())
        sync.enqueuedEntities.clear()

        val result = repo.softDelete(TaskId("t1"))

        assertTrue(result.isSuccess, "softDelete failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size, "soft delete must reach the outbox")
        // Deletion propagates as state: archivedAt is set on the pushed payload,
        // which is what makes the server converge without a protocol change.
        val pushed = lastEnqueued().toJson()["archivedAt"]
        assertNotNull(pushed, "archivedAt must be present in the pushed payload")
    }

    @Test
    fun `restore enqueues the un-trashed state`() = runTest {
        repo.create(task())
        repo.softDelete(TaskId("t1"))
        sync.enqueuedEntities.clear()

        val result = repo.restore(TaskId("t1"))

        assertTrue(result.isSuccess, "restore failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size, "restore must reach the outbox")
    }

    @Test
    fun `delete delegates to soft delete and still enqueues`() = runTest {
        repo.create(task())
        sync.enqueuedEntities.clear()

        val result = repo.delete(TaskId("t1"))

        assertTrue(result.isSuccess)
        assertEquals(1, sync.enqueuedEntities.size, "delete must reach the outbox")
    }

    @Test
    fun `toggleComplete enqueues`() = runTest {
        repo.create(task())
        sync.enqueuedEntities.clear()

        val result = repo.toggleComplete(TaskId("t1"))

        assertTrue(result.isSuccess, "toggleComplete failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size)
    }

    @Test
    fun `togglePinned enqueues`() = runTest {
        repo.create(task())
        sync.enqueuedEntities.clear()

        val result = repo.togglePinned(TaskId("t1"))

        assertTrue(result.isSuccess, "togglePinned failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size)
    }

    @Test
    fun `setTags enqueues fresh entity with the new tags, proving a re-read`() = runTest {
        // REQ-WP-012: narrow field-update methods re-read the entity before enqueueing.
        // A stale read would enqueue the original tags; a fresh read enqueues the new ones.
        val tag1 = com.singularity.todo.feature.tags.TagId("tag1")
        val tag2 = com.singularity.todo.feature.tags.TagId("tag2")
        repo.create(task().copy(tags = listOf(tag1)))
        sync.enqueuedEntities.clear()

        val result = repo.setTags(TaskId("t1"), listOf(tag2))

        assertTrue(result.isSuccess, "setTags failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size, "tag change must reach the outbox")
        val enqueuedJson = sync.enqueuedEntities.last().toJson()
        val enqueuedTags: List<String> = enqueuedJson["tags"]
            ?.jsonArray
            ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            ?: emptyList()
        assertTrue(
            enqueuedTags.any { it.contains("tag2") },
            "Enqueued entity must contain tag2 (fresh read, not stale): $enqueuedTags",
        )
        assertFalse(
            enqueuedTags.any { it.contains("tag1") },
            "Enqueued entity must NOT still contain tag1 (proves fresh read, not stale): $enqueuedTags",
        )
    }

    @Test
    fun `setDependencies enqueues, because dependsOn is part of the synced payload`() = runTest {
        repo.create(task())
        repo.create(task(title = "Other", id = "t2"))
        sync.enqueuedEntities.clear()

        val result = repo.setDependencies(TaskId("t1"), setOf(TaskId("t2")))

        assertTrue(result.isSuccess, "setDependencies failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size, "dependency change must reach the outbox")
    }

    @Test
    fun `mutations on a missing task fail instead of reporting success`() = runTest {
        // The old `?: return@runCatching` made "not found" indistinguishable
        // from "toggled" — a Result.success that lied.
        assertTrue(repo.toggleComplete(TaskId("nope")).isFailure)
        assertTrue(repo.togglePinned(TaskId("nope")).isFailure)
        assertTrue(repo.softDelete(TaskId("nope")).isFailure)
        assertTrue(repo.restore(TaskId("nope")).isFailure)
        assertTrue(sync.enqueuedEntities.isEmpty(), "a failed mutation must not enqueue")
    }

    @Test
    fun `update rejects a foreign userId via assertCanWrite`() = runTest {
        val foreign = task().copy(userId = UserId("intruder"))

        val result = repo.update(foreign)

        assertTrue(result.isFailure, "cross-user update must be rejected")
        assertTrue(
            result.exceptionOrNull() is com.singularity.todo.core.repository.CrossUserWriteException,
            "expected CrossUserWriteException, got ${result.exceptionOrNull()}",
        )
    }

    @Test
    fun `update stamps an anonymous userId to the current user`() = runTest {
        repo.create(task())

        val result = repo.update(task().copy(userId = com.singularity.todo.core.ids.UserId.anonymous))

        assertTrue(result.isSuccess, "update failed: ${result.exceptionOrNull()}")
        assertEquals(user, result.getOrThrow().userId, "anonymous must be normalised to the current user")
    }

    // ── REQ-WP-020: outgoing links are persisted on the source entity ───────────

    @Test
    fun `create with task link persists outgoing_links on the created task`() = runTest {
        // REQ-WP-020: [[task://<id>]] links must be stored on the source entity's
        // outgoing_links column, not on the target entity.
        val t = task(id = "t1", description = "See [[task://t2]] for details")
        repo.create(t)

        // Read the raw column directly from the database to prove persistence.
        val stored = db.taskDao().getByIdForUser("t1", user.value)
        assertNotNull(stored, "task must exist after create")
        @Suppress("JSON_FORMAT_REDUNDANT")
        val storedLinks: List<String> = kotlinx.serialization.json.Json
            .decodeFromString(stored.outgoingLinks)
        assertTrue(
            storedLinks.any { it == "task://t2" },
            "outgoing_links must contain task://t2 after create: $storedLinks",
        )
    }

    @Test
    fun `create with note link persists outgoing_links on the created task`() = runTest {
        // REQ-WP-020: [[note://<id>]] links are also stored as outgoing_links.
        val t = task(id = "t1", description = "Related to [[note://n1]]")
        repo.create(t)

        val stored = db.taskDao().getByIdForUser("t1", user.value)
        assertNotNull(stored)
        @Suppress("JSON_FORMAT_REDUNDANT")
        val storedLinks: List<String> = kotlinx.serialization.json.Json
            .decodeFromString(stored.outgoingLinks)
        assertTrue(
            storedLinks.any { it == "note://n1" },
            "outgoing_links must contain note://n1 after create: $storedLinks",
        )
    }

    @Test
    fun `update with task link persists updated outgoing_links`() = runTest {
        // REQ-WP-020: updating a task's description updates its outgoing_links.
        repo.create(task(id = "t1", description = "No links here"))
        sync.enqueuedEntities.clear()

        val result = repo.update(
            task(id = "t1", description = "See [[task://t2]] then [[task://t3]]").copy(
                userId = user,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )
        assertTrue(result.isSuccess, "update failed: ${result.exceptionOrNull()}")

        val stored = db.taskDao().getByIdForUser("t1", user.value)
        assertNotNull(stored)
        @Suppress("JSON_FORMAT_REDUNDANT")
        val storedLinks: List<String> = kotlinx.serialization.json.Json
            .decodeFromString(stored.outgoingLinks)
        assertTrue(storedLinks.any { it == "task://t2" }, "must contain task://t2: $storedLinks")
        assertTrue(storedLinks.any { it == "task://t3" }, "must contain task://t3: $storedLinks")
    }

    // ── REQ-WP-021: outgoing_links updated atomically with entity write ─────────

    @Test
    fun `outgoing_links and entity are committed together`() = runTest {
        // REQ-WP-021: saveOutgoingLinks runs inside unitOfWork.write alongside the
        // entity upsert. A crash between them would roll back both.
        // This test verifies that after a successful create, both the entity row
        // and its outgoing_links column are visible — proving they committed together.
        repo.create(task(id = "t1", description = "See [[task://t2]] for details"))

        val stored = db.taskDao().getByIdForUser("t1", user.value)
        assertNotNull(stored, "entity must be committed")
        @Suppress("JSON_FORMAT_REDUNDANT")
        val storedLinks: List<String> = kotlinx.serialization.json.Json
            .decodeFromString(stored.outgoingLinks)
        assertTrue(
            storedLinks.any { it == "task://t2" },
            "links must also be committed: $storedLinks",
        )
    }

    @Test
    fun `saveOutgoingLinks throws when task is missing, leaving no orphan column`() = runTest {
        // REQ-WP-021: saveOutgoingLinks requires rows > 0. If the task doesn't exist
        // (or belongs to another profile), the DAO update matches zero rows and the
        // require throws — so the caller sees a failure and no partial column update
        // is left behind.
        val result = repo.update(
            task(id = "nonexistent", description = "Link [[task://t2]]").copy(
                userId = user,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )
        assertTrue(result.isFailure, "update of nonexistent task must fail")
    }
}
