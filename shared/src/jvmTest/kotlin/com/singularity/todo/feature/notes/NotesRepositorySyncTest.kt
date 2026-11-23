
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.CrossUserWriteException
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Sync-propagation contract for the real [NotesRepositoryImpl] against real
 * SQLite.
 *
 * Notes carried the worst instance of the bypass class: 14 write methods reached
 * Room without ever calling `enqueue`. `updateContent` is the severe one — it is
 * the note editor's autosave path, invoked on every debounced keystroke — so a
 * note edited on one device simply never reached the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
@Tag("slow:data")
class NotesRepositorySyncTest {
    private val user = UserId("u1")

    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase
    private lateinit var sync: FakeSyncRepository
    private lateinit var repo: NotesRepositoryImpl

    @BeforeTest
    fun setUp() =
        runTest {
            fixture = DatabaseTestFixture.inMemory()
            db = fixture.database()
            sync = FakeSyncRepository(this)
            val currentUser =
                FakeProfileAwareCurrentUser(
                    FakeAuthRepository(Session.Anonymous(user)),
                    scope = backgroundScope,
                )
            repo =
                NotesRepositoryImpl(
                    noteDao = db.noteDao(),
                    textIndex = db.textIndex(currentUser),
                    clock = Clock.System,
                    currentUser = currentUser,
                    syncRepository = sync,
                    unitOfWork = FakeUnitOfWork(),
                )
        }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    private fun note(
        id: String = "n1",
        title: String = "Note",
    ) = Note(
        id = NoteId(id),
        userId = user,
        title = title,
        kind = NoteKind.Plain,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun lastEnqueued() = sync.enqueuedEntities.last()

    @Test
    fun `createWithContent enqueues`() =
        runTest {
            val result = repo.createWithContent(NoteId("n1"), "T", "# body", "<p>body</p>")
            assertTrue(result.isSuccess, "createWithContent failed: ${result.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size)
        }

    @Test
    fun `createNoteWithTitle enqueues`() =
        runTest {
            val result = repo.createNoteWithTitle("Quick note")
            assertTrue(result.isSuccess, "createNoteWithTitle failed: ${result.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size)
        }

    @Test
    fun `updateContent enqueues — this is the editor autosave path`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "# v1", "<p>v1</p>")
            sync.enqueuedEntities.clear()

            val result = repo.updateContent(NoteId("n1"), "T", "# v2", "<p>v2</p>")

            assertTrue(result.isSuccess, "updateContent failed: ${result.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size, "autosave must reach the outbox")
            // The pushed payload must carry the *new* body, not the stale pre-edit one.
            val body = lastEnqueued().toJson()["bodyMarkdown"].toString()
            assertTrue(body.contains("v2"), "expected edited body in payload, got $body")
        }

    @Test
    fun `soft delete enqueues the trashed state`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
            sync.enqueuedEntities.clear()

            val result = repo.delete(NoteId("n1"))

            assertTrue(result.isSuccess, "delete failed: ${result.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size, "delete must reach the outbox")
            assertNotNull(lastEnqueued().toJson()["deletedAt"], "deletedAt must be in the payload")
        }

    @Test
    fun `restore enqueues`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
            repo.delete(NoteId("n1"))
            sync.enqueuedEntities.clear()

            val result = repo.restore(NoteId("n1"))

            assertTrue(result.isSuccess, "restore failed: ${result.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size)
        }

    @Test
    fun `archive and unarchive enqueue`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
            sync.enqueuedEntities.clear()

            assertTrue(repo.archive(NoteId("n1")).isSuccess)
            assertEquals(1, sync.enqueuedEntities.size, "archive must reach the outbox")

            sync.enqueuedEntities.clear()
            assertTrue(repo.unarchive(NoteId("n1")).isSuccess)
            assertEquals(1, sync.enqueuedEntities.size, "unarchive must reach the outbox")
        }

    @Test
    fun `setPinned setColor setSortOrder enqueue`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
            sync.enqueuedEntities.clear()

            assertTrue(repo.setPinned(NoteId("n1"), true).isSuccess)
            assertEquals(1, sync.enqueuedEntities.size, "setPinned must reach the outbox")

            sync.enqueuedEntities.clear()
            assertTrue(repo.setColor(NoteId("n1"), NoteColor(3)).isSuccess)
            assertEquals(1, sync.enqueuedEntities.size, "setColor must reach the outbox")

            sync.enqueuedEntities.clear()
            assertTrue(repo.setSortOrder(NoteId("n1"), 7).isSuccess)
            assertEquals(1, sync.enqueuedEntities.size, "setSortOrder must reach the outbox")
        }

