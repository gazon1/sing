@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A queued change is sent only by the account that queued it (REQ-UA-019).
 *
 * ## The defect
 *
 * `sync_outbox` had no owner column while `sync_shadow` and `sync_state` both had one,
 * so the device knew who owned a *row* and not who owned the changes to it. `getPending`
 * returned every row and `planPush` built one request from all of them under the active
 * scope.
 *
 * Queues from different accounts coexist by design — REQ-UA-006 keeps work across a
 * sign-out, and neither `apply` nor `signIn` touches the outbox — so this was not a
 * latent shape but a live one. Work queued by one account left the device inside
 * another account's authenticated request.
 *
 * ## What this does not cover
 *
 * REQ-UA-018, in `SyncEnginePushIdentityTest`, covers the *answer*: a response is
 * applied only by the account that asked. It cannot cover the request. By the time the
 * response arrives the bytes are gone, and discarding the response does not recall them.
 * The two requirements fail in the same place and neither one repairs the other.
 *
 * ## The migration, and the cost
 *
 * v37→v38 adds `owner_id` and clears both queue tables. A pre-upgrade row cannot be
 * attributed to an account, and attributing it to a guess would file one account's
 * unsent work under another — silently, and permanently. Clearing states plainly that
 * nothing here is known to be anybody's, at the price of losing work the server never
 * saw. `Migration37To38Test` pins that the rows really are gone.
 */
@Tag("fast")
class SyncEngineOutboxOwnershipTest {

    private val clock = MutableClock()
    private val log = Logger.withTag("SyncEngineOutboxOwnershipTest")

    private val ownerA = "owner-a"
    private val ownerB = "owner-b"

    private class TestEntity(override val syncId: String) : SyncableEntity {
        override val docType: DocType = DocType.Task
        override val syncServerVersion: Long = 0
        override val syncHlc: Hlc? = null
        override fun toJson() = buildJsonObject { put("title", JsonPrimitive("edited $syncId")) }
    }

    private class Harness(
        val engine: SyncEngine,
        val api: FakeSyncApiClient,
        val outbox: FakeSyncOutboxDao,
        val dead: FakeSyncDeadLetterDao,
        val scopes: FakeSyncScopeProvider,
    )

