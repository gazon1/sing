package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncStateEntity
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
 * The v35 → v36 upgrade ([Migration35To36]).
 *
 * v36 adds one column, `sync_state.seed_completed`, and the DDL is validated by
 * Room against the exported schema when the database is opened. What the schema
 * cannot express is the thing that actually matters here: **the default**.
 *
 * A column added as `true` would read, for every row that exists at upgrade time,
 * as "this scope's data has already been uploaded". Nothing would say otherwise,
 * the planner would skip, and the documents a user created before signing in —
 * the exact data REQ-OS-013 is about — would never reach the server. The test
 * asserts the default is `false` and that existing rows survive the upgrade.
 */
@Tag("fast")
class Migration35To36Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration35to36-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Applies every entity DDL + `setupQueries` from the exported v35 schema. */
    private fun applyV35Fixture(connection: SQLiteConnection) {
        val sharedDir = File(System.getProperty("commonMain.root")!!)
            .parentFile.parentFile.parentFile
        val schemaJson = File(sharedDir, "schemas/com.singularity.todo.core.database.AppDatabase/35.json")
        assertTrue(schemaJson.exists(), "v35 schema not found at $schemaJson")

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
        connection.execSQL("PRAGMA user_version = 35")
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { applyV35Fixture(it) }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `an existing row upgrades and reads as not yet seeded`() = runTest {
        val db = openMigrated()
        try {
            db.syncStateDao().insertIfAbsent(
                SyncStateEntity(ownerId = "owner-1", profileId = "work", lastLsn = 7),
            )

            val migrated = db.syncStateDao().get("owner-1", "work")

            assertEquals(7L, migrated?.lastLsn, "the cursor must survive the upgrade")
            assertFalse(
                migrated!!.seedCompleted,
                "nothing has been uploaded on this row's behalf; defaulting to true would " +
                    "strand everything the user created before signing in",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a fresh row defaults to not seeded and can be marked`() = runTest {
        val db = openMigrated()
        try {
            val scope = SyncScope("owner-2", "home")
            db.syncStateDao().insertIfAbsent(SyncStateEntity(ownerId = scope.ownerId, profileId = scope.profileId))

            assertFalse(db.syncStateDao().get(scope.ownerId, scope.profileId)!!.seedCompleted)

            db.syncStateDao().setSeedCompleted(scope.ownerId, scope.profileId, true)

            assertTrue(db.syncStateDao().get(scope.ownerId, scope.profileId)!!.seedCompleted)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the seed marker is per scope`() = runTest {
        val db = openMigrated()
        try {
            val work = SyncScope("owner-3", "work")
            val home = SyncScope("owner-3", "home")
            db.syncStateDao().insertIfAbsent(SyncStateEntity(ownerId = work.ownerId, profileId = work.profileId))
            db.syncStateDao().insertIfAbsent(SyncStateEntity(ownerId = home.ownerId, profileId = home.profileId))

            db.syncStateDao().setSeedCompleted(work.ownerId, work.profileId, true)

            assertTrue(db.syncStateDao().get(work.ownerId, work.profileId)!!.seedCompleted)
            assertFalse(
                db.syncStateDao().get(home.ownerId, home.profileId)!!.seedCompleted,
                "one profile being seeded says nothing about the other",
            )
        } finally {
            db.close()
        }
    }
}
