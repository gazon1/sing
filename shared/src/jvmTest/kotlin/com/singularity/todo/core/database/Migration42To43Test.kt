package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.search.TextMatchQuery
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertTrue

/**
 * The v42 → v43 upgrade ([Migration42To43]) — the full-text indexes.
 *
 * The upgrade creates four virtual tables and fills them with the rows that
 * already exist. The second half is the part that is easy to leave out, and the
 * failure it causes is silent: a search that returns fewer results than the
 * database holds looks like "the search does not find my old task", and
 * nothing reports it.
 *
 * ## The properties that matter
 *
 * 1. **The indexes exist.** The schema validation on the next open fails
 *    otherwise, which is loud — but only on the next open, so the check is
 *    here.
 * 2. **The rows that predate the upgrade are in the index.** The `rebuild` in
 *    the migration is what puts them there.
 * 3. **The index is an external-content table.** The text is not copied; the
 *    index holds the postings and reads the row from the content table.
 */
@Tag("slow")
class Migration42To43Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration42to43-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sharedDir(): File =
        File(System.getProperty("commonMain.root")!!).parentFile.parentFile.parentFile

    /** Applies every entity DDL + `setupQueries` from the exported v42 schema. */
    private fun applyV42Fixture(connection: SQLiteConnection) {
        val schemaJson = File(sharedDir(), "schemas/com.singularity.todo.core.database.AppDatabase/42.json")
        assertTrue(schemaJson.exists(), "v42 schema not found at $schemaJson")

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
        connection.execSQL("PRAGMA user_version = 42")
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { connection ->
            applyV42Fixture(connection)
            // A task that exists *before* the upgrade: the migration has to put
            // it into the index it creates.
            connection.execSQL(
                """
                INSERT INTO tasks (
                    id, title, description, priority, kind, someday, is_pinned,
                    outgoing_links, ai_suppressed_tag_ids, created_at, updated_at, user_id,
                    server_version, sync_status
                )
                VALUES (
                    't-old', 'Write the quarterly report', NULL, 'None', 'Task', 0, 0,
                    '[]', '[]', 1000, 1000, 'u1',
                    0, 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `the indexes exist after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            // The query runs on Room's own connection, so it reads the
            // database the migration has just written.
            val tables = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared(
                    "SELECT name FROM sqlite_master " +
                        "WHERE type = 'table' AND sql LIKE 'CREATE VIRTUAL TABLE%'",
                ) { statement ->
                    buildList {
                        while (statement.step()) {
                            add(statement.getText(0))
                        }
                    }
                }
            }

            assertEquals(
                listOf("notes_fts", "projects_fts", "tags_fts", "tasks_fts"),
                tables.sorted(),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a row that predates the upgrade is found by the index`() = runTest {
        val db = openMigrated()
        try {
            val found = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("quarter"))).first()

            assertEquals(
                listOf("t-old"),
                found.map { it.id },
                "the rows that existed before the upgrade have to be in the index it creates",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the index holds no second copy of the text`() = runTest {
        val db = openMigrated()
        try {
            val createSql = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared(
                    "SELECT sql FROM sqlite_master WHERE name = 'tasks_fts'",
                ) { statement ->
                    check(statement.step()) { "the index must exist" }
                    statement.getText(0)
                }
            }

            assertTrue(
                createSql.contains("content=`tasks`"),
                "the index must read the row from the content table, not keep its own copy: $createSql",
            )
        } finally {
            db.close()
        }
    }
}
