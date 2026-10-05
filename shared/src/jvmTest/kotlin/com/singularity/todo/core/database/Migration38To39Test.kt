package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v38 → v39 upgrade ([Migration38To39]).
 *
 * v38 is a pure addition: three new tables, no existing table altered. Room validates the
 * DDL against the exported schema when the database opens, so what the schema *cannot*
 * express is what this test is for — and for an additive change that is the thing most
 * likely to be wrong in a way nobody notices.
 *
 * ## The properties that matter
 *
 * 1. **Existing data is untouched.** The device-calendar mapping keeps its `INTEGER` event
 *    id; a retyped column would have been the obvious way to add Google, and it is the one
 *    change that can lose data.
 * 2. **The new tables are keyed by user.** A Google event id is scoped to one account, so
 *    two profiles on one device can hold the same one. If the key forgot the user, one
 *    profile's sync would read and delete the other's rows — a bug with no error message,
 *    just a calendar that quietly empties.
 * 3. **`cancelled_at` arrives unbackfilled.** It was added in the same migration as the
 *    table it lives on, so this covers the tombstone column too. `NULL` has to mean "not
 *    cancelled"; a backfill would mark every live event cancelled and the push planner would
 *    silently stop writing *everything*.
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
    private fun applyV37Fixture(connection: SQLiteConnection) {
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
        createSqlDriver().open(dbPath).use { applyV37Fixture(it) }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `an existing device-calendar mapping survives the upgrade unchanged`() = runTest {
        val db = openMigrated()
        try {
            // A row in the shape the old build wrote: an INTEGER event id, no Google anywhere.
            db.calendarSyncTaskMapDao().upsert(
                CalendarSyncTaskMapEntity(
                    taskId = "task-1",
                    userId = "user-1",
                    calendarId = "7",
                    eventId = 42L,
                    syncedAt = 1_700_000_000_000L,
                    checksum = 7,
                ),
            )

            val after = db.calendarSyncTaskMapDao().getByTaskId("task-1", "user-1")

            assertEquals(42L, after?.eventId, "the existing integer event id must be preserved verbatim")
            assertEquals("7", after?.calendarId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the three new tables exist and accept a round trip`() = runTest {
        val db = openMigrated()
        try {
            db.calendarSyncStateDao().upsert(
                CalendarSyncStateEntity(
                    userId = "user-1",
                    provider = "google",
                    calendarId = "primary",
                    nextSyncToken = "token-1",
                ),
            )
            assertEquals(
                "token-1",
                db.calendarSyncStateDao().get("user-1", "google", "primary")?.nextSyncToken,
            )

            db.googleEventShadowDao().upsert(
                GoogleEventShadowEntity(
                    userId = "user-1",
                    eventId = "evt-1",
                    taskId = "task-1",
                    calendarId = "primary",
                    etag = "\"abc\"",
                    baseJson = """{"title":"Standup"}""",
                    lastSyncedAt = 1_700_000_000_000L,
                ),
            )
            assertEquals("task-1", db.googleEventShadowDao().get("user-1", "evt-1")?.taskId)

            db.calendarImportEventDao().upsert(
                CalendarImportEventEntity(
                    userId = "user-1",
                    eventId = "evt-foreign",
                    calendarId = "primary",
                    title = "Dentist",
                    startsAt = 1_700_000_000_000L,
                    endsAt = 1_700_003_600_000L,
                    lastSyncedAt = 1_700_000_000_000L,
                    importedAt = 1_700_000_000_000L,
                ),
            )
            assertEquals("Dentist", db.calendarImportEventDao().get("user-1", "evt-foreign")?.title)
        } finally {
            db.close()
        }
    }

    /**
     * The bug the composite key exists to prevent. Two profiles holding the *same* Google
     * event id must not be able to read, overwrite, or delete each other's rows — and
     * there is no error when they do, only a calendar that quietly loses events.
     */
    @Test
    fun `two profiles sharing a Google event id stay isolated`() = runTest {
        val db = openMigrated()
        try {
            db.googleEventShadowDao().upsert(
                GoogleEventShadowEntity(
                    userId = "work",
                    eventId = "shared-evt",
                    taskId = "task-work",
                    calendarId = "primary",
                    etag = null,
                    baseJson = """{"title":"Work"}""",
                    lastSyncedAt = 1L,
                ),
            )
            db.googleEventShadowDao().upsert(
                GoogleEventShadowEntity(
                    userId = "home",
                    eventId = "shared-evt",
                    taskId = "task-home",
                    calendarId = "primary",
                    etag = null,
                    baseJson = """{"title":"Home"}""",
                    lastSyncedAt = 2L,
                ),
            )

            assertEquals("task-work", db.googleEventShadowDao().get("work", "shared-evt")?.taskId)
            assertEquals("task-home", db.googleEventShadowDao().get("home", "shared-evt")?.taskId)

            // A cleanup for one profile must not touch the other, even though the event id
            // is absent from the live set.
            db.googleEventShadowDao().deleteNotIn("work", liveEventIds = emptyList())

            assertNull(
                db.googleEventShadowDao().get("work", "shared-evt"),
                "the row this profile does not list should be gone",
            )
            assertEquals(
                "task-home",
                db.googleEventShadowDao().get("home", "shared-evt")?.taskId,
                "the other profile's row must survive a cleanup it was never part of",
            )
        } finally {
            db.close()
        }
    }

    /**
     * One calendar's dead cursor must not reset the others. Google invalidates a
     * `nextSyncToken` per calendar, and a 410 on one is a normal, small event — resetting
     * the account would turn it into a full re-download.
     */
    @Test
    fun `invalidating a cursor affects only that calendar`() = runTest {
        val db = openMigrated()
        try {
            listOf("primary", "work", "holidays").forEach { cal ->
                db.calendarSyncStateDao().upsert(
                    CalendarSyncStateEntity(
                        userId = "user-1",
                        provider = "google",
                        calendarId = cal,
                        nextSyncToken = "token-$cal",
                    ),
                )
            }

            db.calendarSyncStateDao().invalidateToken("user-1", "google", "work")

            assertNull(
                db.calendarSyncStateDao().get("user-1", "google", "work")?.nextSyncToken,
                "the invalidated calendar must need a full sync",
            )
            assertEquals(
                "token-primary",
                db.calendarSyncStateDao().get("user-1", "google", "primary")?.nextSyncToken,
                "an unrelated calendar's cursor must survive",
            )
            assertEquals(
                "token-holidays",
                db.calendarSyncStateDao().get("user-1", "google", "holidays")?.nextSyncToken,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `an import event can be adopted into a task exactly once`() = runTest {
        val db = openMigrated()
        try {
            db.calendarImportEventDao().upsert(
                CalendarImportEventEntity(
                    userId = "user-1",
                    eventId = "evt-foreign",
                    calendarId = "primary",
                    title = "Dentist",
                    startsAt = 1_700_000_000_000L,
                    endsAt = 1_700_003_600_000L,
                    lastSyncedAt = 1L,
                    importedAt = 1L,
                ),
            )

            assertEquals(1, db.calendarImportEventDao().observeUnconverted("user-1").first().size)

            db.calendarImportEventDao().linkTask("user-1", "evt-foreign", "task-new")

            assertEquals(
                0,
                db.calendarImportEventDao().observeUnconverted("user-1").first().size,
                "an adopted event must stop being offered",
            )
            assertEquals("task-new", db.calendarImportEventDao().get("user-1", "evt-foreign")?.taskId)
        } finally {
            db.close()
        }
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
        // The dangerous outcome of this column: if old rows came out marked cancelled, the
        // push planner would skip every task on the calendar and the sync would go quiet with
        // no error anywhere.
        val db = openMigrated()
        try {
            db.googleEventShadowDao().upsert(shadow())

            val read = assertNotNull(db.googleEventShadowDao().get("user-1", "evt-1"))

            assertNull(read.cancelledAt, "a row written before the migration must not be marked cancelled")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a tombstone round-trips`() = runTest {
        val db = openMigrated()
        try {
            db.googleEventShadowDao().upsert(shadow().copy(cancelledAt = 1_700_000_500_000))

            val read = assertNotNull(db.googleEventShadowDao().get("user-1", "evt-1"))

            assertEquals(1_700_000_500_000, read.cancelledAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the rest of the shadow row survives the upgrade`() = runTest {
        // The column is additive; losing the ancestor or the task link would turn a
        // cancellation into a much worse event — the planner would insert a duplicate.
        val db = openMigrated()
        try {
            db.googleEventShadowDao().upsert(shadow())

            val read = assertNotNull(db.googleEventShadowDao().get("user-1", "evt-1"))

            assertEquals("task-1", read.taskId)
            assertEquals("etag-1", read.etag)
            assertEquals("""{"title":"Dentist"}""", read.baseJson)
        } finally {
            db.close()
        }
    }
}
