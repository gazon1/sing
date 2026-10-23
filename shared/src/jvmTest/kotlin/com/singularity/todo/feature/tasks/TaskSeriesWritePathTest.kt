@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The write path of a series, against a real database.
 *
 * ## Why this is not in [TaskRepositoryContractTest]
 *
 * The contract test runs every scenario against the fake as well, and the fake
 * has no write path to realign: it stores whole [Task] objects and hands them
 * back. The rule that matters here — *the write heals the series* — lives in
 * [TaskRepositoryImpl], where the rows, the cross-refs and the sync outbox are
 * three different places. A test that only ever saw the fake would pass with
 * the realignment deleted.
 *
 * So these are the checks that can fail on the code that actually runs.
 *
 * @see com.singularity.todo.feature.tasks.domain.logic.TaskSeriesInvariants
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
@Tag("slow:data")
class TaskSeriesWritePathTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val seriesId = TaskSeriesId("series-1")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-series-write-").toFile() }
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

    private fun repo(userId: UserId): TaskRepository {
        val auth = FakeAuthRepository(Session.Anonymous(userId))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        return TaskRepositoryImpl(
            taskDao = db.taskDao(),
            textIndex = db.textIndex(currentUser),
            clock = Clock.System,
            currentUser = currentUser,
            syncRepository = FakeSyncRepository(),
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
            unitOfWork = FakeUnitOfWork(),
        )
    }

    private fun task(
        id: String,
        uid: UserId = alice,
        title: String = "Pay the rent",
        tags: List<TagId> = emptyList(),
        dueDate: LocalDate? = LocalDate(2026, 10, 5),
        updatedAt: Instant = Clock.System.now(),
        seriesId: TaskSeriesId = this.seriesId,
    ) = Task(
        id = TaskId(id),
        title = title,
        userId = uid,
        seriesId = seriesId,
        tags = tags,
        dueDate = dueDate,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private suspend fun TestScope.seedSeries(repo: TaskRepository, vararg members: Task) {
        repo.createSeries(members.toList())
        advanceUntilIdle()
    }

    @Test
    fun `a renamed occurrence realigns the rest of the series`() = runTest {
        val repo = repo(alice)
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t3", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )

        val renamed = repo.get(TaskId("t1"))!!.copy(
            title = "Pay the rent in full",
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        )
        repo.update(renamed)
        advanceUntilIdle()

        val stored = repo.observeSeries(seriesId).first().associateBy { it.id.value }
        assertEquals("Pay the rent in full", stored.getValue("t2").title)
        assertEquals("Pay the rent in full", stored.getValue("t3").title)
    }

    @Test
    fun `the rows that already agree are not written again`() = runTest {
        val repo = repo(alice)
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )
        val before = repo.get(TaskId("t2"))!!.updatedAt

        // Only the date of the occurrence moves. Nothing that describes the work
        // changed, so the other row has no reason to be written — and a row that
        // is written for nothing is a row whose timestamp lies about when it
        // last changed.
        val moved = repo.get(TaskId("t1"))!!.copy(
            dueDate = LocalDate(2026, 10, 6),
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        )
        repo.update(moved)
        advanceUntilIdle()

        val after = repo.get(TaskId("t2"))!!.updatedAt
        assertEquals(before, after, "the row that already agrees must not be written again")
    }

    @Test
    fun `a member that was edited later keeps its own timestamp`() = runTest {
        val repo = repo(alice)
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(5_000)),
        )

        val renamed = repo.get(TaskId("t1"))!!.copy(
            title = "Pay the rent in full",
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        )
        repo.update(renamed)
        advanceUntilIdle()

        val stored = repo.get(TaskId("t2"))!!
        assertEquals("Pay the rent in full", stored.title, "the series says one thing about the work")
        assertTrue(
            stored.updatedAt >= Instant.fromEpochMilliseconds(5_000),
            "a member edited later than the source keeps its newer stamp — " +
                "the push would otherwise carry a stale time",
        )
    }

    @Test
    fun `an incoming row realigns the local series to it`() = runTest {
        val repo = repo(alice)
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )

        // The row that arrives from another device is the newest word about the work.
        repo.upsert(
            TaskDocument.of(
                task("t1", title = "Pay the rent in full", updatedAt = Instant.fromEpochMilliseconds(3_000)),
            ),
        )
        advanceUntilIdle()

        val stored = repo.observeSeries(seriesId).first().associateBy { it.id.value }
        assertEquals("Pay the rent in full", stored.getValue("t2").title)
    }

    @Test
    fun `a batch write repairs the members it did not write`() = runTest {
        val repo = repo(alice)
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )

        // A batch write is a series write: the members that were not in the
        // batch follow the ones that were, the way they do after an ordinary
        // write. The asymmetry read as harmless while every caller wrote the
        // whole series — and a partial batch would have left the series saying
        // two things about the work.
        //
        // The batch is read through the series, the way the callers that write a
        // series read it: a batch built from a task read through `get()` carries
        // no tags at all, and writing that emptiness would take the tags off the
        // rows it writes.
        val members = repo.getSeries(seriesId)
        repo.updateAll(
            listOf(members.first { it.id == TaskId("t1") }.copy(title = "Pay the rent in full")),
        )
        advanceUntilIdle()

        val stored = repo.observeSeries(seriesId).first().associateBy { it.id.value }
        assertEquals("Pay the rent in full", stored.getValue("t2").title, "the series says one thing")
    }

    @Test
    fun `an ordinary write does not take the tags off the other members`() = runTest {
        val repo = repo(alice)
        val tag = TagId("tag-1")
        seedSeries(
            repo,
            task("t1", tags = listOf(tag), updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", tags = listOf(tag), updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )

        // The edit path reads the task through `get()`, and `get()` carries no
        // tags: they live in the cross-ref table. A repair that took the tags of
        // the row it was given would read that absence as the truth and strip the
        // tags off every other member of the series.
        val edited = repo.get(TaskId("t1"))!!
        assertTrue(edited.tags.isEmpty(), "the read carries no tags — that is the point of this test")
        repo.update(
            edited.copy(
                dueDate = LocalDate(2026, 10, 6),
                updatedAt = Instant.fromEpochMilliseconds(2_000),
            ),
        )
        advanceUntilIdle()

        val stored = repo.observeSeries(seriesId).first()
        assertTrue(stored.all { it.tags == listOf(tag) }, "the tags stayed on the series: $stored")
    }

    @Test
    fun `the occurrence's own fields are not touched by the repair`() = runTest {
        val repo = repo(alice)
        val dueOnFriday = LocalDate(2026, 10, 9)
        seedSeries(
            repo,
            task("t1", dueDate = LocalDate(2026, 10, 5), updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", dueDate = dueOnFriday, updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )
        val completedBefore = repo.get(TaskId("t2"))!!.completedAt

        val renamed = repo.get(TaskId("t1"))!!.copy(
            title = "Pay the rent in full",
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        )
        repo.update(renamed)
        advanceUntilIdle()

        val repaired = repo.get(TaskId("t2"))!!
        assertEquals("Pay the rent in full", repaired.title, "the work says one thing")
        assertEquals(dueOnFriday, repaired.dueDate, "the date of the occurrence is its own")
        assertEquals(completedBefore, repaired.completedAt, "the completion of the occurrence is its own")
    }

    @Test
    fun `the pull path writes the tags of the row that arrived`() = runTest {
        val repo = repo(alice)
        val tag = TagId("tag-1")
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )

        // The row that arrives carries the tags of the work, and the local row
        // has to carry them too: a pull that wrote the row and lost the tags
        // would leave the series saying two things about the work.
        repo.upsert(
            TaskDocument.of(
                task(
                    "t1",
                    title = "Pay the rent in full",
                    tags = listOf(tag),
                    updatedAt = Instant.fromEpochMilliseconds(3_000),
                ),
            ),
        )
        advanceUntilIdle()

        val stored = repo.observeSeries(seriesId).first().associateBy { it.id.value }
        assertEquals(listOf(tag), stored.getValue("t1").tags, "the tags of the row that arrived are stored")
        assertEquals(listOf(tag), stored.getValue("t2").tags, "the series says one thing about the tags")
    }

    @Test
    fun `changing the tags of one member changes them for the whole series`() = runTest {
        val repo = repo(alice)
        val tag = TagId("tag-1")
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )

        repo.setTags(TaskId("t1"), listOf(tag)).getOrThrow()
        advanceUntilIdle()

        val stored = repo.observeSeries(seriesId).first()
        assertTrue(stored.all { it.tags == listOf(tag) }, "the tags reached every member: $stored")
    }

    @Test
    fun `another user's row does not touch this profile's series`() = runTest {
        val repo = repo(alice)
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )
        val before = repo.get(TaskId("t2"))!!

        // A row of another account: this device stores it and does not rewrite the
        // rows of a profile it is not on.
        repo.upsert(
            TaskDocument.of(
                task("t9", uid = bob, title = "Pay the rent in full", updatedAt = Instant.fromEpochMilliseconds(3_000)),
            ),
        )
        advanceUntilIdle()

        val after = repo.get(TaskId("t2"))!!
        assertEquals(before.title, after.title)
        assertEquals(before.updatedAt, after.updatedAt)
    }

    @Test
    fun `a batch write carries the tags of the series into the database`() = runTest {
        val repo = repo(alice)
        val tag = TagId("tag-1")
        seedSeries(
            repo,
            task("t1", updatedAt = Instant.fromEpochMilliseconds(1_000)),
            task("t2", updatedAt = Instant.fromEpochMilliseconds(1_000)),
        )

        // The tags live in the cross-ref table, not in the row: a write that carried
        // them only in the task would leave the database saying something else
        // about the work than the task does.
        repo.updateAll(
            listOf(
                repo.get(TaskId("t1"))!!.copy(tags = listOf(tag)),
                repo.get(TaskId("t2"))!!.copy(tags = listOf(tag)),
            ),
        )
        advanceUntilIdle()

        val stored = repo.observeSeries(seriesId).first()
        assertTrue(stored.all { it.tags == listOf(tag) }, "the tags reached every member: $stored")
    }
}
