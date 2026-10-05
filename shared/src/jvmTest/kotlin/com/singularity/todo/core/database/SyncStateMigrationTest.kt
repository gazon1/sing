package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.core.sync.RoomSyncStateRepository
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncStateEntity
import com.singularity.todo.core.sync.SyncTrigger
import com.singularity.todo.core.sync.InMemorySyncPrefs
import kotlinx.coroutines.flow.first
import com.singularity.todo.test.helpers.MutableClock
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
import kotlin.time.Duration.Companion.minutes

/**
 * The v33→v34 upgrade ([Migration33To34]) and the semantics layered on top of it.
 *
 * v34 adds the `sync_state` table keyed by `(owner_id, profile_id)`. The DDL itself
 * is validated by Room against the exported schema when the database is opened, so
 * what is worth testing is the behaviour that the schema alone does not express:
 *
 * - an existing v33 database opens, and the new table is usable;
 * - a scope that has never synced reads as a real scope with a cursor of zero, not
 *   as a missing record;
 * - two scopes keep separate cursors, which is the whole reason the key is a pair;
 * - the legacy flat values are adopted **once**, and a scope that has deliberately
 *   reset its cursor does not get the old value written back.
 */
@Tag("fast")
class SyncStateMigrationTest {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration33to34-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Applies every entity DDL + `setupQueries` from the exported v33 schema. */
    private fun applyV33Fixture(connection: SQLiteConnection) {
        // commonMain.root = <shared>/src/commonMain/kotlin → schemas live at <shared>/schemas/…
        val sharedDir = File(System.getProperty("commonMain.root")!!)
            .parentFile.parentFile.parentFile
        val schemaJson = File(
            sharedDir,
            "schemas/com.singularity.todo.core.database.AppDatabase/33.json",
        )
        assertTrue(schemaJson.exists(), "v33 schema not found at $schemaJson")

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
        connection.execSQL("PRAGMA user_version = 33")
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

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { applyV33Fixture(it) }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `a v33 database upgrades to v34 with a usable sync_state table`() = runTest {
        val db = openMigrated()
        try {
            val scope = SyncScope("owner-1", "profile-a")
            db.syncStateDao().insertIfAbsent(
                SyncStateEntity(ownerId = scope.ownerId, profileId = scope.profileId, lastLsn = 42),
            )
            assertEquals(42L, db.syncStateDao().get(scope.ownerId, scope.profileId)?.lastLsn)
        } finally {
            db.close()
        }

        createSqlDriver().open(dbPath).use { connection ->
            assertTrue(
                "sync_state" in rawStrings(connection, "SELECT name FROM sqlite_master WHERE type = 'table'"),
                "the sync_state table must exist at v34",
            )
            val versions = mutableListOf<Int>()
            connection.prepare("PRAGMA user_version").use { statement ->
                while (statement.step()) versions += statement.getInt(0)
            }
            assertEquals(
                listOf(SCHEMA_VERSION),
                versions,
                "user_version must match the current schema version after upgrade",
            )
        }
    }

    @Test
    fun `two scopes keep separate cursors`() = runTest {
        val db = openMigrated()
        try {
            val repo = RoomSyncStateRepository(
                dao = db.syncStateDao(),
                legacyPrefs = InMemorySyncPrefs(MutableClock()),
                idGenerator = SequenceIdGenerator("device"),
            )
            val a = SyncScope("owner-1", "profile-a")
            val b = SyncScope("owner-1", "profile-b")

            repo.setLastLsn(a, 10)
            repo.setLastLsn(b, 99)

            // The failure this whole table exists to prevent: one scope reading the
            // other's position and resuming inside the wrong history. Nothing reports
            // that — a pull that applies a hundred events looks identical either way.
            assertEquals(10L, repo.get(a).lastLsn)
            assertEquals(99L, repo.get(b).lastLsn)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a scope that has never synced reads as a real scope with a zero cursor`() = runTest {
        val db = openMigrated()
        try {
            val repo = RoomSyncStateRepository(
                dao = db.syncStateDao(),
                legacyPrefs = InMemorySyncPrefs(MutableClock()),
                idGenerator = SequenceIdGenerator("device"),
            )
            val fresh = SyncScope("owner-new", "profile-new")

            val state = repo.get(fresh)

            // A null here would push a branch into every caller for the normal
            // first-run case, which is not an exceptional state.
            assertEquals(0L, state.lastLsn)
            assertNull(state.lastSuccessfulSyncAt)
            assertNotNull(state.deviceId, "a device id is generated so nothing syncs as an empty device")
        } finally {
            db.close()
        }
    }

    @Test
    fun `the legacy flat values are adopted once, and a reset cursor is not written back`() = runTest {
        val db = openMigrated()
        try {
            val legacy = InMemorySyncPrefs(MutableClock()).apply {
                setLastLsn(777)
                setAutoSyncEnabled(false)
                setScheduledInterval(45.minutes)
            }
            val repo = RoomSyncStateRepository(
                dao = db.syncStateDao(),
                legacyPrefs = legacy,
                idGenerator = SequenceIdGenerator("device"),
            )
            val scope = SyncScope("owner-legacy", "profile-1")

            // First use adopts the old values, so the device does not re-download
            // its whole history on upgrade.
            assertEquals(777L, repo.get(scope).lastLsn)
            assertEquals(false, repo.get(scope).autoSyncEnabled)
            assertEquals(45, repo.get(scope).scheduledInterval.inWholeMinutes.toInt())

            // A scope that has genuinely reset its cursor keeps it: adoption is
            // one-shot, not a rule that re-fires whenever the row looks empty.
            repo.setLastLsn(scope, 0)
            assertEquals(0L, repo.get(scope).lastLsn, "a deliberate reset must survive the legacy read")
        } finally {
            db.close()
        }
    }

    @Test
    fun `triggers round-trip through the stored csv`() = runTest {
        val db = openMigrated()
        try {
            val repo = RoomSyncStateRepository(
                dao = db.syncStateDao(),
                legacyPrefs = InMemorySyncPrefs(MutableClock()),
                idGenerator = SequenceIdGenerator("device"),
            )
            val scope = SyncScope("owner-1", "profile-a")

            assertEquals(
                SyncTrigger.entries.toSet(),
                repo.get(scope).enabledTriggers,
                "a fresh scope allows every trigger",
            )

            repo.setEnabledTriggers(scope, setOf(SyncTrigger.Created, SyncTrigger.NetworkConnected))

            assertEquals(
                setOf(SyncTrigger.Created, SyncTrigger.NetworkConnected),
                repo.observe(scope).first().enabledTriggers,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `an unrecognised trigger name costs one trigger, not the whole sync`() = runTest {
        val db = openMigrated()
        try {
            val scope = SyncScope("owner-1", "profile-a")
            db.syncStateDao().insertIfAbsent(
                SyncStateEntity(
                    ownerId = scope.ownerId,
                    profileId = scope.profileId,
                    // A name from a newer build, or a hand-edited row.
                    enabledTriggers = "Created,QuantumEntanglement,NetworkConnected",
                ),
            )
            val repo = RoomSyncStateRepository(
                dao = db.syncStateDao(),
                legacyPrefs = InMemorySyncPrefs(MutableClock()),
                idGenerator = SequenceIdGenerator("device"),
            )

            assertEquals(
                setOf(SyncTrigger.Created, SyncTrigger.NetworkConnected),
                repo.get(scope).enabledTriggers,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `clearAll wipes every scope, not just the current one`() = runTest {
        val db = openMigrated()
        try {
            val repo = RoomSyncStateRepository(
                dao = db.syncStateDao(),
                legacyPrefs = InMemorySyncPrefs(MutableClock()),
                idGenerator = SequenceIdGenerator("device"),
            )
            repo.setLastLsn(SyncScope("owner-1", "profile-a"), 10)
            repo.setLastLsn(SyncScope("owner-2", "profile-b"), 20)

            repo.clearAll()

            assertEquals(0L, db.syncStateDao().get("owner-1", "profile-a")?.lastLsn ?: 0L)
            assertNull(db.syncStateDao().get("owner-2", "profile-b"))
        } finally {
            db.close()
        }
    }
}
