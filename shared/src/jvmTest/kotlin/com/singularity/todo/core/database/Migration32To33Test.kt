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
 * Regression test for the v32→v33 upgrade path ([Migration32To33]).
 *
 * v33 adds `sync_outbox.next_attempt_at` and the `sync_dead_letter` table. The thing
 * worth testing is not the DDL — Room validates that against the exported schema when
 * the database is opened — but that **an existing outbox row is still pushable after
 * the upgrade**.
 *
 * A nullable `next_attempt_at` reads NULL, and the push query treats NULL as "now",
 * so rows that existed before the upgrade are not silently deferred by it. If someone
 * later makes the column NOT NULL with a default far in the future, or changes the
 * query to treat NULL as "never", this test fails with the reason rather than with a
 * sync that quietly stopped.
 *
 * The fixture is the exported Room schema `schemas/.../32.json` — the same source
 * `MigrationTestHelper` would use.
 */
@Tag("fast")
class Migration32To33Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration32to33-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Applies every entity DDL + `setupQueries` from the exported v32 schema. */
    private fun applyV32Fixture(connection: SQLiteConnection) {
        // commonMain.root = <shared>/src/commonMain/kotlin → schemas live at <shared>/schemas/…
        val sharedDir = File(System.getProperty("commonMain.root")!!)
            .parentFile.parentFile.parentFile
        val schemaJson = File(
            sharedDir,
            "schemas/com.singularity.todo.core.database.AppDatabase/32.json",
        )
        assertTrue(schemaJson.exists(), "v32 schema not found at $schemaJson")

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
        connection.execSQL("PRAGMA user_version = 32")
    }

    private fun seed(connection: SQLiteConnection) {
        // An outbox row as it existed at v32: no next_attempt_at column at all.
        connection.execSQL(
            "INSERT INTO sync_outbox (patch_id,entity_id,entity_type,payload,created_at,attempts) " +
                "VALUES ('p1','e1','task','{}',100,0)",
        )
        connection.execSQL(
            "INSERT INTO sync_outbox (patch_id,entity_id,entity_type,payload,created_at,attempts) " +
                "VALUES ('p2','e2','task','{}',200,3)",
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
    fun `v32 database upgrades to v33 keeping outbox rows pushable`() = runTest {
        createSqlDriver().open(dbPath).use { connection ->
            applyV32Fixture(connection)
            seed(connection)
        }

        // First DAO access runs the 32→33 migration and validates against 33.json.
        val db = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try {
            val pending = db.syncOutboxDao().getPending(now = Long.MAX_VALUE)
            assertEquals(
                listOf("p1", "p2"),
                pending.map { it.patchId },
                "every pre-upgrade patch must still be eligible for a push",
            )
            assertNull(
                pending.first().nextAttemptAt,
                "a row that predates v33 must not be deferred by the upgrade",
            )
            // The attempt count survives — that is what the backoff decision reads.
            assertEquals(3, db.syncOutboxDao().attemptsOf("p2"))
        } finally {
            db.close()
        }

        createSqlDriver().open(dbPath).use { connection ->
            val columns = rawStrings(connection, "PRAGMA table_info(sync_outbox)", column = 1)
            assertTrue("next_attempt_at" in columns, "next_attempt_at must exist at v33, got $columns")

            val versions = mutableListOf<Int>()
            connection.prepare("PRAGMA user_version").use { statement ->
                while (statement.step()) versions += statement.getInt(0)
            }
            // SCHEMA_VERSION, not 33: Room applies the whole chain from the fixture
            // version, so the stamp reflects the newest schema, not the migration
            // under test. Reading the constant rather than a literal means this test
            // does not break on every later migration.
            assertEquals(
                listOf(SCHEMA_VERSION),
                versions,
                "user_version must be the current schema version after upgrade",
            )

            val tables = rawStrings(
                connection,
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            )
            assertTrue("sync_dead_letter" in tables, "the dead letter table must exist at v33")
        }
    }

    @Test
    fun `a backoff window set after the upgrade defers a patch`() = runTest {
        createSqlDriver().open(dbPath).use { connection ->
            applyV32Fixture(connection)
            seed(connection)
        }

        val db = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try {
            val future = System.currentTimeMillis() + 60_000
            db.syncOutboxDao().markFailed(id = "p1", error = "server busy", nextAttemptAt = future)

            val now = System.currentTimeMillis()
            assertTrue(
                db.syncOutboxDao().getPending(now).none { it.patchId == "p1" },
                "a deferred patch must not be returned as pending",
            )
            assertEquals(
                listOf("p1", "p2"),
                db.syncOutboxDao().getPending(future + 1).map { it.patchId },
                "the patch returns once the window has passed",
            )
        } finally {
            db.close()
        }
    }
}
