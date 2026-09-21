package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Room 3 pipeline on JVM — confirms that:
 *
 * 1. `AppDatabaseFactory` produces a working [AppDatabase] with `BundledSQLiteDriver`.
 * 2. PRAGMAs from [com.singularity.todo.core.database.contract.PlatformPragmas] are
 *    accepted by the bundled SQLite (e.g. WAL mode).
 * 3. `NoteDao.upsert` → `watchAll` round-trips through Room's invalidation flow
 *    exactly like on Android.
 *
 * Each test gets its own temp directory; the DB file is deleted on `@After`.
 */
class AppDatabaseFactoryJvmTest {

    private lateinit var tempDir: File
    private lateinit var dbPath: String
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-test-").toFile()
        dbPath = File(tempDir, "singularity.db").absolutePath
        db = AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `database file is created on disk`() {
        assertTrue(File(dbPath).exists(), "DB not created at $dbPath")
    }

    @Test
    fun `note round-trip via upsert then watchAll`() = runTest {
        val dao = db.noteDao()
        val now = System.currentTimeMillis()
        val entity = NoteEntity(
            id = "n1",
            userId = "u1",
            title = "Test",
            bodyMarkdown = "# Hi",
            bodyHtml = null,
            isFolder = false,
            parentNoteId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            archivedAt = null,
        )

        dao.upsert(entity)

        val all = dao.watchAll("u1").first()
        assertEquals(1, all.size)
        assertEquals("Test", all.single().title)
    }

    @Test
    fun `soft delete hides note from watchAll but keeps it in watchById`() = runTest {
        val dao = db.noteDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            NoteEntity(
                id = "n1", userId = "u1", title = "T", bodyMarkdown = null, bodyHtml = null,
                isFolder = false, parentNoteId = null, createdAt = now, updatedAt = now,
                deletedAt = null, archivedAt = null,
            ),
        )

        dao.softDelete("n1", ts = now + 1)

        assertEquals(0, dao.watchAll("u1").first().size)
        val byId = dao.watchByIdForUser("n1", "u1").first()
        assertNotNull(byId)
        assertEquals(now + 1, byId.deletedAt)
    }

    @Test
    fun `restore brings a soft-deleted note back to watchAll`() = runTest {
        val dao = db.noteDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            NoteEntity(
                id = "n1", userId = "u1", title = "T", bodyMarkdown = null, bodyHtml = null,
                isFolder = false, parentNoteId = null, createdAt = now, updatedAt = now,
                deletedAt = null, archivedAt = null,
            ),
        )
        dao.softDelete("n1", ts = now + 1)
        dao.restore("n1", ts = now + 2)

        assertEquals(1, dao.watchAll("u1").first().size)
        assertEquals(null, dao.watchByIdForUser("n1", "u1").first()?.deletedAt)
    }

    @Test
    fun `tasks and notes share the same database file`() = runTest {
        // Sanity: both DAOs work off the same Room instance — proves AppDatabase
        // is a single source of truth, not a per-DAO file.
        val now = System.currentTimeMillis()
        db.noteDao().upsert(
            NoteEntity(
                id = "n1", userId = "u1", title = "T", bodyMarkdown = null, bodyHtml = null,
                isFolder = false, parentNoteId = null, createdAt = now, updatedAt = now,
                deletedAt = null, archivedAt = null,
            ),
        )
        db.taskDao().upsert(
            TaskEntity(
                id = "t1", title = "Task", description = null, projectId = null,
                dueDate = null, dueTime = null, completedAt = null, someday = false,
                archivedAt = null, isPinned = false, createdAt = now, updatedAt = now,
                userId = "u1",
            ),
        )
        assertEquals(1, db.noteDao().watchAll("u1").first().size)
        assertEquals(1, db.taskDao().watchActive("u1").first().size)
    }
}
