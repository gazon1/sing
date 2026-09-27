package com.singularity.todo.feature.notes

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.CrossUserWriteException
import com.singularity.todo.core.sync.FakeSyncRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Sync-propagation contract for the real [RoomNotesRepository] against real
 * SQLite.
 *
 * Notes carried the worst instance of the bypass class: 14 write methods reached
 * Room without ever calling `enqueue`. `updateContent` is the severe one — it is
 * the note editor's autosave path, invoked on every debounced keystroke — so a
 * note edited on one device simply never reached the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
class RoomNotesRepositorySyncTest {

    private val user = UserId("u1")

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: FakeSyncRepository
    private lateinit var repo: RoomNotesRepository

    @BeforeTest
    fun setUp() = runTest {
        tempDir = Files.createTempDirectory("singularity-notes-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
        sync = FakeSyncRepository(this)
        repo = RoomNotesRepository(
            noteDao = db.noteDao(),
            clock = Clock.System,
            currentUser = FakeProfileAwareCurrentUser(
                FakeAuthRepository(Session.Anonymous(user)),
                scope = backgroundScope,
            ),
            syncRepository = sync,
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun note(id: String = "n1", title: String = "Note") = Note(
        id = NoteId(id),
        userId = user,
        title = title,
        kind = NoteKind.Plain,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun lastEnqueued() = sync.enqueuedEntities.last()

    @Test
    fun `createWithContent enqueues`() = runTest {
        val result = repo.createWithContent(NoteId("n1"), "T", "# body", "<p>body</p>")
        assertTrue(result.isSuccess, "createWithContent failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size)
    }

    @Test
    fun `createNoteWithTitle enqueues`() = runTest {
        val result = repo.createNoteWithTitle("Quick note")
        assertTrue(result.isSuccess, "createNoteWithTitle failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size)
    }

    @Test
    fun `updateContent enqueues — this is the editor autosave path`() = runTest {
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
    fun `soft delete enqueues the trashed state`() = runTest {
        repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
        sync.enqueuedEntities.clear()

        val result = repo.delete(NoteId("n1"))

        assertTrue(result.isSuccess, "delete failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size, "delete must reach the outbox")
        assertNotNull(lastEnqueued().toJson()["deletedAt"], "deletedAt must be in the payload")
    }

    @Test
    fun `restore enqueues`() = runTest {
        repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
        repo.delete(NoteId("n1"))
        sync.enqueuedEntities.clear()

        val result = repo.restore(NoteId("n1"))

        assertTrue(result.isSuccess, "restore failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size)
    }

    @Test
    fun `archive and unarchive enqueue`() = runTest {
        repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
        sync.enqueuedEntities.clear()

        assertTrue(repo.archive(NoteId("n1")).isSuccess)
        assertEquals(1, sync.enqueuedEntities.size, "archive must reach the outbox")

        sync.enqueuedEntities.clear()
        assertTrue(repo.unarchive(NoteId("n1")).isSuccess)
        assertEquals(1, sync.enqueuedEntities.size, "unarchive must reach the outbox")
    }

    @Test
    fun `setPinned setColor setSortOrder enqueue`() = runTest {
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
    fun `setOutgoingLinks enqueues, because links are part of the synced payload`() = runTest {
        repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
        sync.enqueuedEntities.clear()

        val result = repo.setOutgoingLinks(NoteId("n1"), listOf("note://n2"))
        assertTrue(result.isSuccess, "setOutgoingLinks failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size, "link change must reach the outbox")
    }

    @Test
    fun `saveAsTemplate enqueues`() = runTest {
        repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")
        sync.enqueuedEntities.clear()

        assertTrue(repo.saveAsTemplate(NoteId("n1")).isSuccess)
        assertEquals(1, sync.enqueuedEntities.size, "saveAsTemplate must reach the outbox")
    }

    @Test
    fun `createFromTemplate enqueues`() = runTest {
        repo.createWithContent(NoteId("tpl"), "Template", "b", "<p>b</p>")
        sync.enqueuedEntities.clear()

        val result = repo.createFromTemplate(NoteId("tpl"), "From tpl", null)

        assertTrue(result.isSuccess, "createFromTemplate failed: ${result.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size)
    }

    @Test
    fun `getOrCreateDailyNote enqueues only when it actually creates`() = runTest {
        val first = repo.getOrCreateDailyNote("2026-09-27", null)
        assertTrue(first.isSuccess, "create failed: ${first.exceptionOrNull()}")
        assertEquals(1, sync.enqueuedEntities.size, "the creating call must enqueue")

        sync.enqueuedEntities.clear()
        val second = repo.getOrCreateDailyNote("2026-09-27", null)
        assertTrue(second.isSuccess)
        assertEquals(first.getOrThrow(), second.getOrThrow(), "must return the same note")
        assertTrue(
            sync.enqueuedEntities.isEmpty(),
            "the no-op path changed nothing and must not enqueue",
        )
    }

    @Test
    fun `mutations on a missing note fail and enqueue nothing`() = runTest {
        assertTrue(repo.updateContent(NoteId("nope"), "T", "b", "<p>b</p>").isFailure)
        assertTrue(repo.archive(NoteId("nope")).isFailure)
        assertTrue(repo.setPinned(NoteId("nope"), true).isFailure)
        assertTrue(repo.delete(NoteId("nope")).isFailure)
        assertTrue(sync.enqueuedEntities.isEmpty(), "a failed mutation must not enqueue")
    }

    @Test
    fun `update rejects a foreign userId and normalises an anonymous one`() = runTest {
        repo.createWithContent(NoteId("n1"), "T", "b", "<p>b</p>")

        val foreign = repo.update(note().copy(userId = UserId("intruder")))
        assertTrue(foreign.isFailure, "cross-user update must be rejected")
        assertTrue(foreign.exceptionOrNull() is CrossUserWriteException)

        val anonymous = repo.update(note().copy(userId = UserId.anonymous))
        assertTrue(anonymous.isSuccess, "anonymous must be normalised, not orphaned")
        assertEquals(user, anonymous.getOrThrow().userId)
    }
}
