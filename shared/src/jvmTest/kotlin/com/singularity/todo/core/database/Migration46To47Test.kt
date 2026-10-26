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
import kotlin.test.assertTrue

/**
 * The v46 → v47 upgrade ([Migration46To47]) — the `tasks.workflow_state` column.
 *
 * The column holds the state the user has claimed for a task's work: `Open`,
 * `NextUp` or `InProgress`. It has a constant SQL default, which is what makes
 * the upgrade safe — every task that existed before it reads back as `Open`,
 * and "nothing claimed" is exactly what `Open` means.
 *
 * ## The properties that matter
 *
 * 1. **The column exists.** The schema validation on the next open fails otherwise.
 * 2. **A task that predates the upgrade reads back as `Open`.** Not null, not an
 *    empty string, and not some other name — the default is the claim "nothing
 *    claimed", and it has to be the one the task carries.
 * 3. **The column is writable.** A state can be set after the upgrade; the
 *    column is not a leftover.
 * 4. **A note that points at a task keeps pointing at it.**
 *
 * The fourth is the reason this migration is written by hand rather than
 * derived. Room's auto-migration rewrites the whole `tasks` table, and dropping
 * a table runs the foreign-key actions of its rows — `notes.task_id` is
 * `ON DELETE SET NULL`, so every note that pointed at a task would come back
 * with no task at all. This test is what fails if somebody replaces the
 * hand-written `ALTER TABLE` with an auto-migration.
 */
@Tag("slow")
class Migration46To47Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration46to47-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { connection ->
            applyExportedSchema(45, connection)
            // A task that exists *before* the upgrade. It has no workflow state
            // to bring with it, so the column's default is what it must read as.
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
                "workflow_state" in columns,
                "the upgrade must add the column the task model reads: $columns",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a task that predates the upgrade reads back as claiming nothing`() = runTest {
        val db = openMigrated()
        try {
            val state = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT workflow_state FROM tasks WHERE id = 't-old'") { statement ->
                    assertTrue(statement.step(), "the task must survive the upgrade")
                    statement.getText(0)
                }
            }

            assertEquals(
                "Open",
                state,
                "a task that existed before the upgrade has had nothing claimed about it",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a state can be set after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            db.useConnection(isReadOnly = false) { transactor ->
                transactor.usePrepared(
                    "UPDATE tasks SET workflow_state = 'InProgress' WHERE id = 't-old'",
                ) { it.step() }
            }

            val state = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT workflow_state FROM tasks WHERE id = 't-old'") { statement ->
                    assertTrue(statement.step())
                    statement.getText(0)
                }
            }

            assertEquals("InProgress", state, "the column must be writable after the upgrade")
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
