@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.core.sync.work.FakeHlcFactory
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A push response is applied only by the account it was requested under (REQ-UA-018).
 *
 * ## The defect
 *
 * `push()` read `authRepository.currentSession.value` once, at the top, and then
 * applied whatever `api.batchPush` returned: outbox rows deleted as delivered, shadows
 * settled as confirmed. A sign-out or a switch during the request changed nothing
 * about that. For a switch it is the worst version of the bug — one account's queued
 * work marked delivered on the strength of another account's push, with the server and
 * the device now disagreeing and nothing left to say so.
 *
 * The gap was invisible to tests because there was no way to change the session while
 * a request was in flight. `FakeSyncApiClient.onPushInFlight` is that seam; before it,
 * the only way to reach the state was a delay, and a delay proves nothing about
 * whether the code re-read anything.
 *
 * ## What is compared, and why it is not the session
 *
 * The account ([UserId]), not the [Session] as a whole. A token refresh replaces the
 * session — new access token, new refresh token, same account — and it happens
 * routinely, on a timer and on any 401. Comparing sessions would discard nearly every
 * push response, leave every row queued, and re-send the same patches forever over a
 * difference the server itself caused.
 * [a_token_refresh_does_not_discard_the_response] is that case, and it is the reason
 * the rule is stated in terms of the account.
 */
@Tag("fast")
class SyncEnginePushIdentityTest {

    private val clock = MutableClock()
    private val log = Logger.withTag("SyncEnginePushIdentityTest")
    private val account = UserId.fromString("user-a")

    private class Harness(
        val engine: SyncEngine,
        val api: FakeSyncApiClient,
        val auth: FakeSyncAuthRepository,
        val outbox: FakeSyncOutboxDao,
        val shadow: FakeSyncShadowDao,
        val scopes: FakeSyncScopeProvider,
    )

    private class TestEntity(private val fields: JsonObject, override val syncId: String) : SyncableEntity {
        override val docType: DocType = DocType.Task
        override val syncServerVersion: Long = 0
        override val syncHlc: Hlc? = null
        override fun toJson(): JsonObject = fields
    }

    private fun harness(scope: TestScope): Harness {
        val api = FakeSyncApiClient()
        val auth = FakeSyncAuthRepository(
            Session.SignedIn(account, "a@x.com", "access", "refresh"),
        )
        val outbox = FakeSyncOutboxDao()
        val shadow = FakeSyncShadowDao()
        val scopes = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1"))
        val engine = SyncEngine(
            log = log,
            api = api,
            authRepository = auth,
            outboxDao = outbox,
            deadLetterDao = FakeSyncDeadLetterDao(),
            idGenerator = SequentialIdGenerator(),
            stateRepository = FakeSyncStateRepository(),
            shadowDao = shadow,
            patchBuilder = fakeSyncPatchBuilder(shadow),
            writerProvider = { fakeSyncDocumentWriter() },
            scopeProvider = scopes,
            scheduler = FakeSyncWorkScheduler(),
            retryPolicy = PatchRetryPolicy(),
            clock = clock,
            scope = testScope(scope.backgroundScope),
            crashReporter = NoOpCrashReportingPort(),
            hlcFactory = FakeHlcFactory(),
        )
        return Harness(engine, api, auth, outbox, shadow, scopes)
    }

    /**
     * Queues one edit and scripts the server's answer to it.
     *
     * The response is scripted from the outbox row rather than from a known patch id,
     * because the id is whatever the builder minted — the tests are about who applies
     * the answer, not about which string identifies it.
     */
    private suspend fun Harness.queueOneEdit(
        entityId: String = "e-1",
        answer: (String) -> PatchResult = { PatchResult(it, ok = true, newVersion = 7) },
    ): String {
        engine.enqueue(
            TestEntity(
                fields = buildJsonObject { put("title", JsonPrimitive("edited")) },
                syncId = entityId,
            ),
        )
        val patchId = outbox.rows.single().patchId
        api.pushResponses.addLast(BatchPushResponse(listOf(answer(patchId))))
        return patchId
    }

    private fun Harness.shadowOf(
        entityId: String = "e-1",
        profileId: String = "profile-1",
    ) = shadow.state("owner-1", profileId, DocType.Task.key, entityId)

    @Test
    fun `a response arriving after a sign-out is discarded`() = runTest {
        val h = harness(this)
        h.queueOneEdit()
        // Read what the server is known to hold, to assert it did not move. `null`
        // would be the wrong expectation to write down: the column is not nullable and
        // starts as `{}`, and "still what it was" is the claim being made.
        val confirmedBefore = h.shadowOf()?.confirmedJson
        h.api.onPushInFlight = { h.auth.set(Session.SignedOut) }

        val summary = h.engine.push().getOrThrow()

        assertEquals(1, h.outbox.rows.size, "the row must stay queued for the next sign-in")
        assertEquals(confirmedBefore, h.shadowOf()?.confirmedJson, "nothing may be recorded as delivered")
        assertNotNull(h.shadowOf()?.inFlightPatchId, "the in-flight marker must be left alone")
        assertEquals(1, summary.discarded)
        assertEquals(0, summary.succeeded, "a discarded result is not a delivered one")
        assertEquals(0, summary.processed)
    }

