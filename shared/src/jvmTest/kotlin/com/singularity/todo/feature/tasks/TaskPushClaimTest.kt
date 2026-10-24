@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the write path pushes is what the database holds.
 *
 * ## The rule
 *
 * A task that travels to the server is a claim: "this is what the work is".
 * The parts of the work that live outside the row — the tags, the blocking
 * edges — travel with it, and the receiver applies them. So a push that says
 * "the work has no tags" is a claim that the work has no tags, and the server
 * applies it: the tags are gone from the server, and from every other device
 * on the next pull.
 *
 * The write path therefore must not send a claim it does not mean. The task a
 * caller hands to `update()` was read through `get()`, which does not load the
 * tags — they live in a cross-ref table, and the read is not interested in
 * them. The empty list the caller holds is the *emptiness of the read*, not
 * the truth about the work.
 *
 * The local write already knows this: `update()` does not write the tags, and
 * the comment there says why. The push was the half that did not know: it
 * took the same task and sent it, and the wire does not know the difference
 * between "no tags" and "I did not look".
 *
 * ## Why this test
 *
 * Nothing above the repository can see it: the outbox takes a `SyncableEntity`,
 * and the entity is the task as the caller held it. The claim leaves the
 * device inside a perfectly ordinary `enqueue` call, and the loss happens on
 * another device, later, in a pull.
 *
 * ## The real transaction
 *
 * Every test here runs against a real database and a real write transaction
 * (`RoomUnitOfWork`), because half of the rule is about *when* the claim is
 * read: the read happens inside the transaction that wrote it, so it sees the
 * write rather than the state before it. A fake unit of work cannot show
 * that half at all — the write would already be applied, whatever the read
 * did.
 */
@Tag("slow")
@Tag("slow:data")
@OptIn(ExperimentalCoroutinesApi::class)
class TaskPushClaimTest {

    private val alice = UserId("alice")

