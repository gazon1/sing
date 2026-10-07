package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UnitOfWork] against a real database (ADR `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it`).
 *
 * ## Why this test exists at all
 *
 * The whole decision rests on one unverified claim: that two suspend DAO calls made
 * inside `withWriteTransaction` share the transaction's connection, so that a row
 * write in one repository and an outbox write in another commit together or not at all.
 *
 * If that claim were false, every one of the nineteen enqueue sites would be wrapped in
 * a transaction that commits anyway — turning a real defect into a slower one, and
 * adding a layer of ceremony that reads as a guarantee it does not provide. So the
 * property is asserted against SQLite rather than assumed from the API's shape.
 *
 * ## Why it crosses the process boundary
 *
 * Because it uses a real database file, not a fake. An in-memory fake DAO would happily
 * pass whether or not Room routes the second write onto the same connection, which is
 * the only thing under test. Hence `slow` — per the tag strategy, "slow" means the class
 * crosses a process boundary, not that it is slow.
 */
@Tag("slow")
class UnitOfWorkIsAtomicTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var unitOfWork: UnitOfWork

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("unit-of-work").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "app.db").path)
        unitOfWork = RoomUnitOfWork(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private val note = NoteEntity(
        id = "n1",
        userId = "u1",
        title = "a note",
        bodyMarkdown = null,
        bodyHtml = null,
        parentNoteId = null,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = null,
        archivedAt = null,
    )

    private val outboxRow = SyncOutboxEntity(
        patchId = "p1",
        ownerId = "u1",
        entityId = "n1",
        entityType = DocType.Note.key,
        payload = "{}",
        createdAt = 1L,
    )

    @Test
    fun `a write and the patch describing it both land`() = runTest {
        unitOfWork.write {
            db.noteDao().upsert(note)
            db.syncOutboxDao().insert(outboxRow)
        }

        assertEquals(1, db.noteDao().getByIdForUser("n1", "u1")?.let { 1 } ?: 0, "the row did not land")
        assertEquals(1, db.syncOutboxDao().getPending(now = 1L, ownerId = "u1").size, "the patch did not land")
    }

    @Test
    fun `a failure between the row and the patch rolls the row back too`() = runTest {
        // The half that loses the user's edit silently: the row is written, the patch
        // never is, and the device carries an edit no future cycle will ever send.
        assertFailsWith<IllegalStateException> {
            unitOfWork.write {
                db.noteDao().upsert(note)
                error("the patch could not be built")
            }
        }

        assertNull(db.noteDao().getByIdForUser("n1", "u1"), "the row survived a failed unit of work")
    }

    @Test
    fun `a failure after the patch rolls the patch back too`() = runTest {
        // The other half, and the worse one: the server is told about a document this
        // device does not have.
        assertFailsWith<IllegalStateException> {
            unitOfWork.write {
                db.noteDao().upsert(note)
                db.syncOutboxDao().insert(outboxRow)
                error("the row's timestamp could not be written")
            }
        }

        assertNull(
            db.syncOutboxDao().getPending(now = 1L, ownerId = "u1").singleOrNull(),
            "a patch outlived the row it describes",
        )
        assertNull(db.noteDao().getByIdForUser("n1", "u1"), "the row survived the rollback")
    }

    @Test
    fun `the value the block returns reaches the caller`() = runTest {
        val written = unitOfWork.write {
            db.noteDao().upsert(note)
            db.noteDao().getByIdForUser("n1", "u1")
        }

        assertTrue(written != null, "a call site that needs the written row must still have it")
    }

    @Test
    fun `a nested unit of work commits once, not twice`() = runTest {
        // Repositories call one another, and a nested `write` that opened its own
        // transaction would either deadlock or commit the outer work early — which
        // would reintroduce the split this port exists to remove.
        unitOfWork.write {
            db.noteDao().upsert(note)
            unitOfWork.write { db.syncOutboxDao().insert(outboxRow) }
        }

        val noteLanded = db.noteDao().getByIdForUser("n1", "u1") != null
        val patchLanded = db.syncOutboxDao().getPending(now = 1L, ownerId = "u1").singleOrNull() != null
        assertTrue(
            noteLanded && patchLanded,
            "a nested write did not join the outer transaction",
        )
    }
}
