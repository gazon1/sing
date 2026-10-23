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
 * The v43 → v44 upgrade ([Migration43To44]) — the `projects.closed_at` column.
 *
 * A closed project is one whose work is finished: the row stays, the project stays
 * readable, and it leaves the project list. The column is nullable, which is what
 * makes the upgrade safe — but only if the rows that existed before the upgrade
 * come back as *open* projects, and not as something that reads as closed.
 *
 * ## The properties that matter
 *
 * 1. **The column exists.** The schema validation on the next open fails otherwise.
 * 2. **A row that predates the upgrade reads back as open.** `closed_at` is null
 *    for it, not zero and not the epoch.
 * 3. **A project can be closed after the upgrade.** The column is writable, not
 *    a leftover.
 */
@Tag("slow")
@Tag("slow:migration")
class Migration43To44Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration43to44-").toFile()
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
            applyExportedSchema(43, connection)
            // A project that exists *before* the upgrade: it has to come back as an
            // open project, not as a closed one.
            connection.execSQL(
                """
                INSERT INTO projects (
                    id, user_id, name, color, created_at, updated_at, is_default,
                    is_deleted, sort_order, server_version, sync_status
                )
                VALUES ('p-old', 'u1', 'Old project', 0, 1000, 1000, 0, 0, 0, 0, 'LOCAL_ONLY')
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
                transactor.usePrepared("PRAGMA table_info(projects)") { statement ->
                    buildList {
                        while (statement.step()) {
                            add(statement.getText(1))
                        }
                    }
                }
            }

            assertTrue(
                "closed_at" in columns,
                "the upgrade must add the column the project model reads: $columns",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a project that predates the upgrade reads back as open`() = runTest {
        val db = openMigrated()
        try {
            val row = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT closed_at FROM projects WHERE id = 'p-old'") { statement ->
                    assertTrue(statement.step(), "the project must survive the upgrade")
                    if (statement.isNull(0)) null else statement.getLong(0)
                }
            }

            assertNull(row, "a project that existed before the upgrade is an open project")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a project can be closed after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            val rows = db.projectDao().closeForUser("p-old", 2000L, "u1")
            assertEquals(1, rows, "the close must reach the row")

            val row = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT closed_at FROM projects WHERE id = 'p-old'") { statement ->
                    assertTrue(statement.step())
                    statement.getLong(0)
                }
            }

            assertEquals(2000L, row, "the column must be writable after the upgrade")
        } finally {
            db.close()
        }
    }
}