    @Test
    fun `setOutgoingLinks enqueues, because links are part of the synced payload`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
            sync.enqueuedEntities.clear()

            val result = repo.setOutgoingLinks(NoteId("n1"), listOf("note://n2"))
            assertTrue(result.isSuccess, "setOutgoingLinks failed: ${result.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size, "link change must reach the outbox")
        }

    @Test
    fun `saveAsTemplate enqueues`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
            sync.enqueuedEntities.clear()

            assertTrue(repo.saveAsTemplate(NoteId("n1")).isSuccess)
            assertEquals(1, sync.enqueuedEntities.size, "saveAsTemplate must reach the outbox")
        }

    @Test
    fun `createFromTemplate enqueues`() =
        runTest {
            repo.createWithContent(NoteId("tpl"), "Template", "b", "<p>b</p>")
            sync.enqueuedEntities.clear()

            val result = repo.createFromTemplate(NoteId("tpl"), "From tpl", null)

            assertTrue(result.isSuccess, "createFromTemplate failed: ${result.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size)
        }

    @Test
    fun `getOrCreateDailyNote enqueues only when it actually creates`() =
        runTest {
            val first = repo.getOrCreateDailyNote("2026-09-27", fromTemplateId = null)
            assertTrue(first.isSuccess, "create failed: ${first.exceptionOrNull()}")
            assertEquals(1, sync.enqueuedEntities.size, "the creating call must enqueue")

            sync.enqueuedEntities.clear()
            val second = repo.getOrCreateDailyNote("2026-09-27", fromTemplateId = null)
            assertTrue(second.isSuccess)
            assertEquals(first.getOrThrow(), second.getOrThrow(), "must return the same note")
            assertTrue(
                sync.enqueuedEntities.isEmpty(),
                "the no-op path changed nothing and must not enqueue",
            )
        }

    @Test
    fun `mutations on a missing note fail and enqueue nothing`() =
        runTest {
            assertTrue(repo.updateContent(NoteId("nope"), "T", "b", "<p>b</p>").isFailure)
            assertTrue(repo.archive(NoteId("nope")).isFailure)
            assertTrue(repo.setPinned(NoteId("nope"), true).isFailure)
            assertTrue(repo.delete(NoteId("nope")).isFailure)
            assertTrue(sync.enqueuedEntities.isEmpty(), "a failed mutation must not enqueue")
        }

    @Test
    fun `update rejects a foreign userId and normalises an anonymous one`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")

            val foreign = repo.update(note().copy(userId = UserId("intruder")))
            assertTrue(foreign.isFailure, "cross-user update must be rejected")
            assertTrue(foreign.exceptionOrNull() is CrossUserWriteException)

            val anonymous = repo.update(note().copy(userId = UserId.anonymous))
            assertTrue(anonymous.isSuccess, "anonymous must be normalised, not orphaned")
            assertEquals(user, anonymous.getOrThrow().userId)
        }

    // ── The conditional write and the brief reads, against real SQLite ──────────

    @Test
    fun `the conditional write matches the version in the statement`() =
        runTest {
            repo.createWithContent(NoteId("n1"), "T", "v1", "<p>v1</p>")
            val readAt = assertNotNull(repo.get(NoteId("n1")), "the note was created").updatedAt.toEpochMilliseconds()
            sync.enqueuedEntities.clear()

            val stale = repo.updateContentIfVersion(NoteId("n1"), "T", "v2", "<p>v2</p>", readAt - 1)

            assertEquals(0, stale.getOrThrow(), "a version that does not match writes no row")
            assertEquals("<p>v1</p>", repo.get(NoteId("n1"))?.bodyHtml, "the body was not replaced")
            assertTrue(sync.enqueuedEntities.isEmpty(), "a write that did not happen is not enqueued")

            val fresh = repo.updateContentIfVersion(NoteId("n1"), "T", null, "<p>v2</p>", readAt)

            assertEquals(1, fresh.getOrThrow(), "the matching version writes the row")
            assertEquals("<p>v2</p>", repo.get(NoteId("n1"))?.bodyHtml, "the body was replaced")
            assertNull(repo.get(NoteId("n1"))?.bodyMarkdown, "the legacy projection is cleared")
            assertEquals(1, sync.enqueuedEntities.size, "a confirmed write reaches the outbox")
        }

    @Test
    fun `the brief reads ask for plain notes only, before the limit`() =
        runTest {
            val project = ProjectId.generate()
            val task = TaskId.generate()
            // The foreign keys are real here, so the rows the note points at exist.
            db.projectDao().upsert(
                ProjectEntity(
                    id = project.value,
                    userId = user.value,
                    name = "Kitchen",
                    color = 0,
                    icon = null,
                    description = null,
                    createdAt = 0,
                    updatedAt = 0,
                    dueDate = null,
                    team = null,
                    externalId = null,
                    deletedAt = null,
                    parentId = null,
                ),
            )
            db.taskDao().upsert(
                TaskEntity(
                    id = task.value,
                    title = "Draft",
                    description = null,
                    projectId = project.value,
                    dueDate = null,
                    dueTime = null,
                    startDate = null,
                    startTime = null,
                    endDate = null,
                    endTime = null,
                    accentColor = null,
                    emoji = null,
                    completedAt = null,
                    archivedAt = null,
                    createdAt = 0,
                    updatedAt = 0,
                    userId = user.value,
                ),
            )
            // One brief, buried behind more than the page of newer daily notes.
            notesDomainSeed(
                id = "brief",
                kind = NoteKind.Plain,
                projectId = project,
                taskId = task,
                createdAtMs = 1_000,
            )
            repeat(60) { index ->
                notesDomainSeed(
                    id = "daily-$index",
                    kind = NoteKind.Daily,
                    projectId = project,
                    taskId = task,
                    createdAtMs = 2_000L + index,
                )
            }

            val byProject = repo.watchAttachedToProject(project, 20).first()
            val byTask = repo.watchPlainAttachedToTask(task, 20).first()

            assertEquals("brief", byProject.singleOrNull()?.id?.value, "the project brief is found")
            assertEquals("brief", byTask.singleOrNull()?.id?.value, "the task brief is found")
        }

    @Test
    fun `a non-positive limit is refused rather than becoming unbounded`() =
        runTest {
            val project = ProjectId.generate()
            val failure = runCatching { repo.watchAttachedToProject(project, -1) }
            assertTrue(failure.isFailure, "a negative limit is not a bound")
        }

    /** Seeds a note row directly, with the kind and attachments the reads filter on. */
    private suspend fun notesDomainSeed(
        id: String,
        kind: NoteKind,
        projectId: ProjectId,
        taskId: TaskId,
        createdAtMs: Long,
    ) {
        repo.upsert(
            Note(
                id = NoteId(id),
                userId = user,
                title = id,
                bodyHtml = "<p>$id</p>",
                kind = kind,
                projectId = projectId,
                taskId = taskId,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(createdAtMs),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(createdAtMs),
            ),
        )
    }
}