    /**
     * [answer] decides how the server responds to each patch it is handed.
     *
     * The default [FakeSyncApiClient] answers with an empty result list, which is a
     * server that says nothing at all — so nothing is settled, nothing leaves the
     * queue, and every assertion about delivery fails for a reason that has nothing to
     * do with ownership. Answering `ok` for the patch ids the engine sent is what
     * makes the ownership assertions mean anything.
     */
    private fun harness(
        scope: TestScope,
        activeOwner: String = ownerA,
        /** How the server answers each patch it is sent. */
        answer: (DeltaPatch) -> PatchResult = { PatchResult(it.patchId, ok = true, newVersion = 1) },
        retryPolicy: PatchRetryPolicy = PatchRetryPolicy(),
    ): Harness {
        val api = FakeSyncApiClient()
        val outbox = FakeSyncOutboxDao()
        val shadow = FakeSyncShadowDao()
        val dead = FakeSyncDeadLetterDao()
        val scopes = FakeSyncScopeProvider(SyncScope(activeOwner, "profile-1"))
        val engine = SyncEngine(
            log = log,
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId.fromString(activeOwner), "a@x.com", "access", "refresh"),
            ),
            outboxDao = outbox,
            deadLetterDao = dead,
            idGenerator = SequentialIdGenerator(),
            stateRepository = FakeSyncStateRepository(),
            shadowDao = shadow,
            patchBuilder = fakeSyncPatchBuilder(shadow),
            writerProvider = { fakeSyncDocumentWriter() },
            scopeProvider = scopes,
            scheduler = FakeSyncWorkScheduler(),
            retryPolicy = retryPolicy,
            clock = clock,
            scope = testScope(scope.backgroundScope),
            crashReporter = NoOpCrashReportingPort(),
        )
        // One response per request, carrying a result for every patch in it — the
        // server answers a batch with one batch, and a per-patch response queue would
        // leave the second patch's result to be consumed by the *next* push.
        api.onPushInFlight = { request ->
            api.pushResponses.addLast(
                BatchPushResponse(request.patches.map { answer(it) }),
            )
        }
        return Harness(engine, api, outbox, dead, scopes)
    }

    @Test
    fun `no active scope means no request at all`() = runTest {
        // The scope is what says whose queue this push is, so without one there is no
        // owner to send for. The tempting alternative — read the queue anyway and send
        // what is there — is the leak this requirement exists to close: the row would go
        // out under a session that has no profile id and no account behind it.
        val h = harness(this, activeOwner = ownerA)
        assertTrue(h.engine.enqueue(TestEntity("from-a")).isSuccess)

        h.scopes.set(null)
        h.engine.push()

        assertEquals(
            emptyList(),
            h.api.pushCalls.map { call -> call.patches.map { it.entityId } },
            "with no scope there is nothing this request may claim to be",
        )
        assertEquals(1, h.outbox.rows.size, "and the work is still queued for whoever signs in")
    }

    @Test
    fun `work queued by another account is not sent in this account's request`() = runTest {
        val h = harness(this, activeOwner = ownerB)

        // Queue work while A is the active scope.
        h.scopes.set(SyncScope(ownerA, "profile-1"))
        assertTrue(h.engine.enqueue(TestEntity("from-a")).isSuccess)
        assertEquals(1, h.outbox.rows.size, "the work must be queued, or nothing is being tested")

        // B signs in. The queue still holds A's row, which is the whole point.
        h.scopes.set(SyncScope(ownerB, "profile-1"))
        assertEquals(1, h.outbox.rows.size, "REQ-UA-006 keeps one account's queue across another sign-in")

        h.engine.push()

        assertEquals(
            emptyList(),
            h.api.pushCalls.map { call -> call.patches.map { it.entityId } },
            "B's push must not carry A's patch — and with nothing of its own to send, " +
                "B should make no request at all",
        )
        assertEquals(
            1,
            h.outbox.rows.size,
            "A's patch must still be queued — it is A's to send, not discarded",
        )
    }

    @Test
    fun `work queued by this account goes out`() = runTest {
        // The negative case. Without it, a push() that sent nothing at all would pass
        // every test above and the queue would never drain.
        val h = harness(this, activeOwner = ownerA)
        assertTrue(h.engine.enqueue(TestEntity("from-a")).isSuccess)

        h.engine.push()

        assertEquals(
            listOf("from-a"),
            h.api.pushCalls.single().patches.map { it.entityId },
        )
        assertTrue(h.outbox.rows.isEmpty(), "a delivered patch leaves the queue")
    }

    @Test
    fun `two accounts' work queues side by side and each sends only its own`() = runTest {
        // Both queues in one table at once, which is the state the outbox could not
        // previously represent at all.
        val h = harness(this, activeOwner = ownerA)
        assertTrue(h.engine.enqueue(TestEntity("from-a")).isSuccess)

        h.scopes.set(SyncScope(ownerB, "profile-1"))
        assertTrue(h.engine.enqueue(TestEntity("from-b")).isSuccess)

        assertEquals(2, h.outbox.rows.size)
        assertEquals(1, h.outbox.countPendingFor(ownerA))
        assertEquals(1, h.outbox.countPendingFor(ownerB))

        h.scopes.set(SyncScope(ownerA, "profile-1"))
        h.engine.push()
        assertEquals(listOf("from-a"), h.api.pushCalls.single().patches.map { it.entityId })

        h.scopes.set(SyncScope(ownerB, "profile-1"))
        h.engine.push()
        assertEquals(2, h.api.pushCalls.size)
        assertEquals(listOf("from-b"), h.api.pushCalls.last().patches.map { it.entityId })
        assertTrue(h.outbox.rows.isEmpty(), "both are delivered once each has had its turn")
    }

    @Test
    fun `editing one account's entity does not drop the other account's queued patch`() = runTest {
        // The other direction of the same rule. Coalescing deletes by entity before it
        // inserts, and an unscoped delete would let one account's enqueue discard
        // another's unsent work — the same loss REQ-UA-019 forbids, reached from the
        // other side.
        val h = harness(this, activeOwner = ownerA)
        assertTrue(h.engine.enqueue(TestEntity("shared-id")).isSuccess)
        val aPatchId = h.outbox.rows.single().patchId

        h.scopes.set(SyncScope(ownerB, "profile-1"))
        assertTrue(h.engine.enqueue(TestEntity("shared-id")).isSuccess)

        assertEquals(
            2,
            h.outbox.rows.size,
            "B's edit must not have coalesced away A's queued patch",
        )
        assertTrue(h.outbox.rows.any { it.patchId == aPatchId })
    }

    @Test
    fun `a patch set aside for later keeps its owner`() = runTest {
        // The dead letter is a queue too, and it is where work nobody can attribute
        // would otherwise pile up unseen — so it needs the owner as much as the outbox
        // does, and for the same reason.
        //
        // Reaching it takes a *retriable* refusal with the attempts exhausted. A
        // terminal refusal is a different branch: it deletes the row outright and
        // releases the shadow marker instead. The first version of this test accepted
        // everything and so never reached the shelf at all.
        val h = harness(
            scope = this,
            activeOwner = ownerA,
            retryPolicy = PatchRetryPolicy(maxAttempts = 1),
            answer = { PatchResult(it.patchId, ok = false, error = "server busy") },
        )
        assertTrue(h.engine.enqueue(TestEntity("doomed")).isSuccess)
        val patchId = h.outbox.rows.single().patchId

        h.engine.push()

        assertEquals(
            listOf("doomed"),
            h.dead.rows.map { it.entityId },
            "the shelved patch is A's, not whoever signs in next",
        )
        assertEquals(listOf(ownerA), h.dead.rows.map { it.ownerId })
        assertTrue(h.outbox.rows.none { it.patchId == patchId }, "and it left the outbox")
    }
}
