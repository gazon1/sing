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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for the v31→v32 upgrade path ([Migration31To32]).
 *
 * Historical bug (fixed 2026-10-04): the KSP-generated auto-migration dropped
 * `ai_proposal.task_id` first and backfilled `target_id` from it afterwards in
 * `onPostMigrate`, failing with `no such column: task_id` and rolling back —
 * every pre-v32 database was stuck forever. room3's `AutoMigrationSpec` has no
 * `onPreMigrate` hook, so 31→32 is now a MANUAL migration that backfills first.
 *
 * The fixture is the exported Room schema `schemas/.../31.json` (same source
 * `MigrationTestHelper` would use), seeded with the three row shapes that matter:
 * backfillable (`target_id NULL`, `task_id set`), already-migrated, and
 * targetless (neither — must be deleted so `target_id NOT NULL` holds).
 *
 * Room itself validates the resulting schema against `32.json` on open, so this
 * test also proves the hand-copied rebuild SQL matches the v32 entity schema.
 */
@Tag("fast")
class Migration31To32Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration31to32-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Applies every entity DDL + `setupQueries` from the exported v31 schema. */
    private fun applyV31Fixture(connection: SQLiteConnection) {
        // commonMain.root = <shared>/src/commonMain/kotlin → schemas live at <shared>/schemas/…
        val sharedDir = File(System.getProperty("commonMain.root")!!)
            .parentFile.parentFile.parentFile
        val schemaJson = File(
            sharedDir,
            "schemas/com.singularity.todo.core.database.AppDatabase/31.json",
        )
        assertTrue(schemaJson.exists(), "v31 schema not found at $schemaJson")

        val database = Json.parseToJsonElement(schemaJson.readText()).jsonObject["database"]!!.jsonObject
        for (entity in database["entities"]!!.jsonArray) {
            val obj = entity.jsonObject
            val tableName = obj["tableName"]!!.jsonPrimitive.content
            connection.execSQL(
                obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName),
            )
            for (index in obj["indices"]?.jsonArray.orEmpty()) {
                connection.execSQL(
                    index.jsonObject["createSql"]!!.jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName),
                )
            }
        }
        for (query in database["setupQueries"]!!.jsonArray) {
            connection.execSQL(query.jsonPrimitive.content)
        }
        connection.execSQL("PRAGMA user_version = 31")
    }

    private fun seed(connection: SQLiteConnection) {
        // p1: target_id is NULL but task_id is set → must be backfilled.
        connection.execSQL(
            "INSERT INTO ai_proposal (id,user_id,source,status,target_kind,target_id," +
                "created_at,updated_at,server_version,sync_status) " +
                "VALUES ('p1','u1','A','PENDING','TASK',NULL,1,1,1,'PENDING')",
        )
        connection.execSQL("UPDATE ai_proposal SET task_id = 't-1' WHERE id = 'p1'")
        // p2: already has target_id → untouched.
        connection.execSQL(
            "INSERT INTO ai_proposal (id,user_id,source,status,target_kind,target_id," +
                "created_at,updated_at,server_version,sync_status) " +
                "VALUES ('p2','u1','A','PENDING','TASK','y-1',1,1,1,'PENDING')",
        )
        // p3: neither target_id nor task_id → cannot satisfy NOT NULL → deleted.
        connection.execSQL(
            "INSERT INTO ai_proposal (id,user_id,source,status,target_kind,target_id," +
                "created_at,updated_at,server_version,sync_status) " +
                "VALUES ('p3','u1','A','PENDING','TASK',NULL,1,1,1,'PENDING')",
        )
        // Note without a task reference — survives the notes rebuild.
        connection.execSQL(
            "INSERT INTO notes (id,user_id,title,is_folder,created_at,updated_at," +
                "server_version,sync_status) " +
                "VALUES ('n1','u1','hello',0,1,1,1,'PENDING')",
        )
    }

    private fun rawStrings(connection: SQLiteConnection, sql: String, column: Int = 0): List<String> {
        val statement = connection.prepare(sql)
        val out = mutableListOf<String>()
        try {
            while (statement.step()) out += statement.getText(column)
        } finally {
            statement.close()
        }
        return out
    }

    @Test
    fun `v31 database upgrades to v32 with task_id backfilled before the drop`() = runTest {
        val fixtureDriver = createSqlDriver()
        fixtureDriver.open(dbPath).use { connection ->
            applyV31Fixture(connection)
            seed(connection)
        }

        // First DAO access runs the 31→32 migration and validates against 32.json.
        val db = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try {
            val p1 = db.proposalDao().getProposal("p1")
            assertNotNull(p1, "p1 must survive the migration")
            assertEquals("t-1", p1.targetId, "target_id must be backfilled from task_id")
            assertEquals("y-1", db.proposalDao().getProposal("p2")?.targetId, "p2 untouched")
            assertNull(
                db.proposalDao().getProposal("p3"),
                "targetless row must be deleted — target_id is NOT NULL at v32",
            )
        } finally {
            db.close()
        }

        createSqlDriver().open(dbPath).use { connection ->
            val columns = rawStrings(connection, "PRAGMA table_info(ai_proposal)", column = 1)
            assertTrue("task_id" !in columns, "task_id must be dropped, got $columns")
            assertTrue("target_id" in columns)

            val versions = mutableListOf<Int>()
            connection.prepare("PRAGMA user_version").use { statement ->
                while (statement.step()) versions += statement.getInt(0)
            }
            assertEquals(listOf(32), versions, "user_version must be 32 after upgrade")

            val noteTitles = rawStrings(connection, "SELECT title FROM notes")
            assertEquals(listOf("hello"), noteTitles, "notes rows must survive the rebuild")

            val fkTables = rawStrings(connection, "PRAGMA foreign_key_list(notes)", column = 2)
            assertTrue("tasks" in fkTables, "notes.task_id FK must exist at v32, got $fkTables")
        }
    }
}
