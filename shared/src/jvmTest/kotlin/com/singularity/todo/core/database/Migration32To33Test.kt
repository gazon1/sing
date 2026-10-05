package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.sync.SyncOutboxEntity
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
        // An outbox row as it existed at v32: no next_attempt_at and no owner_id.
        connection.execSQL(
            "INSERT INTO sync_outbox (patch_id,entity_id,entity_type,payload,created_at,attempts) " +
                "VALUES ('p1','e1','task','{}',100,0)",
        )
        connection.execSQL(
            "INSERT INTO sync_outbox (patch_id,entity_id,entity_type,payload,created_at,attempts) " +
                "VALUES ('p2','e2','task','{}',200,3)",
        )
    }

    /**
     * The same two rows, written through the current DAO once the upgrade has run.
     *
     * Both tests below are about v33's `next_attempt_at` semantics, not about what
     * happens to rows written before the upgrade. They used to seed at v32 and read
     * them back after, which asserted both of those things at once — and the second
     * half stopped being true when Migration37To38 began clearing the queue, since a
     * pre-upgrade row cannot be attributed to an account and is deliberately dropped
     * rather than guessed at (#209). Seeding afterwards keeps the tests testing their
     * own subject.
     */
    private suspend fun seedAfterUpgrade(db: AppDatabase, ownerId: String) {
        db.syncOutboxDao().insert(
            SyncOutboxEntity(
                patchId = "new-p1",
                ownerId = ownerId,
                entityId = "e1",
                entityType = "task",
                payload = "{}",
                createdAt = 100L,
            ),
        )
        db.syncOutboxDao().insert(
            SyncOutboxEntity(
                patchId = "new-p2",
                ownerId = ownerId,
                entityId = "e2",
                entityType = "task",
                payload = "{}",
                createdAt = 200L,
                attempts = 3,
            ),
        )
    }

    private companion object {
        /** The account these rows belong to. Reads the scoping as deliberate. */
        const val OWNER = "owner-migration-test"
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
    fun `v32 database upgrades to v33 with an outbox that can be pushed from`() = runTest {
        createSqlDriver().open(dbPath).use { connection ->
            applyV32Fixture(connection)
            seed(connection)
        }

        // First DAO access runs the whole chain from the fixture version.
        val db = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try {
            seedAfterUpgrade(db, OWNER)

            val pending = db.syncOutboxDao().getPending(now = Long.MAX_VALUE, ownerId = OWNER)
            assertEquals(
                listOf("new-p1", "new-p2"),
                pending.map { it.patchId },
                "a freshly queued patch must be eligible for a push",
            )
            assertNull(
                pending.first().nextAttemptAt,
                "a row with no backoff must not come back deferred",
            )
            // The attempt count is what the backoff decision reads, so it has to persist.
            assertEquals(3, db.syncOutboxDao().attemptsOf("new-p2"))
        } finally {
            db.close()
        }

        // The rows the fixture wrote are gone, and that is the decision rather than an
        // accident: v37→v38 gave the queue an owner and clears rows that cannot be
        // attributed to one, because guessing would file one account's unsent work under
        // another. Asserted here so the loss is visible in the test that used to promise
        // the opposite, and so nobody "fixes" the migration by attributing them.
        createSqlDriver().open(dbPath).use { connection ->
            assertEquals(
                emptyList(),
                rawStrings(
                    connection,
                    "SELECT patch_id FROM sync_outbox WHERE patch_id IN ('p1','p2')",
                ),
                "pre-upgrade queue rows must be cleared, not attributed to a guess",
            )
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
            seedAfterUpgrade(db, OWNER)
            val future = System.currentTimeMillis() + 60_000
            db.syncOutboxDao().markFailed(id = "new-p1", error = "server busy", nextAttemptAt = future)

            val now = System.currentTimeMillis()
            assertTrue(
                db.syncOutboxDao().getPending(now, OWNER).none { it.patchId == "new-p1" },
                "a deferred patch must not be returned as pending",
            )
            assertEquals(
                listOf("new-p1", "new-p2"),
                db.syncOutboxDao().getPending(future + 1, OWNER).map { it.patchId },
                "the patch returns once the window has passed",
            )
        } finally {
            db.close()
        }
    }
}