    private val tempDirLazy = lazy { Files.createTempDirectory("push-claim").toFile() }
    private val tempDir: File by tempDirLazy
    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, "tasks.db").absolutePath,
        )
    }
    private val db: AppDatabase by dbLazy

    @AfterTest
    fun tearDown() {
        if (dbLazy.isInitialized()) db.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    /** The outbox the test reads. */
    private val sync = FakeSyncRepository()

    private fun repo(userId: UserId = alice): TaskRepository {
        val auth = FakeAuthRepository(Session.Anonymous(userId))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        return TaskRepositoryImpl(
            taskDao = db.taskDao(),
            textIndex = db.textIndex(currentUser),
            clock = Clock.System,
            currentUser = currentUser,
            syncRepository = sync,
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
            // The real unit of work: the claim is read back inside the
            // transaction that wrote it, and the read has to see what the write
            // did.
            unitOfWork = RoomUnitOfWork(db),
        )
    }

    private fun task(
        id: String,
        uid: UserId = alice,
        title: String = "Pay the rent",
        tags: List<TagId> = emptyList(),
        blockedBy: Set<TaskId> = emptySet(),
        seriesId: TaskSeriesId? = null,
        updatedAt: Instant = Clock.System.now(),
    ) = Task(
        id = TaskId(id),
        title = title,
        userId = uid,
        tags = tags,
        blockedBy = blockedBy,
        seriesId = seriesId,
        dueDate = LocalDate(2026, 10, 5),
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    /** The task the last push carried. */
    private fun pushedTask(): Task = sync.enqueuedEntities.last() as Task

    /** Every task the pushes carried, in the order they were pushed. */
    private fun pushedTasks(): List<Task> = sync.enqueuedEntities.filterIsInstance<Task>()

    @Test
    fun `an edit does not tell the server that the work has no tags`() = runTest {
        val repo = repo()
        repo.create(task("t1", tags = listOf(TagId("tag-1")))).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        advanceUntilIdle()
        sync.enqueuedEntities.clear()

        // The edit reads the task the way the editor reads it: through `get()`,
        // which does not load the tags.
        val read = repo.get(TaskId("t1"))!!
        repo.update(read.copy(title = "Pay the rent in full")).getOrThrow()
        advanceUntilIdle()

        assertEquals(
            listOf(TagId("tag-1")),
            pushedTask().tags,
            "the pushed task carries the tags the database holds",
        )
    }

    @Test
    fun `a batch write does not tell the server that the work waits for nothing`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        repo.setDependencies(TaskId("t1"), setOf(TaskId("t2"))).getOrThrow()
        advanceUntilIdle()
        sync.enqueuedEntities.clear()

        // A batch built from a task read through `get()` — the shape a caller
        // has when it edits one row of a series. The batch writes the tags and
        // not the edges, so the task in hand carries no edges at all.
        val read = repo.get(TaskId("t1"))!!
        repo.updateAll(listOf(read.copy(title = "Pay the rent in full"))).getOrThrow()
        advanceUntilIdle()

        assertEquals(
            setOf(TaskId("t2")),
            pushedTask().blockedBy,
            "the pushed task carries the dependencies the database holds",
        )
    }

    @Test
    fun `an edit does not tell the server that the work waits for nothing`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        repo.setDependencies(TaskId("t1"), setOf(TaskId("t2"))).getOrThrow()
        advanceUntilIdle()
        sync.enqueuedEntities.clear()

        val read = repo.get(TaskId("t1"))!!
        repo.update(read.copy(title = "Pay the rent in full")).getOrThrow()
        advanceUntilIdle()

        assertEquals(
            setOf(TaskId("t2")),
            pushedTask().blockedBy,
            "the pushed task carries the dependencies the database holds",
        )
    }

    @Test
    fun `the claim is read inside the transaction, and it sees the write`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        advanceUntilIdle()
        sync.enqueuedEntities.clear()

        // The write and the read are one transaction: the tag is written and
        // the claim is read back before the transaction commits. A read taken
        // *before* the write would carry the tags the row had before, which
        // here is none — and the test would fail.
        repo.setTags(TaskId("t1"), listOf(TagId("tag-1"))).getOrThrow()
        advanceUntilIdle()

        assertEquals(
            listOf(TagId("tag-1")),
            pushedTask().tags,
            "the task read inside the transaction carries the tags the write left",
        )
    }

    @Test
    fun `a tag edit carries the new tag, not the one the row had before`() = runTest {
        val repo = repo()
        repo.create(task("t1", tags = listOf(TagId("tag-1")))).getOrThrow()
        advanceUntilIdle()
        sync.enqueuedEntities.clear()

        // The claim has to say what the work is *now*. A push that carried the
        // previous tag set would leave the server with a tag the user removed.
        repo.setTags(TaskId("t1"), listOf(TagId("tag-2"))).getOrThrow()
        advanceUntilIdle()

        assertEquals(
            listOf(TagId("tag-2")),
            pushedTask().tags,
            "the pushed task carries the tags the write left",
        )
    }

    @Test
    fun `removing a tag the task does not carry changes nothing and claims nothing`() = runTest {
        val repo = repo()
        repo.create(task("t1", tags = listOf(TagId("tag-1")))).getOrThrow()
        advanceUntilIdle()
        sync.enqueuedEntities.clear()

        // A write that changes nothing is not a claim. Removing a tag the task
        // does not carry leaves the tags the same, so there is nothing to say
        // and nothing to push — the screen may ask, but the wire is not told.
        repo.setTags(TaskId("t1"), listOf(TagId("tag-1"))).getOrThrow()
        advanceUntilIdle()

        assertTrue(
            sync.enqueuedEntities.isEmpty(),
            "a write that changed nothing pushed nothing",
        )
        assertEquals(
            listOf(TagId("tag-1")),
            repo.getTagIds(TaskId("t1")).first(),
            "the tags the row carries are the tags it had",
        )
    }

    @Test
    fun `a tag edit of one member reaches the whole series`() = runTest {
        val repo = repo()
        val series = TaskSeriesId("series-1")
        repo.create(task("s1", seriesId = series)).getOrThrow()
        repo.create(task("s2", seriesId = series)).getOrThrow()
        advanceUntilIdle()
        sync.enqueuedEntities.clear()

        // The tags are a shared field of a series: the other members follow the
        // one whose tags were changed, and every one of them says the same
        // thing about the work on the wire.
        repo.setTags(TaskId("s1"), listOf(TagId("tag-1"))).getOrThrow()
        advanceUntilIdle()

        val pushed = pushedTasks()
        assertEquals(
            setOf("s1", "s2"),
            pushed.map { it.id.value }.toSet(),
            "every member of the series is pushed with the tags it now carries",
        )
        assertTrue(
            pushed.all { it.tags == listOf(TagId("tag-1")) },
            "no member of the series says the work has no tags",
        )
    }
}
