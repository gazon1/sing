package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
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
 * The v38 → v39 upgrade ([Migration38To39]).
 *
 * One nullable column on one table. That is a small change, and small migrations are where
 * two specific mistakes hide:
 *
 * 1. **The backfill.** `cancelled_at` must be absent on every pre-existing row, because
 *    `NULL` is what "not cancelled" means. A backfill that filled in a timestamp would mark
 *    every live event as cancelled, and the push planner would then silently stop writing
 *    *everything* — a silent feature failure on upgrade.
 * 2. **Room's DDL check.** Room validates the schema when the database opens, so a migration
 *    whose resulting DDL does not match the export fails here rather than on a user's phone.
 *
 * The behavioural consequence of the column is covered in `GoogleSyncLifecycleTest`; this is
 * about the storage change alone.
 */
@Tag("fast")
class Migration38To39Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration38to39-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Applies every entity DDL + `setupQueries` from the exported v38 schema. */
    private fun applyV38Fixture(connection: SQLiteConnection) {
        val sharedDir = File(System.getProperty("commonMain.root")!!)
            .parentFile.parentFile.parentFile
        val schemaJson = File(sharedDir, "schemas/com.singularity.todo.core.database.AppDatabase/38.json")
        assertTrue(schemaJson.exists(), "v38 schema not found at $schemaJson")

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
        connection.execSQL("PRAGMA user_version = 38")
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { applyV38Fixture(it) }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    private fun shadow(eventId: String = "evt-1", taskId: String = "task-1") = GoogleEventShadowEntity(
        userId = "user-1",
        eventId = eventId,
        taskId = taskId,
        calendarId = "primary",
        etag = "etag-1",
        baseJson = """{"title":"Dentist"}""",
        remoteUpdatedAt = null,
        lastSyncedAt = 1_700_000_000_000,
    )

    @Test
    fun `a pre-existing shadow reads back as not cancelled`() = runTest {
        // The dangerous outcome of this migration: if old rows came out marked cancelled, the
        // push planner would skip every task on the calendar and the sync would go quiet with
        // no error anywhere.
        val db = openMigrated()
        db.googleEventShadowDao().upsert(shadow())

        val read = assertNotNull(db.googleEventShadowDao().get("user-1", "evt-1"))

        assertNull(read.cancelledAt, "a row written before the migration must not be marked cancelled")
    }

    @Test
    fun `a tombstone round-trips`() = runTest {
        val db = openMigrated()
        db.googleEventShadowDao().upsert(shadow().copy(cancelledAt = 1_700_000_500_000))

        val read = assertNotNull(db.googleEventShadowDao().get("user-1", "evt-1"))

        assertEquals(1_700_000_500_000, read.cancelledAt)
    }

    @Test
    fun `the rest of the row survives the upgrade`() = runTest {
        // The column is additive; losing the ancestor or the task link would turn a
        // cancellation into a much worse event — the planner would insert a duplicate.
        val db = openMigrated()
        db.googleEventShadowDao().upsert(shadow())

        val read = assertNotNull(db.googleEventShadowDao().get("user-1", "evt-1"))

        assertEquals("task-1", read.taskId)
        assertEquals("etag-1", read.etag)
        assertEquals("""{"title":"Dentist"}""", read.baseJson)
    }
}