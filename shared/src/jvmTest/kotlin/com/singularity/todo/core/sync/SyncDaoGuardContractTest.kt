@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ownership guards of the sync shadow and the sync dead-letter shelf.
 *
 * ## Why these guards are worth one contract
 *
 * The outbox is the reference: `getPending` filters on the owner *and* the profile,
 * `deleteForOwner` and `countForOwner` scope an erase to one account, `delete` and
 * `clearAll` are patch-id-keyed and global, and the table is documented as holding
 * several accounts' work at once (REQ-UA-006 keeps work across a sign-out).
 *
 * The shadow and the shelf sit on either side of it — the shadow records what the
 * server holds, the shelf records what gave up — and a guard that is missing on one
 * of them is a row one account can read, count or delete on another account's behalf.
 * A fake that quietly drops a condition is the other half of the same failure: it
 * lets a test assert the guard works while the fake never had it.
 *
 * So the scenarios run against all three implementations of the same two DAOs — the
 * Room ones, the shared fakes, and the sync-engine test fakes — and the contract is
 * that they answer alike.
 *
 * ## What is deliberately not asserted here
 *
 * That the shelf's *reads* are owner-scoped. They are not, and that is the outbox's
 * shape too: a transport queue is read globally and erased per owner. What the
 * contract pins is the pair — the global read and the owner-scoped erase — so a
 * change to either half has to be a deliberate one.
 */
abstract class SyncDaoGuardContractTest {
    /** The shelf under test. */
    protected abstract val deadLetterDao: SyncDeadLetterDao

    /** The shadow under test. */
    protected abstract val shadowDao: SyncShadowDao

    @Test
    fun `the shelf's owner erase takes only that owner's rows`() = runTest {
        deadLetterDao.insert(shelfRow("patch-a", OWNER_A))
        deadLetterDao.insert(shelfRow("patch-b", OWNER_B))

        val removed = deadLetterDao.deleteForOwner(OWNER_A)

        assertEquals(1, removed, "the erase reports how many rows it took")
        assertEquals(0, deadLetterDao.countForOwner(OWNER_A))
        assertEquals(1, deadLetterDao.countForOwner(OWNER_B), "another account's shelved work survives")
        assertEquals(1, deadLetterDao.getAll().size, "and the shelf holds only what is left")
    }

    @Test
    fun `the shelf's count is the owner's own`() = runTest {
        deadLetterDao.insert(shelfRow("patch-a", OWNER_A))
        deadLetterDao.insert(shelfRow("patch-b", OWNER_A))
        deadLetterDao.insert(shelfRow("patch-c", OWNER_B))

        assertEquals(2, deadLetterDao.countForOwner(OWNER_A))
        assertEquals(1, deadLetterDao.countForOwner(OWNER_B))
        assertEquals(0, deadLetterDao.countForOwner("owner-c"))
    }

    @Test
    fun `the shelf reports a patch shelved after the flow was taken`() = runTest {
        val shelf = deadLetterDao.watch()
        val count = deadLetterDao.watchCount()

        assertTrue(shelf.first().isEmpty(), "the shelf starts empty")
        assertEquals(0, count.first())

        deadLetterDao.insert(shelfRow("patch-a", OWNER_A))

        assertEquals(1, shelf.first().size, "a flow taken before the row was shelved must still report it")
        assertEquals(1, count.first())
    }

