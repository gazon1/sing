@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Push retry behaviour of [SyncEngine] (REQ-OS-010).
 *
 * ## The defect
 *
 * The outbox had an `attempts` column and `markFailed` incremented it — and nothing
 * read it. `getPending()` returned every row, in creation order, on every cycle. A
 * patch the server kept rejecting was therefore re-sent immediately and forever, with
 * no delay and no deadline: the worst retry policy available, aimed at a server that
 * was already the thing having a bad time.
 *
 * ## The policy
 *
 * Exponential from 30 s, capped at one hour, ten attempts, then the patch is *moved*
 * to a dead-letter store. Moved, not deleted — a user's edit should not be destroyed
 * because the system could not deliver it.
 */
@Tag("fast")
class SyncEnginePushTest {

    private val log = Logger.withTag("SyncEnginePushTest")

    private fun engine(
        api: FakeSyncApiClient,
        scope: TestScope,
        outbox: FakeSyncOutboxDao = FakeSyncOutboxDao(),
        deadLetter: FakeSyncDeadLetterDao = FakeSyncDeadLetterDao(),
        policy: PatchRetryPolicy = PatchRetryPolicy(),
    ): Triple<SyncEngine, FakeSyncOutboxDao, FakeSyncDeadLetterDao> {
        val engine = SyncEngine(
            log = log,
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh"),
            ),
            outboxDao = outbox,
            deadLetterDao = deadLetter,
            idGenerator = SequentialIdGenerator(),
            prefs = FakeSyncPrefs(),
            scheduler = FakeSyncWorkScheduler(),
            retryPolicy = policy,
            scope = testScope(scope.backgroundScope),
            crashReporter = NoOpCrashReportingPort(),
        )

