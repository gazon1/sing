@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    /**
     * A flow taken from the shelf before the shelf changed reports the change.
     *
     * Two things are pinned here, and they are different. Re-collecting the flow reports
     * the row, which is what a fake that captured a snapshot in `watch()` fails — its
     * frozen list stays empty forever. And a collector started on the flow *taken above*
     * sees the row arrive, which is what makes this a flow rather than a re-read of
     * whatever the caller happens to ask next.
     */
    @Test
    fun `the shelf reports a patch shelved after the flow was taken`() = runTest {
        val shelf = deadLetterDao.watch()
        val count = deadLetterDao.watchCount()

        assertTrue(shelf.first().isEmpty(), "the shelf starts empty")
        assertEquals(0, count.first())

        val shelved = CompletableDeferred<Int>()
        val counted = CompletableDeferred<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shelf.collect { rows -> if (rows.isNotEmpty()) shelved.complete(rows.size) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            count.collect { total -> if (total > 0) counted.complete(total) }
        }

        deadLetterDao.insert(shelfRow("patch-a", OWNER_A))

        assertEquals(
            1,
            awaitEmission(shelved, "the shelved row"),
            "a flow taken before the row was shelved must report it without being re-collected",
        )
        assertEquals(
            1,
            awaitEmission(counted, "the shelf's count"),
            "the count taken before the row was shelved must report it too",
        )

        assertEquals(1, shelf.first().size, "and re-collecting reports it as well")
        assertEquals(1, count.first())
    }

    @Test
    fun `the shadow's owner erase takes every profile of that owner`() = runTest {
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_ONE, "entity-1"))
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_TWO, "entity-2"))
        shadowDao.upsert(shadowRow(OWNER_B, PROFILE_ONE, "entity-3"))

        assertEquals(2, shadowDao.countForOwner(OWNER_A))
        assertEquals(
            2,
            shadowDao.deleteForOwner(OWNER_A),
            "the erase takes both of the owner's profiles, and reports both rows",
        )

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

        val current =
            shadowDao.confirm(OWNER_A, PROFILE_ONE, TASK, "entity-1", "patch-new", """{"title":"confirmed"}""")
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

    /**
     * The shelf's reads are global, and that is a decision rather than a gap.
     *
     * Pinned here so that a change to either half — a read that stops being global,
     * or an erase that stops being owner-scoped — has to be a deliberate one. The
     * erase's half is asserted by the scenarios above; this is the read's.
     */
    @Test
    fun `the shelf is read whole, across every account`() = runTest {
        deadLetterDao.insert(shelfRow("patch-a", OWNER_A))
        deadLetterDao.insert(shelfRow("patch-b", OWNER_B))

        assertEquals(2, deadLetterDao.getAll().size, "the shelf hands over every account's shelved work")
        assertEquals(2, deadLetterDao.watchCount().first())
        assertEquals(2, deadLetterDao.watch().first().size)
    }

    /**
     * The patch-id delete is the shelf's patch-id-keyed operation.
     *
     * It names one row, so it takes one row and leaves the rest — the account's
     * other shelved work included.
     */
    @Test
    fun `the shelf's patch-id delete takes that patch and nothing else`() = runTest {
        deadLetterDao.insert(shelfRow("patch-a", OWNER_A))
        deadLetterDao.insert(shelfRow("patch-b", OWNER_B))

        assertEquals(1, deadLetterDao.delete("patch-a"))
        assertEquals(0, deadLetterDao.countForOwner(OWNER_A))
        assertEquals(1, deadLetterDao.countForOwner(OWNER_B), "another account's shelved work survives")
    }

    /**
     * The global drain empties the shelf for every account.
     *
     * The counterpart of the owner-scoped erase, and a different operation: the
     * first is a switch, this is a sign-out.
     */
    @Test
    fun `the shelf's global drain empties every account`() = runTest {
        deadLetterDao.insert(shelfRow("patch-a", OWNER_A))
        deadLetterDao.insert(shelfRow("patch-b", OWNER_B))

        deadLetterDao.clearAll()

        assertEquals(0, deadLetterDao.countForOwner(OWNER_A))
        assertEquals(0, deadLetterDao.countForOwner(OWNER_B))
        assertTrue(deadLetterDao.getAll().isEmpty())
    }

    /**
     * A shadow row belongs to one account's one profile.
     *
     * The composite key is the scope, so another account's read — and another
     * profile's read — must not find it. A fake that keyed its rows by entity id
     * alone would hand the row to both, and would let the rest of this file pass.
     */
    @Test
    fun `the shadow's row belongs to one account's one profile`() = runTest {
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_ONE, "entity-1"))

        assertNull(
            shadowDao.get(OWNER_B, PROFILE_ONE, TASK, "entity-1"),
            "another account must not see the row",
        )
        assertNull(
            shadowDao.get(OWNER_A, PROFILE_TWO, TASK, "entity-1"),
            "another profile of the same account must not see it either",
        )
    }

    /**
     * A write for one account does not land on another's row.
     *
     * The confirmed-state update is the one that would silently corrupt a row: it
     * reports 0 when there is no row to update, and a fake that reported 1 would
     * hide the guard behind the return value.
     */
    @Test
    fun `the shadow's confirmed update does not reach another account's row`() = runTest {
        shadowDao.upsert(shadowRow(OWNER_A, PROFILE_ONE, "entity-1", confirmedJson = """{"title":"old"}"""))

        val changed = shadowDao.updateConfirmed(OWNER_B, PROFILE_ONE, TASK, "entity-1", """{"title":"theirs"}""")

        assertEquals(0, changed, "there is no row of that account to update")
        val row = assertNotNull(shadowDao.get(OWNER_A, PROFILE_ONE, TASK, "entity-1"))
        assertEquals("""{"title":"old"}""", row.confirmedJson, "the owner's row is untouched")
    }

    /**
     * A promotion for one account does not promote another's row.
     *
     * The stale-response guard and the ownership guard are separate, and both have
     * to hold: a response carrying the right patch id but the wrong owner still
     * promotes nothing.
     */
    @Test
    fun `the shadow's promotion does not reach another account's row`() = runTest {
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

        val promoted =
            shadowDao.confirm(OWNER_B, PROFILE_ONE, TASK, "entity-1", "patch-new", """{"title":"theirs"}""")

        assertEquals(0, promoted, "another account's response promotes nothing")
        val row = assertNotNull(shadowDao.get(OWNER_A, PROFILE_ONE, TASK, "entity-1"))
        assertEquals("""{"title":"old"}""", row.confirmedJson)
        assertEquals("patch-new", row.inFlightPatchId, "the owner's queued patch keeps its marker")
    }

    /**
     * A release for one account does not clear another's marker.
     *
     * The release is the mirror of the promotion: it drops the in-flight marker so
     * the next edit re-diffs, and dropping another account's marker would lose the
     * fields that account's queued patch carried.
     */
    @Test
    fun `the shadow's release does not reach another account's row`() = runTest {
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

        val released = shadowDao.release(OWNER_B, PROFILE_ONE, TASK, "entity-1", "patch-new")

        assertEquals(0, released)
        val row = assertNotNull(shadowDao.get(OWNER_A, PROFILE_ONE, TASK, "entity-1"))
        assertEquals("patch-new", row.inFlightPatchId)
        assertEquals("""{"title":"new"}""", row.inFlightJson)
    }

    /**
     * Waits for [deferred] in real time, and says what never arrived.
     *
     * Real time rather than the test scheduler's virtual clock: the Room tiers' flows
     * emit from the database's own dispatcher, which the test scheduler does not drive,
     * and a virtual `withTimeout` would fire the moment the test's own queue went idle.
     */
    private suspend fun <T> awaitEmission(
        deferred: CompletableDeferred<T>,
        what: String,
    ): T =
        try {
            withContext(Dispatchers.Default) { withTimeout(FLOW_TIMEOUT_MS) { deferred.await() } }
        } catch (timeout: TimeoutCancellationException) {
            fail("$what never arrived on a flow taken before the change: $timeout")
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

        /** How long an open flow is given to report a change. Generous: it is a wait, not a deadline. */
        private const val FLOW_TIMEOUT_MS = 10_000L
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
