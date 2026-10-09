@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import kotlinx.coroutines.test.TestScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A local read that fails is a different problem from a server that refuses, and the
 * engine has to say which.
 *
 * ## The defect
 *
 * `push()` read the outbox on the one line between setting the status to `Pushing` and
 * entering its `try`. A database that could not be read therefore:
 *
 * - left the status at `Pushing` forever. `isRunning()` is what the sync screen checks
 *   before starting another cycle, so a running state that outlives its cycle refuses
 *   every later attempt — one unlucky storage failure and manual sync is dead until
 *   the process restarts;
 * - escaped `push()` and `syncOnce()` into the coordinator's catch-all, which filled
 *   *both* phase slots with the same error, claiming a push and a pull had failed when
 *   neither had run;
 * - arrived as `AppError.Unknown` carrying the driver's own message, which is
 *   indistinguishable from the `AppError.Network` the transport produces for a server
 *   that did not take the batch. A user told the wrong one retries the wrong thing.
 *
 * Each of the three is a separate assertion below, because they fail separately.
 */
@Tag("fast")
class SyncEngineStorageFailureTest {
    /**
     * The engine reads this for every timestamp, and the tests below assert on two of
     * them. Movable rather than fixed so a backoff deferral can be observed expiring
     * without the test waiting in real time.
     */
    private val clock = MutableClock()

    private val log = Logger.withTag("SyncEngineStorageFailureTest")
    private val json = StableJson

    /** The outbox with [getPending] refusing, which is what a closed database does. */
    private class UnreadableOutbox(private val delegate: FakeSyncOutboxDao, private val failure: Throwable) :
        SyncOutboxDao by delegate {
        override suspend fun getPending(now: Long, ownerId: String): List<SyncOutboxEntity> =
            throw failure
    }

    /**
     * The outbox with a switch, so a test can break storage and then repair it.
     *
     * The recovery is the point: the engine does not consult its own status before
     * pushing, so a stranded `Pushing` is not what stops the *next* cycle — it is what
     * stops the next cycle from being *requested*, one layer up, because a status that
     * reads as running is a refusal. So the assertion that matters after a failure is
     * that the status is terminal and the work is still there to do.
     */
    private class SwitchableOutbox(private val delegate: FakeSyncOutboxDao) : SyncOutboxDao by delegate {
        var readable: Boolean = true
        override suspend fun getPending(now: Long, ownerId: String): List<SyncOutboxEntity> =
            if (readable) {
                delegate.getPending(now, ownerId)
            } else {
                throw IllegalStateException("database is closed")
            }
    }

