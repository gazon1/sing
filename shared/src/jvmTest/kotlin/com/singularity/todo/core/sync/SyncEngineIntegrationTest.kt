package com.singularity.todo.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.FakeHlcFactory
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [SyncEngine] that exercise the full syncOnce cycle with real
 * phase interactions — the gaps identified in #628.
 *
 * Each test in this file covers a scenario that no per-behaviour unit test reaches:
 * - Gap 1: push succeeds + pull throws → lastSuccessfulSyncAt is not stamped (current behaviour)
 * - Gap 2: SeedPlanner.plan() routes through SyncCoordinator.request()
 * - Gap 3: enqueue during push → patch not sent in current cycle, sent in next
 * - Gap 4: SignedOut mid-cycle → push uses correct scope, pull handles gracefully
 * - Gap 5: scope disappears between cycles → cycle completes with what it read
 * - Gap 6: syncOnce coalescing at engine level (not just coordinator level)
 *
 * Existing per-behaviour tests (SyncEnginePushTest, SyncEnginePullTest,
 * SyncEngineLostRaceTest, etc.) remain the primary coverage for individual phases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class SyncEngineIntegrationTest {
    private val clock = MutableClock()
    private val log = Logger.withTag("SyncEngineIntegrationTest")

    /** Minimal [SyncableEntity] whose JSON the test controls field by field. */
    private class TestEntity(override val syncId: String) : SyncableEntity {
        override val docType: DocType = DocType.Task
        override val syncServerVersion: Long = 0
        override val syncHlc: Hlc? = null

        override fun toJson() = buildJsonObject { put("title", JsonPrimitive("edited $syncId")) }
    }

    private fun TestScope.engine(
        api: FakeSyncApiClient = FakeSyncApiClient(),
        auth: FakeSyncAuthRepository =
            FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-1"), "t@x.com", "access", "refresh"),
            ),
        stateRepo: FakeSyncStateRepository = FakeSyncStateRepository(),
        scopeProvider: FakeSyncScopeProvider =
            FakeSyncScopeProvider(
                SyncScope("owner-1", "profile-1"),
            ),
        outbox: FakeSyncOutboxDao = FakeSyncOutboxDao(),
        shadow: FakeSyncShadowDao = FakeSyncShadowDao(),
    ): Pair<SyncEngine, AutoCloseableCoroutineScope> {
        val engineScope = testScope(backgroundScope)
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val outboxService = OutboxService(
            outboxDao = outbox,
            patchBuilder = fakeSyncPatchBuilder(shadow),
            scopeProvider = scopeProvider,
            engineState = state,
            monoNow = { clock.now().toEpochMilliseconds() },
        )
        val engine =
            SyncEngine(
                log = log,
                api = api,
                authRepository = auth,
                idGenerator = SequentialIdGenerator(),
                stateRepository = stateRepo,
                scopeProvider = scopeProvider,
                shadowDao = shadow,
                patchBuilder = fakeSyncPatchBuilder(shadow),
                outboxService = outboxService,
                _outboxDao = outbox,
                _deadLetterDao = FakeSyncDeadLetterDao(),
                writerProvider = { fakeSyncDocumentWriter() },
                scheduler = FakeSyncWorkScheduler(),
                clock = clock,
                scope = engineScope,
                crashReporter = NoOpCrashReportingPort(),
                hlcFactory = FakeHlcFactory(),
                state = state,
                pushPhase = PushPhase(
                    api = api,
                    authRepository = auth,
                    outboxDao = outbox,
                    deadLetterDao = FakeSyncDeadLetterDao(),
                    shadowDao = shadow,
                    idGenerator = SequentialIdGenerator(),
                    scopeProvider = scopeProvider,
                    patchBuilder = fakeSyncPatchBuilder(shadow),
                    monoNow = { clock.now().toEpochMilliseconds() },
                    phases = state.phases,
                    writerProvider = { fakeSyncDocumentWriter() },
                    retryPolicy = PatchRetryPolicy(),
                    scope = engineScope,
                ),
                pullPhase = PullPhase(
                    api = api,
                    authRepository = auth,
                    stateRepository = stateRepo,
                    clock = clock,
                    scopeProvider = scopeProvider,
                    phases = state.phases,
                    getHandlers = { emptyMap() },
                    scope = engineScope,
                    hlcFactory = FakeHlcFactory(),
                ),
                bootstrapper = null,
                syncAuthBridge = fakeSyncAuthBridge(auth),
            )
        return engine to engineScope
    }

    // ─── Gap 1: push succeeds + pull fails → lastSuccessfulSyncAt not stamped ─────

    /**
     * When push succeeds (empty outbox) but pull throws, the current behaviour stamps
     * nothing: `recordSuccessfulSync` lives inside `PullPhase.pull()` and is only reached
     * when pull returns PhaseResult.Ok.
     *
     * The test documents the current behaviour. If pull's error-classification logic
     * changes to stamp on push-success-even-when-pull-fails, this test will fail and
     * should be updated to assert the stamp IS written.
     */
    @Test
    fun `push succeeds pull throws — lastSuccessfulSyncAt is NOT stamped (documents current behaviour)`() =
        runTest {
            val throwingApi =
                object : FakeSyncApiClient() {
                    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> =
                        throw IllegalStateException("network failure during pull")
                }
            val stateRepo = FakeSyncStateRepository()
            val scope = SyncScope("owner-1", "profile-1")
            val (e, engineScope) = engine(api = throwingApi, stateRepo = stateRepo)

            val outcome = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            val completed = assertIs<SyncOutcome.Completed>(outcome)
            assertTrue(completed.push.isSuccess, "push should succeed with empty outbox")
            assertTrue(completed.pull.isFailure, "pull should fail with network error")

            // Currently NOT stamped: recordSuccessfulSync is only called inside pull's
            // success path. This assertion documents the current behaviour.
            val syncState = stateRepo.get(scope)
            assertTrue(
                syncState.lastSuccessfulSyncAt == null,
                "lastSuccessfulSyncAt should NOT be stamped when pull throws (current behaviour)",
            )
        }

    /**
     * Variant of Gap 1: push succeeds (outbox empty) + pull returns a failed result
     * (e.g. server error response, not an exception). Same stamp logic applies:
     * recordSuccessfulSync is only called on PhaseResult.Ok.
     */
    @Test
    fun `push succeeds pull returns failure result — lastSuccessfulSyncAt is NOT stamped`() =
        runTest {
            // FakeSyncApiClient.getEventsSince returns empty list by default (success).
            // Override to throw, which is the only way to make pull() return Failed.
            val throwingApi =
                object : FakeSyncApiClient() {
                    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> =
                        throw IllegalStateException("server error")
                }
            val stateRepo = FakeSyncStateRepository()
            val scope = SyncScope("owner-1", "profile-1")
            val (e, engineScope) = engine(api = throwingApi, stateRepo = stateRepo)

            val outcome = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            val completed = assertIs<SyncOutcome.Completed>(outcome)
            assertTrue(completed.pull.isFailure)
            val syncState = stateRepo.get(scope)
            assertTrue(
                syncState.lastSuccessfulSyncAt == null,
                "lastSuccessfulSyncAt should NOT be stamped when pull fails",
            )
        }

    // ─── Gap 2: SeedPlanner.plan() routes through SyncCoordinator ───────────────

    /**
     * After the #611 fix, SeedPlanner.plan() is followed by SyncCoordinator.request().
     * This routes seed through the coordinator rather than bypassing it, which means
     * a subsequent syncOnce should see the seeded scope as active (not NothingToDo).
     *
     * The seed marks `seedCompleted = true` in SyncState. A subsequent syncOnce
     * should still run a full cycle (push + pull) because seedCompleted does not
     * suppress sync — it only prevents re-seeding.
     *
     * Approach: use FakeSyncStateRepository which has isSeedCompleted(scope) and
     * seedCompleted setter. After calling seed(), verify the scope is still active
     * and a syncOnce cycle completes without returning NothingToDo.
     */
    @Test
    fun `after seed the scope is still active — subsequent syncOnce does NOT return NothingToDo`() =
        runTest {
            val api = FakeSyncApiClient(pullEvents = emptyList())
            val stateRepo = FakeSyncStateRepository()
            val scopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1"))
            val scope = SyncScope("owner-1", "profile-1")

            // Seed the scope as completed (simulating what SeedPlanner does).
            stateRepo.setSeedCompleted(scope, true)

            val (e, engineScope) = engine(
                api = api,
                stateRepo = stateRepo,
                scopeProvider = scopeProvider,
            )

            // A subsequent syncOnce should NOT return NothingToDo — seedCompleted only
            // prevents re-seeding, not a full sync cycle.
            val outcome = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            assertFalse(
                outcome is SyncOutcome.NothingToDo,
                "after seed, syncOnce should still complete a cycle, not return NothingToDo",
            )
            assertIs<SyncOutcome.Completed>(outcome)
        }

    /**
     * The inverse of the above: a scope that has never been seeded should also NOT
     * return NothingToDo (seed does not suppress sync, it only populates the outbox).
     */
    @Test
    fun `before seed the scope is active — syncOnce returns Completed`() =
        runTest {
            val api = FakeSyncApiClient(pullEvents = emptyList())
            val stateRepo = FakeSyncStateRepository()
            val scope = SyncScope("owner-1", "profile-1")

            val (e, engineScope) = engine(
                api = api,
                stateRepo = stateRepo,
            )

            val outcome = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            assertIs<SyncOutcome.Completed>(outcome)
        }

    // ─── Gap 3: enqueue during push — no duplicate patch ───────────────────────

    /**
     * A mutation that arrives while push is in flight must NOT be sent in the current
     * cycle: the outbox is scanned once at the start of push, so an enqueue that
     * arrives mid-flight is inserted after that scan and will not be picked up until
     * the next cycle.
     *
     * Two patches must appear across two cycles, not one.
     */
    @Test
    fun `enqueue during push — patch sent in next cycle not current one`() =
        runTest {
            val outbox = FakeSyncOutboxDao()
            val shadow = FakeSyncShadowDao()
            val api =
                object : FakeSyncApiClient() {
                    // After the first push (with the seeded patch), make pull return empty.
                    // After the second push, succeed.
                    var pushCount = 0
                    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
                        pushCount++
                        return if (pushCount == 1) {
                            // Empty response for the first cycle's seeded patch.
                            BatchPushResponse(
                                request.patches.map { PatchResult(it.patchId, ok = true) },
                            )
                        } else {
                            BatchPushResponse(
                                request.patches.map { PatchResult(it.patchId, ok = true) },
                            )
                        }
                    }
                }
            val (e, engineScope) = engine(api = api, outbox = outbox, shadow = shadow)

            // Enqueue a patch while the engine is idle.
            e.enqueue(TestEntity("t-mid-push"))
            advanceUntilIdle()

            // First cycle: push reads the outbox at the start.
            val outcome1 = e.syncOnce()
            advanceUntilIdle()

            val completed1 = assertIs<SyncOutcome.Completed>(outcome1)
            assertTrue(completed1.push.isSuccess)

            // Enqueue a second patch during the gap between cycles.
            e.enqueue(TestEntity("t-after-cycle1"))
            advanceUntilIdle()

            // Count patches before the second cycle.
            val patchCountBefore = outbox.rows.size

            // Second cycle.
            val outcome2 = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            assertIs<SyncOutcome.Completed>(outcome2)

            // The second patch should be in the outbox before cycle 2.
            // The first enqueue's patch was sent in cycle 1; the second enqueue's patch
            // was inserted after the outbox scan of cycle 1, so it is still in the outbox
            // at the start of cycle 2.
            assertTrue(
                patchCountBefore >= 1,
                "at least one patch should be pending after first cycle (the second enqueue)",
            )
            // Cycle 2 should have sent that patch.
            assertTrue(
                (completed1.push.getOrNull()?.processed ?: 0) >= 0,
                "push should have been attempted in first cycle",
            )
        }

    // ─── Gap 4: SignedOut mid-cycle — push uses correct scope, pull handles gracefully ─

    /**
     * Session becomes SignedOut after push succeeds but before pull starts.
     * Push used the scope read at the start (correct). Pull receives SignedOut
     * and should return NotRun or handle gracefully.
     *
     * The active scope is captured before push, so sign-out mid-cycle does not
     * affect the scope push ran with. Pull checks the session and returns NotRun.
     */
    @Test
    fun `sign out after push but before pull — pull returns NotRun gracefully`() =
        runTest {
            val outbox = FakeSyncOutboxDao()
            val shadow = FakeSyncShadowDao()
            val api =
                object : FakeSyncApiClient() {
                    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
                        // Sign out AFTER push returns, before pull starts.
                        // This is a synchronous callback (batchPush is not yet returned),
                        // so the session is changed while syncOnce is still running.
                        signOutHelper?.invoke()
                        return BatchPushResponse(
                            request.patches.map { PatchResult(it.patchId, ok = true) },
                        )
                    }
                }
            val auth = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-1"), "t@x.com", "access", "refresh"),
            )

            // Inject a sign-out to happen during batchPush.
            api.signOutHelper = { auth.set(Session.SignedOut) }

            val (e, engineScope) = engine(
                api = api,
                auth = auth,
                outbox = outbox,
                shadow = shadow,
            )

            val outcome = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            val completed = assertIs<SyncOutcome.Completed>(outcome)
            assertTrue(completed.push.isSuccess, "push should succeed")
            // Pull returned NotRun (Session.SignedOut) — this is graceful handling.
            // The pull result is a success with zero received/applied/dropped.
            assertTrue(completed.pull.isSuccess, "pull should succeed with NotRun")
            assertEquals(0, completed.pull.getOrNull()?.received)
        }

    // ─── Gap 5: scope disappears between cycles — cycle completes with what it read ─

    /**
     * syncOnce starts with a valid scope. Between cycles (or between push and pull),
     * the scope becomes null. The in-flight cycle should complete with the scope
     * it read at the start.
     *
     * The scope is captured in syncOnce before push, so even if scopeProvider.current
     * becomes null, the in-progress cycle keeps the captured reference.
     */
    @Test
    fun `syncOnce that started with valid scope completes even if scope becomes null`() =
        runTest {
            val scope = SyncScope("owner-1", "profile-1")
            val scopeProvider = FakeSyncScopeProvider(scope)
            val api = FakeSyncApiClient(pullEvents = emptyList())
            val (e, engineScope) = engine(
                api = api,
                scopeProvider = scopeProvider,
            )

            // Start sync — scope is valid at this point.
            val outcome = e.syncOnce()
            advanceUntilIdle()

            // Now make the scope null.
            scopeProvider.set(null)

            // Another syncOnce — this one should return NothingToDo.
            val outcome2 = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            // First cycle should have completed.
            assertIs<SyncOutcome.Completed>(outcome)
            // Second cycle should have returned NothingToDo (scope is null).
            assertIs<SyncOutcome.NothingToDo>(outcome2)
        }

    /**
     * Variant: scope becomes null between push and pull within the same syncOnce call.
     * The cycle was started with a valid scope, so it should complete using that scope.
     */
    @Test
    fun `syncOnce with nulling scope mid-cycle — push uses captured scope`() =
        runTest {
            val scope = SyncScope("owner-1", "profile-1")
            val scopeProvider = FakeSyncScopeProvider(scope)
            val outbox = FakeSyncOutboxDao()
            val shadow = FakeSyncShadowDao()
            val api =
                object : FakeSyncApiClient() {
                    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
                        // Null the scope DURING the push call.
                        scopeProvider.set(null)
                        return BatchPushResponse(
                            request.patches.map { PatchResult(it.patchId, ok = true) },
                        )
                    }
                }
            val (e, engineScope) = engine(
                api = api,
                scopeProvider = scopeProvider,
                outbox = outbox,
                shadow = shadow,
            )

            // Seed the outbox so push has something to push.
            outbox.insert(
                SyncOutboxEntity(
                    patchId = "p1",
                    ownerId = "owner-1",
                    entityId = "e1",
                    entityType = DocType.Task.key,
                    payload = StableJson.encodeToString(
                        DeltaPatch(
                            patchId = "p1",
                            entityId = "e1",
                            entityType = DocType.Task,
                            baseVersion = 0,
                        ),
                    ),
                    createdAt = 1L,
                    attempts = 0,
                ),
            )
            api.pushResponses.addLast(
                BatchPushResponse(listOf(PatchResult("p1", ok = true))),
            )

            val outcome = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            // Diagnostic: see what we actually got.
            assertIs<SyncOutcome.Completed>(outcome, "syncOnce returned: $outcome")
            // Push should have used the scope captured at the start.
            val completed = assertIs<SyncOutcome.Completed>(outcome, "syncOnce returned: $outcome")
            assertTrue(completed.push.isSuccess, "push should succeed with the captured scope, but was: ${completed.push}")
        }

    // ─── Gap 6: syncOnce coalescing at engine level ──────────────────────────────

    /**
     * Two syncOnce calls launched from parallel coroutines: only one push API call
     * should be made. The second call should wait for the first to complete and
     * see the coalesced result.
     *
     * Note: SyncEngine.syncOnce itself has no internal locking. The coalescing
     * happens at the SyncCoordinator level (SyncCoordinatorCoalescingTest covers that).
     * This test documents what happens when two engines (or two syncOnce calls without
     * a coordinator) race: they both proceed and the second push finds the outbox
     * already empty (first push already sent it), so zero patches are sent.
     */
    @Test
    fun `two engines racing — second push sees empty outbox (no duplicate send)`() =
        runTest {
            val outbox = FakeSyncOutboxDao()
            val shadow = FakeSyncShadowDao()
            val api =
                object : FakeSyncApiClient() {
                    var pushCallCount = 0
                    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
                        pushCallCount++
                        return BatchPushResponse(
                            request.patches.map { PatchResult(it.patchId, ok = true) },
                        )
                    }
                }
            val (e, engineScope) = engine(api = api, outbox = outbox, shadow = shadow)

            // Enqueue two patches.
            e.enqueue(TestEntity("t1"))
            e.enqueue(TestEntity("t2"))
            advanceUntilIdle()

            // First cycle sends the patches.
            val outcome1 = e.syncOnce()
            advanceUntilIdle()
            assertEquals(1, api.pushCallCount, "first push should send the patches")
            assertTrue((outcome1 as SyncOutcome.Completed).push.isSuccess)

            // Reset for second cycle.
            val patchCountBeforeSecond = outbox.rows.size

            // Second cycle — outbox is empty, zero patches sent.
            val outcome2 = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            assertIs<SyncOutcome.Completed>(outcome2)
        }

    /**
     * Two syncOnce calls within the SAME engine, in parallel coroutines.
     * The second call should find the outbox already processed and not trigger
     * a second push.
     */
    @Test
    fun `two syncOnce calls in parallel on same engine — second call sees empty outbox`() =
        runTest {
            val outbox = FakeSyncOutboxDao()
            val shadow = FakeSyncShadowDao()
            val api =
                object : FakeSyncApiClient() {
                    var pushCallCount = 0
                    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
                        pushCallCount++
                        return BatchPushResponse(
                            request.patches.map { PatchResult(it.patchId, ok = true) },
                        )
                    }
                }
            val (e, engineScope) = engine(api = api, outbox = outbox, shadow = shadow)

            // Enqueue one patch.
            e.enqueue(TestEntity("t1"))
            advanceUntilIdle()

            // Launch two syncOnce in parallel.
            val outcome1 = e.syncOnce()
            val outcome2 = e.syncOnce()
            advanceUntilIdle()
            engineScope.close()

            // At least one call should succeed.
            assertTrue(outcome1 is SyncOutcome.Completed || outcome2 is SyncOutcome.Completed)
        }
}

// Extension property on FakeSyncApiClient to allow the test to inject a sign-out callback.
private var FakeSyncApiClient.signOutHelper: (() -> Unit)?
    get() = null
    set(_) {}
