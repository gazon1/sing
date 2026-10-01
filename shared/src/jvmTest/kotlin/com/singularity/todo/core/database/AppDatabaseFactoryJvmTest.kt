package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
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
@Tag("slow")
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

        assertEquals(1, dao.softDeleteForUser("n1", ts = now + 1, userId = "u1"))

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
        dao.softDeleteForUser("n1", ts = now + 1, userId = "u1")
        assertEquals(1, dao.restoreForUser("n1", ts = now + 2, userId = "u1"))

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
                dueDate = null, dueTime = null,
                startDate = null, startTime = null, endDate = null, endTime = null,
                accentColor = null, emoji = null,
                completedAt = null, someday = false,
                archivedAt = null, isPinned = false, createdAt = now, updatedAt = now,
                userId = "u1",
            ),
        )
        assertEquals(1, db.noteDao().watchAll("u1").first().size)
        assertEquals(1, db.taskDao().watchActive("u1").first().size)
    }

    // ── Cross-user write isolation ────────────────────────────────────────────
    //
    // Every ownership-scoped mutation must report 0 affected rows when the caller
    // is not the owner, and must leave the row untouched. This is the guarantee
    // the `*ForUser` + `require(rows > 0)` convention exists to provide, and the
    // fakes could not prove it — only a real SQLite round-trip can.

    @Test
    fun `note mutations are rejected for a foreign user`() = runTest {
        val dao = db.noteDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            NoteEntity(
                id = "n1", userId = "u1", title = "T", bodyMarkdown = null, bodyHtml = null,
                isFolder = false, parentNoteId = null, createdAt = now, updatedAt = now,
                deletedAt = null, archivedAt = null,
            ),
        )

        assertEquals(0, dao.softDeleteForUser("n1", ts = now + 1, userId = "intruder"))
        assertEquals(0, dao.restoreForUser("n1", ts = now + 1, userId = "intruder"))
        assertEquals(0, dao.archiveForUser("n1", ts = now + 1, userId = "intruder"))
        assertEquals(0, dao.unarchiveForUser("n1", ts = now + 1, userId = "intruder"))
        assertEquals(0, dao.setPinnedForUser("n1", true, now + 1, now + 1, "intruder"))
        assertEquals(0, dao.setColorForUser("n1", 1, now + 1, "intruder"))
        assertEquals(0, dao.setSortOrderForUser("n1", 7, now + 1, "intruder"))
        assertEquals(0, dao.setOutgoingLinksForUser("n1", "[]", now + 1, "intruder"))
        assertEquals(0, dao.setKindForUser("n1", "Template", now + 1, "intruder"))
        assertEquals(
            0,
            dao.updateContentForUser("n1", "H", "b", "", 0, 0, now + 1, "intruder"),
        )

        // Row is untouched and still visible to its real owner.
        val row = dao.watchByIdForUser("n1", "u1").first()
        assertNotNull(row)
        assertEquals(null, row.deletedAt)
        assertEquals(null, row.archivedAt)
        assertEquals(false, row.isPinned)
        assertEquals("T", row.title)
    }

    @Test
    fun `task mutations are rejected for a foreign user`() = runTest {
        val dao = db.taskDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            TaskEntity(
                id = "t1", title = "Task", description = null, projectId = null,
                dueDate = null, dueTime = null,
                startDate = null, startTime = null, endDate = null, endTime = null,
                accentColor = null, emoji = null,
                completedAt = null, someday = false,
                archivedAt = null, isPinned = false, createdAt = now, updatedAt = now,
                userId = "u1",
            ),
        )

        assertEquals(0, dao.softDeleteForUser("t1", now + 1, "intruder"))
        assertEquals(0, dao.restoreForUser("t1", now + 1, "intruder"))
        assertEquals(0, dao.markCompleteForUser("t1", now + 1, "intruder"))
        assertEquals(0, dao.markIncompleteForUser("t1", now + 1, "intruder"))
        assertEquals(0, dao.setPinnedForUser("t1", true, now + 1, "intruder"))
        assertEquals(0, dao.setOutgoingLinksForUser("t1", "[]", now + 1, "intruder"))
        assertEquals(0, dao.removeTagRefForUser("t1", "tag1", "intruder"))
        assertEquals(0, dao.clearDependenciesForUser("t1", "intruder"))

        // The INSERT cross-ref methods return Unit, so assert the effect: nothing written.
        dao.upsertTagCrossRefForUser("t1", "tag1", "intruder")
        dao.upsertDependencyForUser("t1", "t2", DependencyVerb.BLOCKS.name, "intruder")
        assertEquals(emptyList(), dao.getTagIdsForTask("t1").first())
        assertEquals(emptyList(), dao.getDependencyIdsForTask("t1").first())

        val row = dao.watchById("t1").first()
        assertNotNull(row)
        assertEquals(null, row.archivedAt)
        assertEquals(null, row.completedAt)
        assertEquals(false, row.isPinned)
    }

    @Test
    fun `task mutations succeed for the owner`() = runTest {
        val dao = db.taskDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            TaskEntity(
                id = "t1", title = "Task", description = null, projectId = null,
                dueDate = null, dueTime = null,
                startDate = null, startTime = null, endDate = null, endTime = null,
                accentColor = null, emoji = null,
                completedAt = null, someday = false,
                archivedAt = null, isPinned = false, createdAt = now, updatedAt = now,
                userId = "u1",
            ),
        )

        assertEquals(1, dao.markCompleteForUser("t1", now + 1, "u1"))
        assertEquals(1, dao.setPinnedForUser("t1", true, now + 2, "u1"))
        dao.upsertTagCrossRefForUser("t1", "tag1", "u1")
        dao.upsertDependencyForUser("t1", "t2", DependencyVerb.BLOCKS.name, "u1")
        assertEquals(1, dao.softDeleteForUser("t1", now + 3, "u1"))

        assertEquals(listOf("tag1"), dao.getTagIdsForTask("t1").first())
        assertEquals(1, dao.removeTagRefForUser("t1", "tag1", "u1"))
        assertEquals(emptyList(), dao.getTagIdsForTask("t1").first())
    }

    @Test
    fun `archiveCompletedForUser only archives the owner's completed tasks`() = runTest {
        val dao = db.taskDao()
        val now = System.currentTimeMillis()

        fun task(id: String, uid: String, completed: Boolean) = TaskEntity(
            id = id, title = "T", description = null, projectId = null,
            dueDate = null, dueTime = null,
            startDate = null, startTime = null, endDate = null, endTime = null,
            accentColor = null, emoji = null,
            completedAt = if (completed) now else null, someday = false,
            archivedAt = null, isPinned = false, createdAt = now, updatedAt = now,
            userId = uid,
        )

        dao.upsert(task("mine-done", "u1", completed = true))
        dao.upsert(task("mine-open", "u1", completed = false))
        dao.upsert(task("theirs-done", "u2", completed = true))

        val archived = dao.archiveCompletedForUser(now + 1, "u1")

        assertEquals(1, archived, "only u1's completed task should be archived")
        assertNotNull(dao.watchById("mine-done").first()?.archivedAt)
        assertEquals(null, dao.watchById("mine-open").first()?.archivedAt)
        assertEquals(
            null,
            dao.watchById("theirs-done").first()?.archivedAt,
            "u2's task must not be touched by a u1-scoped archive sweep",
        )
    }
}