    private fun engine(
        scope: TestScope,
        api: FakeSyncApiClient = FakeSyncApiClient(),
        outbox: SyncOutboxDao,
        state: SyncStateRepository = FakeSyncStateRepository(),
    ): SyncEngine {
        val shadow = FakeSyncShadowDao()
        return SyncEngine(
            log = log,
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh"),
            ),
            outboxDao = outbox,
            deadLetterDao = FakeSyncDeadLetterDao(),
            idGenerator = SequentialIdGenerator(),
            stateRepository = state,
            shadowDao = shadow,
            patchBuilder = fakeSyncPatchBuilder(shadow),
            writerProvider = { fakeSyncDocumentWriter() },
            scopeProvider = FakeSyncScopeProvider(SyncScope("owner-storage", "profile-1")),
            scheduler = FakeSyncWorkScheduler(),
            clock = clock,
            scope = testScope(scope.backgroundScope),
            crashReporter = NoOpCrashReportingPort(),
        )
    }

    private suspend fun FakeSyncOutboxDao.seedOne(): FakeSyncOutboxDao = apply {
        insert(
            SyncOutboxEntity(
                patchId = "patch-storage-1",
                ownerId = "owner-storage",
                entityId = "task-1",
                entityType = "task",
                payload = json.encodeToString(
                    DeltaPatch(
                        patchId = "patch-storage-1",
                        entityId = "task-1",
                        entityType = DocType.Task,
                        baseVersion = 0,
                    ),
                ),
                createdAt = 1L,
            ),
        )
    }

    // ─── A local read failure ────────────────────────────────────────────────

    @Test
    fun `a failure reading the outbox is reported as a persistence error`() = runTest {
        val outbox = FakeSyncOutboxDao().seedOne()
        val sut = engine(this, outbox = UnreadableOutbox(outbox, IllegalStateException("database is closed")))

        val result = sut.push()

        val error = assertIs<AppError.Persistence>(result.exceptionOrNull())
        assertEquals("Could not read the pending changes", error.message)
    }

    /** The driver's text is kept for the crash report even though it is not shown. */
    @Test
    fun `the driver's own failure survives as the cause`() = runTest {
        val thrown = IllegalStateException("database is closed")
        val outbox = FakeSyncOutboxDao().seedOne()
        val sut = engine(this, outbox = UnreadableOutbox(outbox, thrown))

        val error = assertIs<AppError>(sut.push().exceptionOrNull())

        assertTrue(error.original() === thrown, "the original throwable was replaced")
    }

    /**
     * The wedge. A status left at `Pushing` reads as "a cycle is running", and every
     * later request declines because of it.
     */
    @Test
    fun `the status does not stay running after a storage failure`() = runTest {
        val outbox = FakeSyncOutboxDao().seedOne()
        val sut = engine(this, outbox = UnreadableOutbox(outbox, IllegalStateException("closed")))

        sut.push()

        val status = sut.status.value
        assertTrue(!status.isRunning(), "status stranded at $status; every later sync is refused")
        assertIs<SyncEngineStatus.Failure>(status)
    }

    /**
     * The work survives a storage failure, and the next cycle can run once storage is
     * back. Before the fix the patch was still in the outbox but the status read as
     * "a cycle is running", so nothing would have asked for the next one.
     */
    @Test
    fun `the queued work survives a storage failure and pushes once storage returns`() = runTest {
        val backing = FakeSyncOutboxDao().seedOne()
        val outbox = SwitchableOutbox(backing)
        val sut = engine(this, outbox = outbox)

        outbox.readable = false
        val failed = sut.push()
        assertIs<AppError.Persistence>(failed.exceptionOrNull())

        assertTrue(!sut.status.value.isRunning(), "a caller would refuse to request the next cycle")
        assertEquals(1, backing.rows.size, "a read failure must not drop the patch")

        outbox.readable = true
        // The server answers for the patch; the default fake answers for nothing, which
        // would leave the row in the outbox and make "was it kept?" indistinguishable
        // from "did the server take it?".
        val api = FakeSyncApiClient(
            pushResponse = BatchPushResponse(
                listOf(PatchResult(patchId = "patch-storage-1", ok = true)),
            ),
        )
        val recovering = engine(this, api = api, outbox = outbox)
        val recovered = recovering.push()

        assertTrue(recovered.isSuccess, "the second push did not run: ${recovered.exceptionOrNull()}")
        assertTrue(backing.rows.isEmpty(), "the patch was neither pushed nor kept")
        assertTrue(recovering.status.value.isSuccess())
    }

    // ─── A row that reads fine and will not decode ────────────────────────────

    /**
     * An outbox row whose payload is not a patch.
     *
     * The read succeeds and the damage is in the bytes, which is what made this one
     * different from the read failure above: `getPending` returned the row, so the
     * engine was past every check it had, and the decode was the next unguarded step.
     */
    private suspend fun FakeSyncOutboxDao.seedUndecodable(): FakeSyncOutboxDao = apply {
        insert(
            SyncOutboxEntity(
                patchId = "patch-corrupt-1",
                ownerId = "owner-storage",
                entityId = "task-2",
                entityType = "task",
                payload = "{ this is not a DeltaPatch",
                createdAt = 1L,
            ),
        )
    }

    /**
     * The wedge this fixes, and the negative control for it.
     *
     * The decode sat outside every `phases.*` guard, so `SerializationException` left
     * `planPush` with `_status` already set to `Pushing`. `runCycleCatching` caught it
     * above the phase reporter, so nothing moved the status back and `isRunning()`
     * answered true from then on — the sync screen showed a push that had been failing
     * on the same row every cycle, with no dead letter and nothing to retry against.
     *
     * Asserted three ways because the failure has three faces: the cycle has to end,
     * it has to end as a persistence failure rather than a server refusal, and the row
     * has to still be there afterwards so a repair can push it.
     */
    @Test
    fun `an outbox row that cannot be decoded ends the cycle instead of stranding it`() = runTest {
        val backing = FakeSyncOutboxDao().seedOne().seedUndecodable()
        val sut = engine(this, outbox = backing)

        val result = sut.push()

        val error = assertIs<AppError.Persistence>(result.exceptionOrNull())
        assertEquals("Could not assemble the queued changes", error.message)
        assertTrue(
            !sut.status.value.isRunning(),
            "status stranded at ${sut.status.value}; every later sync is refused",
        )
        assertEquals(
            2,
            backing.rows.size,
            "a decode failure must not drop the queued work",
        )
    }

    /**
     * The decode failure is named as storage, not as a server that said no.
     *
     * The server was never asked — the request was never built — so reporting it as a
     * refusal sends the user to look at a connection that was working fine.
     */
    @Test
    fun `a row that cannot be decoded is not reported as a server refusal`() = runTest {
        val backing = FakeSyncOutboxDao().seedOne().seedUndecodable()
        val sut = engine(this, outbox = backing)

        val error = assertIs<AppError>(sut.push().exceptionOrNull())

        assertIs<AppError.Persistence>(error, "reported as ${error::class.simpleName}: ${error.message}")
    }

    // ─── Against a server failure, which must read differently ───────────────

    @Test
    fun `a server failure is not reported as a persistence error`() = runTest {
        val api = FakeSyncApiClient().apply {
            failWith = AppError.Network("The server could not be reached")
        }
        val outbox = FakeSyncOutboxDao().seedOne()
        val sut = engine(this, api = api, outbox = outbox)

        val error = sut.push().exceptionOrNull()!!

        assertIs<AppError.Network>(error, "a refused request is not a local storage problem")
        assertTrue(error !is AppError.Persistence)
    }

    /**
     * The two side by side, because the distinction is worth nothing unless the pair
     * can be told apart. A user who is told "local storage" restarts the app; a user
     * who is told "the server did not take it" waits, and does not throw away edits.
     */
    @Test
    fun `storage and server failures carry different variants and different text`() = runTest {
        val outbox = FakeSyncOutboxDao().seedOne()
        val storage = engine(this, outbox = UnreadableOutbox(outbox, IllegalStateException("closed")))
            .push().exceptionOrNull()!!

        val server = engine(
            this,
            api = FakeSyncApiClient().apply { failWith = AppError.Network("The server could not be reached") },
            outbox = FakeSyncOutboxDao().seedOne(),
        ).push().exceptionOrNull()!!

        assertEquals(AppError.Persistence::class, storage::class)
        assertEquals(AppError.Network::class, server::class)
        assertTrue(storage.message != server.message, "both failures showed the user the same words")
    }

    // ─── A failure before either phase ran ───────────────────────────────────

    /**
     * The cursor read is the first thing `syncOnce` does with storage, and it is what
     * a broken database fails on before any push is attempted.
     */
    private class UnreadableCursor : SyncStateRepository by FakeSyncStateRepository() {
        override suspend fun get(scope: SyncScope): SyncState = throw IllegalStateException("database is closed")
    }

    @Test
    fun `a cycle that could not start says so instead of blaming both phases`() = runTest {
        val api = FakeSyncApiClient()
        val sut = engine(
            this,
            api = api,
            outbox = FakeSyncOutboxDao().seedOne(),
            state = UnreadableCursor(),
        )

        val outcome = sut.syncOnce()

        val failed = assertIs<SyncOutcome.CouldNotStart>(outcome)
        assertIs<AppError.Persistence>(failed.error)
        assertTrue(api.pushCalls.isEmpty(), "no patch should have been attempted")
        assertTrue(api.pullCalls.isEmpty(), "no pull should have been attempted")
        assertTrue(!sut.status.value.isRunning())
    }
}
