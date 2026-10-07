package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.sync.SyncStateEntity
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The v40 → v41 upgrade ([Migration40To41]).
 *
 * One column, added to a table that already holds a great deal: `sync_state` is keyed by
 * (owner, profile) and carries the LSN cursor, the device id and the auto-sync settings.
 * Adding a column to it is easy; the two things that are easy to get wrong are whether
 * existing rows survive it, and what the new column reads as when nobody set it.
 *
 * ## The properties that matter
 *
 * 1. **Every existing row keeps its data.** The cursor is the one field whose loss is
 *    visible — a device that loses it re-downloads its whole history — so a migration that
 *    rebuilt the table instead of altering it would be a regression in exchange for
 *    nothing.
 * 2. **The new column defaults to off.** A fresh upgrade must not claim the user asked for
 *    attachment sync. There is no binary transport yet, so `true` would be a stored promise
 *    nothing on the device can keep.
 * 3. **It is still per profile.** Two profiles on one device must not be able to read or
 *    write each other's answer; that is the whole reason the column lives here and not in
 *    the global `SyncPrefs`.
 */
@Tag("fast")
class Migration40To41Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration40to41-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sharedDir(): File =
        File(System.getProperty("commonMain.root")!!).parentFile.parentFile.parentFile

    /** Applies every entity DDL + `setupQueries` from the exported v40 schema. */
    private fun applyV40Fixture(connection: SQLiteConnection) {
        val schemaJson = File(sharedDir(), "schemas/com.singularity.todo.core.database.AppDatabase/40.json")
        assertTrue(schemaJson.exists(), "v40 schema not found at $schemaJson")

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
        connection.execSQL("PRAGMA user_version = 40")
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { applyV40Fixture(it) }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `an existing sync row keeps its cursor and settings`() = runTest {
        val db = openMigrated()
        try {
            val dao = db.syncStateDao()
            dao.insertIfAbsent(
                SyncStateEntity(
                    ownerId = "user-1",
                    profileId = "personal",
                    lastLsn = 4_242L,
                    deviceId = "device-a",
                    autoSyncEnabled = false,
                    scheduledIntervalMinutes = 90,
                    seedCompleted = true,
                ),
            )

            val after = dao.observe("user-1", "personal").first()

            assertEquals(4_242L, after?.lastLsn, "the LSN cursor must survive the upgrade")
            assertEquals("device-a", after?.deviceId)
            assertEquals(false, after?.autoSyncEnabled)
            assertEquals(90, after?.scheduledIntervalMinutes)
            assertEquals(true, after?.seedCompleted)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the new column reads as off for a row that predates it`() = runTest {
        val db = openMigrated()
        try {
            val dao = db.syncStateDao()
            dao.insertIfAbsent(SyncStateEntity(ownerId = "user-1", profileId = "personal"))

            assertFalse(
                dao.observe("user-1", "personal").first()?.attachmentsSyncEnabled ?: true,
                "an upgraded scope must not claim the user asked for attachment sync",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the preference round trips and stays scoped to one profile`() = runTest {
        val db = openMigrated()
        try {
            val dao = db.syncStateDao()
            dao.insertIfAbsent(SyncStateEntity(ownerId = "user-1", profileId = "personal"))
            dao.insertIfAbsent(SyncStateEntity(ownerId = "user-1", profileId = "work"))

            dao.setAttachmentsSyncEnabled("user-1", "personal", true)

            assertTrue(
                dao.observe("user-1", "personal").first()?.attachmentsSyncEnabled == true,
                "the write must be visible to the scope it was addressed to",
            )
            assertFalse(
                dao.observe("user-1", "work").first()?.attachmentsSyncEnabled == true,
                "a second profile must not inherit the first profile's answer",
            )
        } finally {
            db.close()
        }
    }
}
