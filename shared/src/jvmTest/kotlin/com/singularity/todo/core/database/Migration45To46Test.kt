package com.singularity.todo.core.database

import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v45 → v46 upgrade ([Migration45To46]) — the `tasks.series_id` column.
 *
 * A series is one piece of work scheduled on several dates: several task rows
 * that share a series id. The column is nullable, which is what makes the
 * upgrade safe — but only if the rows that existed before the upgrade come back
 * as tasks that stand alone, and not as members of a series nobody created.
 *
 * ## The properties that matter
 *
 * 1. **The column exists.** The schema validation on the next open fails otherwise.
 * 2. **A task that predates the upgrade reads back as standing alone.**
 *    `series_id` is null for it, not an empty string and not a zero.
 * 3. **A task can be put into a series after the upgrade.** The column is
 *    writable, not a leftover.
 * 4. **A note that points at a task keeps pointing at it.** The upgrade adds a
 *    column; it does not rewrite the table the note points into.
 */
@Tag("slow")
class Migration45To46Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration45to46-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sharedDir(): File =
        File(System.getProperty("commonMain.root")!!).parentFile.parentFile.parentFile

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { connection ->
            applyExportedSchema(45, connection)
            // A task that exists *before* the upgrade: it has to come back as a
            // task that stands alone, not as a member of a series.
            connection.execSQL(
                """
                INSERT INTO tasks (
                    id, user_id, title, priority, kind, someday, is_pinned,
                    created_at, updated_at, server_version, sync_status
                )
                VALUES (
                    't-old', 'u1', 'Old task', 'None', 'Task', 0, 0,
                    1000, 1000, 0, 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
            // A note that points at the task: the relation is the thing the
            // upgrade must not cost anybody.
            connection.execSQL(
                """
                INSERT INTO notes (
                    id, user_id, title, is_folder, kind, is_pinned, sort_order,
                    word_count, char_count, outgoing_links, task_id,
                    created_at, updated_at, server_version, sync_status
                )
                VALUES (
                    'n-old', 'u1', 'Old note', 0, 'Plain', 0, 0,
                    0, 0, '[]', 't-old',
                    1000, 1000, 0, 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `the column exists after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            val columns = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("PRAGMA table_info(tasks)") { statement ->
                    buildList {
                        while (statement.step()) {
                            add(statement.getText(1))
                        }
                    }
                }
            }

            assertTrue(
                "series_id" in columns,
                "the upgrade must add the column the task model reads: $columns",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a task that predates the upgrade reads back as standing alone`() = runTest {
        val db = openMigrated()
        try {
            val row = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT series_id FROM tasks WHERE id = 't-old'") { statement ->
                    assertTrue(statement.step(), "the task must survive the upgrade")
                    if (statement.isNull(0)) null else statement.getText(0)
                }
            }

            assertNull(row, "a task that existed before the upgrade belongs to no series")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a task can be put into a series after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            db.useConnection(isReadOnly = false) { transactor ->
                transactor.usePrepared("UPDATE tasks SET series_id = 'series-1' WHERE id = 't-old'") {
                    it.step()
                }
            }

            val row = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT series_id FROM tasks WHERE id = 't-old'") { statement ->
                    assertTrue(statement.step())
                    statement.getText(0)
                }
            }

            assertEquals("series-1", row, "the column must be writable after the upgrade")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a note that points at a task keeps that task after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            val row = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT task_id FROM notes WHERE id = 'n-old'") { statement ->
                    assertTrue(statement.step(), "the note must survive the upgrade")
                    if (statement.isNull(0)) null else statement.getText(0)
                }
            }

            assertEquals(
                "t-old",
                row,
                "adding a column must not cost the note the task it points at",
            )
        } finally {
            db.close()
        }
    }
}