    @Test
    fun `a response arriving after a switch is discarded`() = runTest {
        val h = harness(this)
        h.queueOneEdit()
        val confirmedBefore = h.shadowOf()?.confirmedJson
        h.api.onPushInFlight = { h.auth.signIn(UserId.fromString("user-b")) }

        h.engine.push()

        assertEquals(1, h.outbox.rows.size, "user A's row must not be delivered by user B's push")
        assertEquals(confirmedBefore, h.shadowOf()?.confirmedJson)
        assertEquals(0L, h.shadowOf()?.serverVersion, "and the server's version must not be taken")
    }

    @Test
    fun `a response arriving after a token refresh is applied`() = runTest {
        // The reason the comparison is by account. A refresh replaces the session
        // wholesale and is the server's own doing; discarding here would mean the
        // outbox could never drain on an account whose token expires mid-push.
        val h = harness(this)
        h.queueOneEdit()
        h.api.onPushInFlight = { h.auth.refreshToken(account) }

        h.engine.push()

        assertTrue(h.outbox.rows.isEmpty(), "a refreshed token is still the same account")
        assertEquals(7L, h.shadowOf()?.serverVersion)
    }

    @Test
    fun `a response is applied when the account has not changed`() = runTest {
        // The negative case. Without it, a push() that discarded unconditionally would
        // pass every test above and leave nothing ever delivered.
        val h = harness(this)
        h.queueOneEdit()

        val summary = h.engine.push().getOrThrow()

        assertTrue(h.outbox.rows.isEmpty(), "the row must be delivered")
        assertEquals(1, summary.succeeded)
        assertEquals(0, summary.discarded)
    }

    @Test
    fun `a profile change mid-flight settles the shadow under the profile that sent it`() = runTest {
        // The scope is captured with the request, deliberately — re-reading it after
        // the response would put the patches under one profile and their shadow under
        // another. It was implemented and unasserted; the account re-check is about
        // *who*, and says nothing about *which profile*.
        val h = harness(this)
        h.queueOneEdit { PatchResult(it, ok = true, newVersion = 3) }
        h.api.onPushInFlight = { h.scopes.set(SyncScope("owner-1", "profile-2")) }

        h.engine.push()

        assertNull(
            h.shadowOf(profileId = "profile-2"),
            "the shadow must not be settled under the profile that arrived mid-flight",
        )
        assertEquals(3L, h.shadowOf()?.serverVersion, "and must be settled under the one that sent it")
    }

    @Test
    fun `a rejected response after a sign-out is discarded too`() = runTest {
        // Not only the success path. A terminal rejection releases the shadow marker so
        // the next local edit re-sends the fields this patch was carrying; applying
        // that under a session that no longer exists re-sends one account's fields
        // into another's account.
        val h = harness(this)
        h.queueOneEdit { PatchResult(it, ok = false, error = "not_found") }
        h.api.onPushInFlight = { h.auth.set(Session.SignedOut) }

        h.engine.push()

        assertEquals(1, h.outbox.rows.size, "a discarded rejection must not delete the row either")
        assertNotNull(h.shadowOf()?.inFlightPatchId, "nor release the in-flight marker")
    }

    @Test
    fun `a sign-out does not discard queued changes`() = runTest {
        // REQ-UA-006, pinned on this side of the boundary. Without it, "clean up on
        // sign-out" would read as a tidy-up rather than as data loss: the rows are the
        // only copy of work the server has not taken.
        val h = harness(this)
        h.queueOneEdit()
        h.auth.set(Session.SignedOut)

        h.engine.push()

        assertEquals(1, h.outbox.rows.size, "signing out must not touch the queue")
        assertTrue(h.api.pushCalls.isEmpty(), "and must not attempt a push with no account")
    }

    @Test
    fun `the next sign-in is the one that delivers the queued row`() = runTest {
        // The consequence the requirement is really about: the row is not lost, it is
        // simply still this device's to send. Reinstating the original account and
        // pushing again must deliver it.
        val h = harness(this)
        val patchId = h.queueOneEdit()
        h.api.onPushInFlight = { h.auth.set(Session.SignedOut) }
        h.engine.push()
        assertEquals(1, h.outbox.rows.size)

        h.api.onPushInFlight = null
        h.api.pushResponses.addLast(
            BatchPushResponse(listOf(PatchResult(patchId, ok = true, newVersion = 2))),
        )
        h.auth.signIn(account)
        h.engine.push()

        assertTrue(h.outbox.rows.isEmpty(), "the queued row must still be deliverable")
        assertEquals(2L, h.shadowOf()?.serverVersion)
    }

    @Test
    fun `leaving an anonymous session discards the response`() = runTest {
        // The account-less session owns a real [UserId], so it is an account for this
        // purpose. A switch away from it and a sign-out from it look the same here, and
        // both must discard.
        val h = harness(this)
        h.queueOneEdit()
        h.api.onPushInFlight = { h.auth.set(Session.Anonymous(UserId.fromString("local-1"))) }

        val summary = h.engine.push().getOrThrow()

        // One push only. A second would return early — the engine does not push for an
        // account-less session — and would report an empty summary that says nothing
        // about the discard this test is about.
        assertEquals(1, h.outbox.rows.size)
        assertEquals(1, summary.discarded)
    }
}
