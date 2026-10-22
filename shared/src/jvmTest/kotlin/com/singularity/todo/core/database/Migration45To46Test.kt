package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** Applies every entity DDL + `setupQueries` from the exported v45 schema. */
    private fun applyV45Fixture(connection: SQLiteConnection) {
        val schemaJson = File(sharedDir(), "schemas/com.singularity.todo.core.database.AppDatabase/45.json")
        assertTrue(schemaJson.exists(), "v45 schema not found at $schemaJson")

        val database = Json.parseToJsonElement(schemaJson.readText()).jsonObject["database"]!!.jsonObject
        for (entity in database["entities"]!!.jsonArray) {
            val obj = entity.jsonObject
            val tableName = obj["tableName"]!!.jsonPrimitive.content
            connection.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
            for (index in obj["indices"]?.jsonArray.orEmpty()) {
                connection.execSQL(
                    index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName),
                )
            }
        }
        for (query in database["setupQueries"]!!.jsonArray) {
            connection.execSQL(query.jsonPrimitive.content)
        }
        connection.execSQL("PRAGMA user_version = 45")
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { connection ->
            applyV45Fixture(connection)
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
}
