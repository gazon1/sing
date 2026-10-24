@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.SyncDocumentWriter
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.data.parseLinksJson
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagGroupRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A task document is more than a row, and every path that applies one has to
 * apply all of it — and only the parts it speaks about.
 *
 * ## Why a real database
 *
 * The parts of a task live in three places — the row, the `task_tags` cross-ref
 * table, the `task_dependencies` cross-ref table — and one column of the row
 * (`outgoing_links`) is *derived* from the description rather than carried by
 * the domain model. `@Upsert` replaces the whole row, so a writer that forgets
 * one part does not leave that part alone: it resets it. A fake that stores
 * whole `Task` objects cannot show any of this.
 *
 * ## The rule
 *
 * A document that arrives from another device is applied whole: the row, the
 * links its description implies, and the parts it carries — the tags, the
 * blocking dependencies. The paths that apply *part* of a document — an edit
 * that changes the title — are a different thing, and the tags are one of
 * those parts.
 *
 * And a part the document does not carry is not an empty part. It is the
 * absence of a claim: a device that sends no `tags` key says nothing about the
 * tags, and a claim that is not made is not a deletion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
@Tag("slow:data")
class TaskDocumentWriteTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-task-document-").toFile() }
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

    /** The log the repository writes to, and the test reads. */
    private val logWriter = CapturingLogWriter()

    private fun currentUser(userId: UserId = alice): ProfileAwareCurrentUser =
        FakeProfileAwareCurrentUser(FakeAuthRepository(Session.Anonymous(userId)))

    private fun repo(userId: UserId = alice): TaskRepository {
        val currentUser = currentUser(userId)
        return TaskRepositoryImpl(
            taskDao = db.taskDao(),
            textIndex = db.textIndex(currentUser),
            clock = Clock.System,
            currentUser = currentUser,
            syncRepository = FakeSyncRepository(),
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
            unitOfWork = FakeUnitOfWork(),
            log = Logger(loggerConfigInit(logWriter), "task-document-test"),
        )
    }

    /**
     * The log the test reads.
     *
     * A refusal that nobody can see is a device that disagrees with the device
     * that sent the document — and the disagreement repeats on every pull. The
     * repository takes its logger as a parameter for exactly this reason.
     */
    private class CapturingLogWriter : LogWriter() {
        /** One captured log entry. */
        data class Entry(val severity: Severity, val message: String, val tag: String)

        /** Everything the repository logged, in order. */
        val entries: MutableList<Entry> = mutableListOf()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            entries += Entry(severity, message, tag)
        }
    }

    private fun task(
        id: String,
        uid: UserId = alice,
        title: String = "Pay the rent",
        description: String? = null,
        tags: List<TagId> = emptyList(),
        blockedBy: Set<TaskId> = emptySet(),
        dueDate: LocalDate? = LocalDate(2026, 10, 5),
        updatedAt: Instant = Clock.System.now(),
        seriesId: TaskSeriesId? = null,
    ) = Task(
        id = TaskId(id),
        title = title,
        description = description,
        userId = uid,
        tags = tags,
        blockedBy = blockedBy,
        dueDate = dueDate,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        seriesId = seriesId,
    )

    private suspend fun linksOf(taskId: String, uid: UserId = alice): String? =
        db.taskDao().getByIdForUser(taskId, uid.value)?.outgoingLinks

    private suspend fun edgesOf(taskId: String, uid: UserId = alice) =
        db.taskDao().observeTypedDependenciesForUser(taskId, uid.value).first()

    private suspend fun tagsOf(taskId: String, uid: UserId = alice): List<String> =
        db.taskDao().getTagIdsForUser(taskId, uid.value).first()

    @Test
    fun `the pull path writes the tags the document carries`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        advanceUntilIdle()

        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = listOf(TagId("tag-1")),
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("tag-1"),
            tagsOf("t1"),
            "the tag of the row that arrived is in the database",
        )
    }

    @Test
    fun `the pull path replaces the tags with the ones the document carries`() = runTest {
        val repo = repo()
        repo.create(task("t1", tags = listOf(TagId("tag-1")))).getOrThrow()
        advanceUntilIdle()

        // The document that arrives carries no tag: the tags of the task are the
        // tags of the document, so the tag the document dropped is gone.
        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = emptyList(),
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            emptyList(),
            tagsOf("t1"),
            "the tag the document dropped is gone",
        )
    }

    @Test
    fun `a document that says nothing about the tags leaves them alone`() = runTest {
        val repo = repo()
        repo.create(task("t1", tags = listOf(TagId("tag-1")))).getOrThrow()
        advanceUntilIdle()

        // The document says nothing about the tags: a device that sends no
        // `tags` key makes no claim, and a claim that is not made is not a
        // deletion.
        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = null,
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("tag-1"),
            tagsOf("t1"),
            "the tags of the task are still the tags of the task",
        )
    }

    @Test
    fun `a document that says nothing about the tags leaves the tags of the series alone`() = runTest {
        val repo = repo()
        val series = TaskSeriesId("s1")
        repo.create(task("t1", seriesId = series, tags = listOf(TagId("tag-1")))).getOrThrow()
        repo.create(task("t2", seriesId = series)).getOrThrow()
        advanceUntilIdle()
        val before = db.taskDao().getByIdForUser("t1", alice.value)?.updatedAt

        // The document that arrives is about t2, and it says nothing about the
        // tags. The repair of the series copies the source's shared fields onto
        // the members — but the source has no claim about the tags, so the tags
        // of t1 are not t2's to take away.
        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t2"))!!,
                tags = null,
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("tag-1"),
            tagsOf("t1"),
            "the tag of the other member of the series is still there",
        )
        // And the member was not touched at all: the repair compares the shared
        // fields the writer had a claim about, so a member that differs only in
        // the tags is not written. Without that, the row would be rewritten and
        // re-stamped for a claim that was never made.
        assertEquals(
            before,
            db.taskDao().getByIdForUser("t1", alice.value)?.updatedAt,
            "the member of the series was not written",
        )
    }

    @Test
    fun `a document that says nothing about the dependencies leaves them alone`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        repo.setDependencies(TaskId("t1"), setOf(TaskId("t2"))).getOrThrow()
        advanceUntilIdle()

        // The document says nothing about the dependencies: a device that sends
        // no `dependsOn` key makes no claim, and the local edges stay.
        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = null,
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.any { it.dependsOnTaskId == "t2" },
            "the dependency the document said nothing about is still there: $edges",
        )
    }

    @Test
    fun `the pull path writes the links the description implies`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        advanceUntilIdle()

        // The links are derived from the description: a document that carries a
        // description carries the links that description implies.
        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!.copy(description = "the details are in task://t9"),
                tags = null,
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        val links = linksOf("t1")
        assertEquals(
            listOf("task://t9"),
            links.orEmpty().parseLinksJson(),
            "the links of the row that arrived are in the database",
        )
    }

    @Test
    fun `the pull path applies the dependencies the document carries`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        advanceUntilIdle()

        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = null,
                blockedBy = setOf(TaskId("t2")),
            ),
        )
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.any { it.dependsOnTaskId == "t2" && it.verb == DependencyVerb.BLOCKS.name },
            "the dependency of the row that arrived is in the database: $edges",
        )
    }

    @Test
    fun `the pull path removes a dependency the document no longer carries`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        repo.setDependencies(TaskId("t1"), setOf(TaskId("t2"))).getOrThrow()
        advanceUntilIdle()

        // The document that arrives says the task depends on nothing.
        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = null,
                blockedBy = emptySet(),
            ),
        )
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.none { it.dependsOnTaskId == "t2" },
            "the dependency the document dropped is gone: $edges",
        )
    }

    @Test
    fun `the pull path leaves the edges the document never spoke about`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        // A task that follows up another one: the wire carries only the blocking
        // edges, so the document says nothing about this one.
        db.taskDao().upsertDependencyForUser(
            taskId = "t1",
            depId = "t2",
            verb = DependencyVerb.FOLLOWS_UP.name,
            userId = alice.value,
        )
        advanceUntilIdle()

        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = null,
                blockedBy = emptySet(),
            ),
        )
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.any { it.dependsOnTaskId == "t2" && it.verb == DependencyVerb.FOLLOWS_UP.name },
            "the edge the document never spoke about survives: $edges",
        )
    }

    @Test
    fun `a pair may carry two relations`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        // The pair already carries one relation: a follow-up.
        db.taskDao().upsertDependencyForUser(
            taskId = "t1",
            depId = "t2",
            verb = DependencyVerb.FOLLOWS_UP.name,
            userId = alice.value,
        )
        advanceUntilIdle()

        // The document says the task waits for the other one. The two relations
        // are different claims about the same pair, and the document speaks
        // about the blocking one only — so the follow-up stays.
        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!,
                tags = null,
                blockedBy = setOf(TaskId("t2")),
            ),
        )
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.any { it.verb == DependencyVerb.FOLLOWS_UP.name },
            "the relation the document never spoke about survives: $edges",
        )
        assertTrue(
            edges.any { it.verb == DependencyVerb.BLOCKS.name },
            "the relation the document carried is applied beside it: $edges",
        )
    }

    @Test
    fun `a write does not take the tags off the task`() = runTest {
        val repo = repo()
        val tag = TagId("tag-1")
        repo.create(task("t1", tags = listOf(tag))).getOrThrow()
        advanceUntilIdle()

        // The task is read the way an edit reads it: through `get()`, which does
        // not load the tags — they live in the cross-ref table, and the caller
        // that reads a task to edit it is not the caller that sets its tags.
        val edited = repo.get(TaskId("t1"))!!
        assertTrue(edited.tags.isEmpty(), "the read carries no tags — that is the point of this test")

        repo.update(edited.copy(dueDate = LocalDate(2026, 10, 6))).getOrThrow()
        advanceUntilIdle()

        assertEquals(
            listOf(tag.value),
            tagsOf("t1"),
            "the tags of the task are still the tags of the task",
        )
    }

    @Test
    fun `a document that would close a cycle still lands its row`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        // t2 waits for t1 — so a document that makes t1 wait for t2 closes a circle.
        repo.setDependencies(TaskId("t2"), setOf(TaskId("t1"))).getOrThrow()
        advanceUntilIdle()

        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!.copy(title = "Pay the rent in full"),
                tags = null,
                blockedBy = setOf(TaskId("t2")),
            ),
        )
        advanceUntilIdle()

        // The row landed: a document that says something the local graph cannot
        // hold does not stop the row from landing.
        assertEquals(
            "Pay the rent in full",
            db.taskDao().getByIdForUser("t1", alice.value)?.title,
            "the row of the document that arrived is in the database",
        )
        // And the edge that would have closed the circle was refused on its own.
        val edges = edgesOf("t1")
        assertTrue(
            edges.none { it.dependsOnTaskId == "t2" },
            "the edge that would close the cycle was refused: $edges",
        )
    }

    @Test
    fun `a refused edge is recorded`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        repo.setDependencies(TaskId("t2"), setOf(TaskId("t1"))).getOrThrow()
        advanceUntilIdle()

        repo.upsert(
            TaskDocument(
                task = repo.get(TaskId("t1"))!!.copy(title = "Pay the rent in full"),
                tags = null,
                blockedBy = setOf(TaskId("t2")),
            ),
        )
        advanceUntilIdle()

        // The refusal is a disagreement between two devices about the shape of
        // the work, and it repeats on every pull. A refusal nobody can see is
        // a device that silently disagrees.
        val refusals = logWriter.entries.filter { it.message.contains("wait for") }
        assertEquals(
            1,
            refusals.size,
            "the refusal of the edge is in the log: ${logWriter.entries.map { it.message }}",
        )
        assertTrue(
            refusals.single().message.contains("t2"),
            "the line names the task that was not made to wait: ${refusals.single().message}",
        )
    }

    @Test
    fun `another profile's row keeps the links it carried`() = runTest {
        // The row of the other profile, with its links, already on this device.
        val bobRepo = repo(bob)
        bobRepo.create(task("t9", uid = bob, description = "the details are in task://t1")).getOrThrow()
        advanceUntilIdle()
        val before = linksOf("t9", bob)
        assertEquals(
            listOf("task://t1"),
            before.orEmpty().parseLinksJson(),
            "the row of the other profile carries the links its description implies",
        )

        // This device pulls that row. It is not on that profile, so it stores the
        // row and leaves the parts of the document alone — and the links the row
        // carried are the ones it keeps.
        repo().upsert(
            TaskDocument(
                task = task("t9", uid = bob, description = "the details are in task://t1"),
                tags = null,
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            before,
            linksOf("t9", bob),
            "the row of the other profile kept the links it carried",
        )
    }

    @Test
    fun `a document that carries no series id still leaves the series saying one thing`() = runTest {
        val repo = repo()
        val series = TaskSeriesId("series-1")
        repo.createSeries(
            listOf(
                task("t1", seriesId = series, title = "Pay the rent"),
                task("t2", seriesId = series, title = "Pay the rent"),
            ),
        ).getOrThrow()
        advanceUntilIdle()

        // The document that arrives carries no series id: a device that
        // predates the series sends no such key. The row keeps the series it
        // has — and the series still says one thing about the work, because
        // the document changed a task of the series, and the rule of the
        // series does not depend on whether the sender knew about it.
        val writer = SyncDocumentWriter(
            taskRepo = repo,
            noteRepo = FakeNotesRepository(),
            projectRepo = FakeProjectsRepository(),
            tagRepo = FakeTagsRepository(),
            tagGroupRepo = FakeTagGroupRepository(),
            timeTrackingRepo = FakeTimeTrackingRepository(FakeClock()),
            currentUser = currentUser(),
        )
        val arriving = task("t1", seriesId = series, title = "Pay the rent in full")
        writer.upsert(
            DocType.Task,
            JsonObject(arriving.toJson().filterKeys { it != "seriesId" }),
        )
        advanceUntilIdle()

        val members = repo.observeSeries(series).first().associateBy { it.id.value }
        assertEquals(
            "Pay the rent in full",
            members.getValue("t2").title,
            "the series says one thing about the work",
        )
    }

    @Test
    fun `another profile's row does not touch this profile's rows`() = runTest {
        val repo = repo()
        repo.create(task("t1", description = "the details are in task://t9")).getOrThrow()
        advanceUntilIdle()
        val before = linksOf("t1")

        repo.upsert(
            TaskDocument(
                task = task("t9", uid = bob, description = "the details are in task://t1"),
                tags = null,
                blockedBy = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            before,
            linksOf("t1"),
            "the row of this profile is untouched by the row of another profile",
        )
    }
}