        return Triple(engine, outbox, deadLetter)
    }

    private suspend fun FakeSyncOutboxDao.seed(patchId: String, attempts: Int = 0, createdAt: Long = 1L) {
        insert(
            SyncOutboxEntity(
                patchId = patchId,
                entityId = "entity-$patchId",
                entityType = DocType.Task.key,
                payload = StableJson.encodeToString(
                    DeltaPatch(
                        patchId = patchId,
                        entityId = "entity-$patchId",
                        entityType = DocType.Task,
                        baseVersion = 0,
                    ),
                ),
                createdAt = createdAt,
                attempts = attempts,
            ),
        )
    }

    private fun rejecting(patchId: String, error: String = "server busy") = PatchResult(
        patchId = patchId,
        ok = false,
        error = error,
    )

    @Test
    fun `a rejected patch is retried later, not on the next cycle`() = runTest {
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(rejecting("p1"))))
        val (engine, outbox, dead) = engine(api, this)
        outbox.seed("p1")

        engine.push()

        val row = outbox.rows.single()
        assertEquals(1, row.attempts)
        assertNotNull(row.nextAttemptAt, "a failed patch must be deferred")
        // A real delay, not a zero that reads as "deferred" while behaving as "now".
        assertTrue(
            (row.nextAttemptAt ?: 0) - System.currentTimeMillis() > 0,
            "nextAttemptAt must be in the future",
        )
        assertTrue(dead.rows.isEmpty())
    }

    @Test
    fun `a deferred patch is not sent again before its time`() = runTest {
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(rejecting("p1"))))
        val (engine, outbox, dead) = engine(api, this)
        outbox.seed("p1")

        engine.push()
        assertEquals(1, api.pushCalls.size)

        // Second cycle immediately after: the patch is still deferred, so there is
        // nothing to send. Before the fix this sent it again, and every cycle after.
        engine.push()
        assertEquals(1, api.pushCalls.size, "the deferred patch was re-sent immediately")

        // Once the backoff has elapsed, it goes out again.
        outbox.rows.single().let { row ->
            outbox.rows[0] = row.copy(nextAttemptAt = System.currentTimeMillis() - 1)
        }
        engine.push()
        assertEquals(2, api.pushCalls.size, "an elapsed backoff must allow a retry")
    }

    @Test
    fun `a patch past the attempt limit moves to the dead letter store`() = runTest {
        val policy = PatchRetryPolicy(baseDelayMs = 1, maxDelayMs = 1, maxAttempts = 3)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(rejecting("p1"))))
        val (engine, outbox, dead) = engine(api, this, policy = policy)
        outbox.seed("p1")

        repeat(3) {
            // Expire the backoff between attempts so the patch is eligible each time.
            if (outbox.rows.isNotEmpty()) {
                outbox.rows[0] = outbox.rows[0].copy(nextAttemptAt = System.currentTimeMillis() - 1)
            }
            engine.push()
        }

        assertTrue(outbox.rows.isEmpty(), "an exhausted patch must leave the outbox")
        val parked = dead.rows.single()
        assertEquals("p1", parked.patchId)
        assertEquals(3, parked.attempts)
        assertEquals("server busy", parked.lastError)
    }

    @Test
    fun `a dead-lettered patch is not retried by later cycles`() = runTest {
        val policy = PatchRetryPolicy(baseDelayMs = 1, maxDelayMs = 1, maxAttempts = 1)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(rejecting("p1"))))
        val (engine, outbox, dead) = engine(api, this, policy = policy)
        outbox.seed("p1")

        engine.push()
        assertEquals(1, api.pushCalls.size)
        assertEquals(1, dead.rows.size)

        engine.push()
        engine.push()
        assertEquals(1, api.pushCalls.size, "a dead-lettered patch must not be retried")
    }

    @Test
    fun `the backoff grows and is capped`() {
        val policy = PatchRetryPolicy(baseDelayMs = 1_000, maxDelayMs = 8_000)

        assertEquals(1_000, policy.delayFor(1))
        assertEquals(2_000, policy.delayFor(2))
        assertEquals(4_000, policy.delayFor(3))
        // Capped, not still doubling: an uncapped doubling eventually exceeds any
        // sensible lifetime, which is "never" wearing a policy costume.
        assertEquals(8_000, policy.delayFor(4))
        assertEquals(8_000, policy.delayFor(50))
    }

    @Test
    fun `a non-retriable rejection is dropped rather than backed off`() = runTest {
        // `too_old` is the protocol's "your base version is behind, stop sending this".
        val api = FakeSyncApiClient(
            pushResponse = BatchPushResponse(listOf(rejecting("p1", error = "too_old"))),
        )
        val (engine, outbox, dead) = engine(api, this)
        outbox.seed("p1")

        engine.push()

        assertTrue(outbox.rows.isEmpty(), "an unacceptable patch must not linger")
        assertTrue(dead.rows.isEmpty(), "and must not be dead-lettered either")
    }

    @Test
    fun `a successful patch leaves the outbox`() = runTest {
        val api = FakeSyncApiClient(
            pushResponse = BatchPushResponse(listOf(PatchResult(patchId = "p1", ok = true))),
        )
        val (engine, outbox, dead) = engine(api, this)
        outbox.seed("p1")

        val summary = engine.push().getOrThrow()

        assertEquals(1, summary.succeeded)
        assertEquals(0, summary.failed)
        assertTrue(outbox.rows.isEmpty())
        assertNull(outbox.rows.firstOrNull()?.lastError)
    }

    @Test
    fun `one failing patch does not hold up the others in the batch`() = runTest {
        val api = FakeSyncApiClient(
            pushResponse = BatchPushResponse(
                listOf(
                    PatchResult(patchId = "ok1", ok = true),
                    rejecting("bad1"),
                    PatchResult(patchId = "ok2", ok = true),
                ),
            ),
        )
        val (engine, outbox, dead) = engine(api, this)
        outbox.seed("ok1", createdAt = 1)
        outbox.seed("bad1", createdAt = 2)
        outbox.seed("ok2", createdAt = 3)

        val summary = engine.push().getOrThrow()

        assertEquals(2, summary.succeeded)
        assertEquals(1, summary.failed)
        assertEquals(listOf("bad1"), outbox.rows.map { it.patchId })
    }
}
