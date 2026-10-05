package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.sync.SyncShadowEntity
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
 * The v36 → v37 upgrade ([Migration36To37]).
 *
 * This is the migration that nearly shipped without a test. `SyncColumns.server_version`
 * was declared on an embedded mixin, the exported schema moved to 37, and
 * `SCHEMA_VERSION` stayed at 36 — so Room's identity-hash check threw
 * `IllegalStateException` at the first query for every user with an existing
 * database, while all 1859 tests stayed green because each of them creates its
 * own database and a fresh one has no stored hash to disagree with. The check
 * that now prevents that is `scripts/check-room-schema-integrity.py`; this test
 * covers the half the gate cannot: that the migration *produces* the schema its
 * successor claims.
 *
 * ## What the schema cannot express
 *
 * The column is `server_version INTEGER NOT NULL DEFAULT 0`, and the DDL is
 * validated by Room when the database opens. What a DDL check cannot catch is a
 * default of any other value. `0` is the honest reading for every existing row:
 * the client parsed the server's version on every push and then dropped it, so
 * what it knows about pre-upgrade rows is *nothing*. A non-zero default would
 * invent a fact, and a wrong base version is worse than a missing one — the
 * server answers a stale or non-zero base with a refusal, and the change is
 * parked rather than applied.
 *
 * So the assertions below are about the value pre-existing rows read, not about
 * the column's presence: Room already proves the column exists.
 */
@Tag("fast")
class Migration36To37Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration36to37-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * Applies every entity DDL + `setupQueries` from the exported v36 schema, then
     * seeds one `sync_shadow` row.
     *
     * Seeding matters: an `ALTER TABLE … ADD COLUMN … DEFAULT 0` on an empty table
     * is indistinguishable from one on a table with rows, and the second is the case
     * that decides whether a user's data is real or invented.
     */
    private fun applyV36Fixture(connection: SQLiteConnection) {
        val sharedDir = File(System.getProperty("commonMain.root")!!)
            .parentFile.parentFile.parentFile
        val schemaJson = File(sharedDir, "schemas/com.singularity.todo.core.database.AppDatabase/36.json")
        assertTrue(schemaJson.exists(), "v36 schema not found at $schemaJson")

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

        // A row that existed before the upgrade, in the shape v36 knew.
        connection.execSQL(
            "INSERT INTO sync_shadow " +
                "(owner_id, profile_id, entity_type, entity_id, confirmed_json, in_flight_json, in_flight_patch_id) " +
                "VALUES ('owner-1', 'work', 'task', 'task-1', '{\"title\":\"before\"}', NULL, NULL)",
        )
        connection.execSQL("PRAGMA user_version = 36")
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { applyV36Fixture(it) }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `a row that predates the upgrade reads as version zero`() = runTest {
        val db = openMigrated()
        try {
            val migrated = db.syncShadowDao().get("owner-1", "work", "task", "task-1")

            assertTrue(
                migrated != null,
                "the row must survive the upgrade — the migration adds a column, it does not rebuild the table",
            )
            assertEquals(
                0L,
                migrated!!.serverVersion,
                "0 is the honest reading: the client parsed the server's version on every push and " +
                    "then dropped it, so what it knows about a pre-upgrade row is nothing. Any other " +
                    "default invents a fact, and a wrong base version is refused by the server, which " +
                    "parks the change instead of applying it",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the pre-existing row keeps its confirmed state`() = runTest {
        val db = openMigrated()
        try {
            val migrated = db.syncShadowDao().get("owner-1", "work", "task", "task-1")!!

            assertEquals(
                """{"title":"before"}""",
                migrated.confirmedJson,
                "the server's known state must survive the upgrade",
            )
            assertEquals(null, migrated.inFlightJson)
            assertEquals(null, migrated.inFlightPatchId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a row written after the upgrade defaults to zero`() = runTest {
        val db = openMigrated()
        try {
            db.syncShadowDao().upsert(
                SyncShadowEntity(
                    ownerId = "owner-2",
                    profileId = "home",
                    entityType = "note",
                    entityId = "note-1",
                    confirmedJson = """{"title":"after"}""",
                ),
            )

            val fresh = db.syncShadowDao().get("owner-2", "home", "note", "note-1")!!

            assertEquals(0L, fresh.serverVersion, "the Kotlin default and the column default must agree")
        } finally {
            db.close()
        }
    }

    @Test
    fun `confirming without a version leaves the stored one alone`() = runTest {
        val db = openMigrated()
        try {
            db.syncShadowDao().upsert(
                SyncShadowEntity(
                    ownerId = "owner-3",
                    profileId = "work",
                    entityType = "task",
                    entityId = "task-9",
                    confirmedJson = """{"n":1}""",
                ),
            )
            db.syncShadowDao().upsert(
                SyncShadowEntity(
                    ownerId = "owner-3",
                    profileId = "work",
                    entityType = "task",
                    entityId = "task-9",
                    confirmedJson = """{"n":1}""",
                    inFlightJson = """{"n":2}""",
                    inFlightPatchId = "patch-1",
                    serverVersion = 41L,
                ),
            )

            val promoted = db.syncShadowDao().confirm(
                ownerId = "owner-3",
                profileId = "work",
                entityType = "task",
                entityId = "task-9",
                patchId = "patch-1",
                json = """{"n":2}""",
                serverVersion = 42L,
            )
            assertEquals(1, promoted)

            val withVersion = db.syncShadowDao().get("owner-3", "work", "task", "task-9")!!
            assertEquals(42L, withVersion.serverVersion, "a response carrying a version must advance it")

            // A later response that carries no version must not reset it: a default of 0
            // here would put the next patch's base back to "the server has never seen this
            // row", which is exactly the condition the server answers `not_found` for.
            db.syncShadowDao().upsert(
                withVersion.copy(inFlightJson = """{"n":3}""", inFlightPatchId = "patch-2"),
            )
            db.syncShadowDao().confirm(
                ownerId = "owner-3",
                profileId = "work",
                entityType = "task",
                entityId = "task-9",
                patchId = "patch-2",
                json = """{"n":3}""",
                serverVersion = null,
            )

            val preserved = db.syncShadowDao().get("owner-3", "work", "task", "task-9")!!
            assertEquals(42L, preserved.serverVersion, "a response with no version must leave the stored one alone")
        } finally {
            db.close()
        }
    }
}
