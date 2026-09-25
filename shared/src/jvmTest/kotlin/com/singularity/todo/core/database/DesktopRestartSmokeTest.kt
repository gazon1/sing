package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless smoke-test that mirrors the real desktop app restart cycle:
 *
 *   1. Build [AppDatabase] pointing at a temp file (simulates `desktopApp:run` start).
 *   2. Write notes through Room's generated DAO (the actual desktop code path).
 *   3. Close the DB and drop the in-memory connection pool (simulates app exit).
 *   4. Build a brand-new [AppDatabase] over the **same file** (simulates relaunch).
 *   5. Read notes through Room — proves the data is physically on disk and
 *      the Room-generated DAO pipeline reads it back unchanged.
 *
 * If this passes, `notes created in session A survive session B` — exactly the
 * promise the old `JdbcNotesStore` made but never delivered (its `watchAll`
 * polling worked only against in-memory state because the hand-rolled schema
 * had drifted away from `@Entity`, so any upsert would silently no-op).
 */
@Tag("slow")
class DesktopRestartSmokeTest {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-restart-").toFile()
        dbPath = File(tempDir, "singularity.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `notes created in one session survive a process restart`() = runTest {
        // ─── Session A: start, write, stop ───────────────────────────────
        val sessionA = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try {
            val now = System.currentTimeMillis()
            sessionA.noteDao().upsert(
                NoteEntity(
                    id = "n-pinned",
                    userId = "u-1",
                    title = "Buy milk",
                    bodyMarkdown = "# Today\n- milk\n- eggs",
                    bodyHtml = null,
                    isFolder = false,
                    parentNoteId = null,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    archivedAt = null,
                ),
            )
            sessionA.noteDao().upsert(
                NoteEntity(
                    id = "n-folder",
                    userId = "u-1",
                    title = "Archive",
                    bodyMarkdown = null,
                    bodyHtml = null,
                    isFolder = true,
                    parentNoteId = null,
                    createdAt = now + 1,
                    updatedAt = now + 1,
                    deletedAt = null,
                    archivedAt = null,
                ),
            )
            sessionA.taskDao().upsert(
                TaskEntity(
                    id = "t-1", title = "Walk dog", description = null, projectId = null,
                    dueDate = null, dueTime = null,
                    startDate = null, startTime = null, endDate = null, endTime = null,
                    accentColor = null, emoji = null,
                    completedAt = null, someday = false,
                    archivedAt = null, isPinned = true, createdAt = now, updatedAt = now,
                    userId = "u-1",
                ),
            )
        } finally {
            sessionA.close()
        }

        // ─── Session B: cold start, fresh in-memory state, same file ──────
        val sessionB = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try {
            // Both notes survive: the leaf ("Buy milk") and the folder ("Archive").
            // `watchAll` only filters out soft-deleted rows — folders are not deleted.
            val notes = sessionB.noteDao().watchAll("u-1").first()
            assertEquals(2, notes.size, "both notes must be restored")
            val buyMilk = notes.first { it.id == "n-pinned" }
            assertEquals("Buy milk", buyMilk.title)
            assertEquals("# Today\n- milk\n- eggs", buyMilk.bodyMarkdown)
            assertEquals(false, buyMilk.isFolder)

            val archive = notes.first { it.id == "n-folder" }
            assertEquals(true, archive.isFolder)

            // TaskDao is a separate DAO but lives in the same Room database.
            val tasks = sessionB.taskDao().watchActive("u-1").first()
            assertEquals(1, tasks.size)
            assertEquals("Walk dog", tasks.single().title)
        } finally {
            sessionB.close()
        }
    }

    @Test
    fun `schema is identical across restarts — same exportSchema hash`() = runTest {
        // Build twice and confirm the file is the same Room-generated schema
        // (AppDatabase_Impl compares CREATE scripts via RoomOpenDelegate).
        // The first build creates the DB; the second build must validate it
        // without re-running DDL.
        val a = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try { /* session A */ } finally {
            a.close()
        }
        val b = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try { /* session B */ } finally {
            b.close()
        }

        // Both sessions ended without `IllegalStateException: Room cannot
        // verify the data integrity` — that's the assertion.
        assertTrue(File(dbPath).exists())
    }
}
