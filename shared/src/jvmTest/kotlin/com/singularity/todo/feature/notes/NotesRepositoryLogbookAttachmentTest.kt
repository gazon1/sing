@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The task attachment of a note, against real SQLite.
 *
 * A note attached to a task used to fall out of that task's logbook on the first
 * save: the editor wrote the note with the *create* primitive, and Room's
 * `@Upsert` replaces every column — so the `task_id` the note was created with
 * was overwritten with `NULL`.
 *
 * These tests pin the two halves of the fix:
 *
 * - the create primitive writes the attachment ([createForTask]), and
 * - the content save that follows it does not touch the attachment
 *   ([updateContent]).
 *
 * The last test is a characterization of the hazard: it says why the editor has
 * to create exactly once instead of relying on a create being safe on top of an
 * existing row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
@Tag("slow:data")
class NotesRepositoryLogbookAttachmentTest {

    private val user = UserId("u1")
    private val taskId = TaskId("t1")

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: FakeSyncRepository
    private lateinit var repo: NotesRepositoryImpl

    @BeforeTest
    fun setUp() = runTest {
        tempDir = Files.createTempDirectory("singularity-logbook-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
        sync = FakeSyncRepository(this)
        val currentUser = FakeProfileAwareCurrentUser(
            FakeAuthRepository(Session.Anonymous(user)),
            scope = backgroundScope,
        )
        repo = NotesRepositoryImpl(
            noteDao = db.noteDao(),
            textIndex = db.textIndex(currentUser),
            clock = Clock.System,
            currentUser = currentUser,
            syncRepository = sync,
            unitOfWork = FakeUnitOfWork(),
        )
        // The attachment is a foreign key, so the task has to exist before the note
        // may point at it.
        db.taskDao().upsert(
            com.singularity.todo.core.database.TaskEntity(
                id = taskId.value,
                title = "Buy milk",
                description = null,
                priority = TaskPriority.None,
                kind = TaskKind.Task,
                projectId = null,
                parentTaskId = null,
                dueDate = null,
                dueTime = null,
                startDate = null,
                startTime = null,
                endDate = null,
                endTime = null,
                accentColor = null,
                emoji = null,
                estimateMinutes = null,
                completedAt = null,
                someday = false,
                archivedAt = null,
                isPinned = false,
                recurrenceRule = null,
                outgoingLinks = "[]",
                aiSuppressedTagIds = "[]",
                createdAt = 1_000L,
                updatedAt = 1_000L,
                userId = user.value,
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private suspend fun storedTaskIdOf(noteId: String): String? =
        db.noteDao().getByIdForUser(noteId, user.value)?.taskId

    @Test
    fun `createForTask writes the attachment onto the note`() = runTest {
        val result = repo.createForTask(
            id = NoteId("n1"),
            taskId = taskId,
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )

        assertTrue(result.isSuccess, "createForTask failed: ${result.exceptionOrNull()}")
        assertEquals(taskId.value, storedTaskIdOf("n1"))
    }

    @Test
    fun `the attached note is in the task's logbook right after it is created`() = runTest {
        repo.createForTask(
            id = NoteId("n1"),
            taskId = taskId,
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )

        val attached = repo.watchForTask(taskId).first()
        assertEquals(1, attached.size)
        assertEquals("Bought the good one", attached.first().title)
    }

    @Test
    fun `a content save does not drop the attachment — the editor's autosave path`() = runTest {
        repo.createForTask(
            id = NoteId("n1"),
            taskId = taskId,
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )

        // What the editor's autosave does on every debounced keystroke.
        val result = repo.updateContent(
            id = NoteId("n1"),
            title = "Bought the good one (2l)",
            bodyMarkdown = "",
            bodyHtml = "<p>Bought the good one (2l)</p>",
        )

        assertTrue(result.isSuccess, "updateContent failed: ${result.exceptionOrNull()}")
        assertEquals(
            taskId.value,
            storedTaskIdOf("n1"),
            "a content save must not detach the note from its task",
        )
    }

    @Test
    fun `the note is still in the task's logbook after a content save`() = runTest {
        repo.createForTask(
            id = NoteId("n1"),
            taskId = taskId,
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )
        repo.updateContent(
            id = NoteId("n1"),
            title = "Bought the good one (2l)",
            bodyMarkdown = "",
            bodyHtml = "<p>Bought the good one (2l)</p>",
        )

        val attached = repo.watchForTask(taskId).first()
        assertEquals(1, attached.size, "the note left the logbook after its content was saved")
    }

    @Test
    fun `a note of another task is not in this task's logbook`() = runTest {
        val other = TaskId("t2")
        repo.createForTask(
            id = NoteId("n1"),
            taskId = other,
            title = "Somewhere else",
            bodyMarkdown = null,
            bodyHtml = "<p>Somewhere else</p>",
        )

        assertTrue(repo.watchForTask(taskId).first().isEmpty())
    }

    @Test
    fun `a note that only links to the task is not in the logbook`() = runTest {
        // A `task://` link in the body: the note is *about* the task, not attached to it.
        repo.createWithContent(
            id = NoteId("n1"),
            title = "Mentions the task",
            bodyMarkdown = "",
            bodyHtml = "<p>Mentions the task</p>",
        )
        repo.setOutgoingLinks(NoteId("n1"), listOf("task://${taskId.value}"))

        assertTrue(
            repo.watchForTask(taskId).first().isEmpty(),
            "a note that merely links to the task belongs in the task's \"Linked\" card, " +
                "not in its logbook",
        )
    }

    /**
     * The hazard the editor has to avoid, pinned so it cannot be forgotten.
     *
     * `createWithContent` is a *whole-row* write: Room's `@Upsert` replaces every
     * column, so writing a note that already exists — with a row that carries no
     * `task_id` — clears the attachment. This is why the editor creates the note
     * exactly once and every later save is a targeted UPDATE; it is not a licence
     * to create twice.
     */
    @Test
    fun `the create primitive replaces the whole row — it is not safe on an existing note`() = runTest {
        repo.createForTask(
            id = NoteId("n1"),
            taskId = taskId,
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )

        repo.createWithContent(
            id = NoteId("n1"),
            title = "Bought the good one (2l)",
            bodyMarkdown = "",
            bodyHtml = "<p>Bought the good one (2l)</p>",
        )

        assertNull(
            storedTaskIdOf("n1"),
            "a whole-row write is expected to clear the attachment — that is the hazard",
        )
    }

    @Test
    fun `the created note is enqueued for sync`() = runTest {
        val result = repo.createForTask(
            id = NoteId("n1"),
            taskId = taskId,
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )

        assertTrue(result.isSuccess)
        assertNotNull(sync.enqueuedEntities.lastOrNull())
    }
}