    @Test
    fun `the shadow's owner erase takes every profile of that owner`() = runTest {
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_ONE, "entity-1"))
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_TWO, "entity-2"))
        shadowDao.upsert(shadowRow(OWNER_B, PROFILE_ONE, "entity-3"))

        assertEquals(2, shadowDao.countForOwner(OWNER_A))
        assertEquals(1, shadowDao.deleteForOwner(OWNER_A))

        assertEquals(0, shadowDao.countForOwner(OWNER_A))
        assertNotNull(
            shadowDao.get(OWNER_B, PROFILE_ONE, TASK, "entity-3"),
            "another account's shadow survives",
        )
    }

    @Test
    fun `the shadow's scope clear leaves the owner's other profiles alone`() = runTest {
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_ONE, "entity-1"))
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_TWO, "entity-2"))

        shadowDao.clearScope(OWNER_A, PROFILE_ONE)

        assertNull(shadowDao.get(OWNER_A, PROFILE_ONE, TASK, "entity-1"))
        assertNotNull(
            shadowDao.get(OWNER_A, PROFILE_TWO, TASK, "entity-2"),
            "the owner's other profile keeps its shadow",
        )
    }

    @Test
    fun `the shadow promotes only the patch that still owns the marker`() = runTest {
        shadowDao.upsert(
            shadowRow(
                OWNER_A,
                PROFILE_ONE,
                "entity-1",
                confirmedJson = """{"title":"old"}""",
                inFlightJson = """{"title":"new"}""",
                inFlightPatchId = "patch-new",
                serverVersion = 7,
            ),
        )

        val stale = shadowDao.confirm(OWNER_A, PROFILE_ONE, TASK, "entity-1", "patch-old", """{"title":"stale"}""")
        assertEquals(0, stale, "a response for a superseded patch promotes nothing")

        val unchanged = assertNotNull(shadowDao.get(OWNER_A, PROFILE_ONE, TASK, "entity-1"))
        assertEquals(
            """{"title":"old"}""",
            unchanged.confirmedJson,
            "the confirmed state stays where the server left it",
        )
        assertEquals("patch-new", unchanged.inFlightPatchId, "the queued patch keeps its marker")

        val current = shadowDao.confirm(OWNER_A, PROFILE_ONE, TASK, "entity-1", "patch-new", """{"title":"confirmed"}""")
        assertEquals(1, current, "the patch that owns the marker is promoted")

        val promoted = assertNotNull(shadowDao.get(OWNER_A, PROFILE_ONE, TASK, "entity-1"))
        assertEquals("""{"title":"confirmed"}""", promoted.confirmedJson)
        assertNull(promoted.inFlightPatchId)
        assertNull(promoted.inFlightJson)
        assertEquals(
            7L,
            promoted.serverVersion,
            "a response with no version must not reset the one the client had",
        )
    }

    @Test
    fun `updating the confirmed state leaves the queued patch's marker alone`() = runTest {
        shadowDao.upsert(
            shadowRow(
                OWNER_A,
                PROFILE_ONE,
                "entity-1",
                confirmedJson = """{"title":"old"}""",
                inFlightJson = """{"title":"new"}""",
                inFlightPatchId = "patch-new",
            ),
        )

        val changed = shadowDao.updateConfirmed(OWNER_A, PROFILE_ONE, TASK, "entity-1", """{"title":"pulled"}""")

        assertEquals(1, changed)
        val row = assertNotNull(shadowDao.get(OWNER_A, PROFILE_ONE, TASK, "entity-1"))
        assertEquals("""{"title":"pulled"}""", row.confirmedJson)
        assertEquals("patch-new", row.inFlightPatchId, "the pull must not clobber the queued patch's base")
        assertEquals("""{"title":"new"}""", row.inFlightJson)
    }

    /** A shelved patch, as the shelf holds it. */
    private fun shelfRow(
        patchId: String,
        ownerId: String,
    ) = SyncDeadLetterEntity(
        patchId = patchId,
        ownerId = ownerId,
        entityId = "entity-$patchId",
        entityType = TASK,
        payload = """{"title":"patch"}""",
        createdAt = 1L,
        failedAt = 2L,
        attempts = 10,
    )

    /** A shadow row, as the shadow holds it. */
    private fun shadowRow(
        ownerId: String,
        profileId: String,
        entityId: String,
        confirmedJson: String = "{}",
        inFlightJson: String? = null,
        inFlightPatchId: String? = null,
        serverVersion: Long = 0,
    ) = SyncShadowEntity(
        ownerId = ownerId,
        profileId = profileId,
        entityType = TASK,
        entityId = entityId,
        confirmedJson = confirmedJson,
        inFlightJson = inFlightJson,
        inFlightPatchId = inFlightPatchId,
        serverVersion = serverVersion,
    )

    protected companion object {
        const val OWNER_A = "owner-a"
        const val OWNER_B = "owner-b"
        const val PROFILE_ONE = "profile-1"
        const val PROFILE_TWO = "profile-2"
        const val TASK = "task"
    }
}

/**
 * The guards as Room implements them.
 *
 * A real database file, because the guards live in the SQL: the WHERE clauses, the
 * COALESCE, and the promotion that only fires where the in-flight marker still
 * matches. A fake cannot be asked whether it agrees with the query it stands in for
 * — only the query can answer that.
 *
 * A temp file rather than an in-memory database: Room rejects the literal name
 * `":memory:"` in `databaseBuilder`, and the in-memory builder is a JVM-only API
 * unreachable from the commonMain factory.
 */
@Tag("slow")
class RoomSyncDaoGuardContractTest : SyncDaoGuardContractTest() {
    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-sync-guards-").toFile() }
    private val tempDir: File by tempDirLazy

    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, "sync-guards.db").absolutePath,
        )
    }
    private val db: AppDatabase by dbLazy

    override val deadLetterDao: SyncDeadLetterDao get() = db.syncDeadLetterDao()

    override val shadowDao: SyncShadowDao get() = db.syncShadowDao()

    @AfterTest
    fun tearDown() {
        if (dbLazy.isInitialized()) db.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }
}

/**
 * The guards as the shared fakes implement them.
 *
 * The fakes the feature tests run against, reached through the fake database rather
 * than through their classes: those classes are private to the file that owns them,
 * and the accessor is the contract the rest of the codebase sees.
 */
@Tag("fast")
class SharedFakeSyncDaoGuardContractTest : SyncDaoGuardContractTest() {
    private val database = FakeAppDatabase()

    override val deadLetterDao: SyncDeadLetterDao get() = database.syncDeadLetterDao()

    override val shadowDao: SyncShadowDao get() = database.syncShadowDao()
}

/**
 * The guards as the sync-engine test fakes implement them.
 *
 * A second copy of the same two DAOs, living next to the engine tests rather than
 * inside the fake database. It is the copy most sync tests use, so it is the one
 * whose drift would be felt first.
 */
@Tag("fast")
class JvmFakeSyncDaoGuardContractTest : SyncDaoGuardContractTest() {
    private val deadLetter = FakeSyncDeadLetterDao()
    private val shadow = FakeSyncShadowDao()

    override val deadLetterDao: SyncDeadLetterDao get() = deadLetter

    override val shadowDao: SyncShadowDao get() = shadow
}
