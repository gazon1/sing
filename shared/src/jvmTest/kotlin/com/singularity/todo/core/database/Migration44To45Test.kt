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
 * The v44 → v45 upgrade ([Migration44To45]) — the stored links, read by one grammar.
 *
 * The column `outgoing_links` holds a JSON array of `scheme://id` URLs. Two things
 * were wrong with it before this migration:
 *
 * 1. **The note-to-task relation was never filled.** `notes.task_id` had a foreign
 *    key and no data behind it: no migration ever read the note's own links to find
 *    the task. `Migration25To26Runner` was written to do that and was never wired in.
 * 2. **Links nothing can open stayed in the column.** An identifier outside
 *    `[A-Za-z0-9_-]` is not an identifier, so such a link is a claim of a relation
 *    that does not exist.
 *
 * ## The properties that matter
 *
 * 1. **A note that links to a task points at it afterwards.**
 * 2. **A note that already pointed at a task keeps that task.**
 * 3. **A link the grammar rejects is gone; a link it accepts stays.**
 */
@Tag("slow")
class Migration44To45Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration44to45-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sharedDir(): File =
        File(System.getProperty("commonMain.root")!!).parentFile.parentFile.parentFile

    /** Applies every entity DDL + `setupQueries` from the exported v44 schema. */
    private fun applyV44Fixture(connection: SQLiteConnection) {
        val schemaJson = File(sharedDir(), "schemas/com.singularity.todo.core.database.AppDatabase/44.json")
        assertTrue(schemaJson.exists(), "v44 schema not found at $schemaJson")

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
        connection.execSQL("PRAGMA user_version = 44")
    }

    private fun insertTask(connection: SQLiteConnection, id: String) {
        insertLegacyTask(connection, id = id)
    }

    private fun insertNote(connection: SQLiteConnection, id: String, links: String, taskId: String? = null) {
        connection.execSQL(
            """
            INSERT INTO notes (
                id, user_id, title, is_folder, outgoing_links, task_id,
                created_at, updated_at, server_version, sync_status
            )
            VALUES (
                '$id', 'u1', 'Note $id', 0, '$links', ${taskId?.let { "'$it'" } ?: "NULL"},
                1000, 1000, 0, 'LOCAL_ONLY'
            )
            """.trimIndent(),
        )
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { connection ->
            applyV44Fixture(connection)
            insertTask(connection, "t1")
            insertTask(connection, "t2")
            // A note that links to a task and has no task of its own.
            insertNote(connection, "n1", """["task://t1"]""")
            // A note that already points at a task, and also links to another one.
            insertNote(connection, "n2", """["task://t2"]""", taskId = "t1")
            // A note whose link nobody can open: the identifier is not the grammar's.
            insertNote(connection, "n3", """["task://not a valid id","note://n1"]""")
            // A note that links to nothing at all.
            insertNote(connection, "n4", "[]")
            // A task whose description links to a note and to something unreadable.
            insertLegacyTask(
                connection,
                id = "t3",
                columns = mapOf("outgoing_links" to """["note://n1","task://bad id"]"""),
            )
        }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    private suspend fun noteTaskId(db: AppDatabase, noteId: String): String? =
        db.useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared("SELECT task_id FROM notes WHERE id = '$noteId'") { statement ->
                assertTrue(statement.step(), "the note must survive the upgrade")
                if (statement.isNull(0)) null else statement.getText(0)
            }
        }

    private suspend fun links(db: AppDatabase, table: String, id: String): String =
        db.useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared("SELECT outgoing_links FROM $table WHERE id = '$id'") { statement ->
                assertTrue(statement.step(), "the row must survive the upgrade")
                statement.getText(0)
            }
        }

    @Test
    fun `a note that links to a task points at it after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            assertEquals(
                "t1",
                noteTaskId(db, "n1"),
                "the note carried the link; the column has to agree with it",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a note that already pointed at a task keeps that task`() = runTest {
        val db = openMigrated()
        try {
            assertEquals(
                "t1",
                noteTaskId(db, "n2"),
                "a note that already points at a task must not be re-pointed at another one",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a link the grammar rejects is gone and a link it accepts stays`() = runTest {
        val db = openMigrated()
        try {
            assertEquals(
                """["note://n1"]""",
                links(db, "notes", "n3"),
                "a link nobody can open is not a relation; the ones that can stay",
            )
            assertEquals(
                """["note://n1"]""",
                links(db, "tasks", "t3"),
                "the task's links are read by the same grammar",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a note without links has no task`() = runTest {
        val db = openMigrated()
        try {
            assertNull(
                noteTaskId(db, "n4"),
                "a note that links to nothing must not be given a task",
            )
        } finally {
            db.close()
        }
    }
}
