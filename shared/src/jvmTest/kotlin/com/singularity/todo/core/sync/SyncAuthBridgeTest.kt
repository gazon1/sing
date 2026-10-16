package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dedicated tests for [SyncAuthBridge].
 *
 * Covers the behaviour extracted from `SyncEngine.init {}`:
 * - [Session.SignedIn] → [SyncWorkScheduler.enqueuePush] is called
 * - [Session.SignedOut] → [SyncWorkScheduler.cancelPush] is called
 * - [Session.Anonymous] → [SyncWorkScheduler.cancelPush] is called
 * - Transition: SignedIn → SignedOut → enqueues then cancels
 * - Transition: Anonymous → SignedIn → cancels then enqueues
 *
 * The bridge owns its own [AutoCloseableCoroutineScope] and is [AutoCloseable] itself.
 *
 * ## Timing
 *
 * `runCurrent()` is used instead of `advanceUntilIdle()` because the bridge's `init {}`
 * block launches a coroutine that calls the scheduler synchronously with respect to the
 * collector. `advanceUntilIdle()` runs pending tasks but in a different dispatch mode that
 * can defer the bridge's collector past the assertion point in fast tests.
 */
@Tag("fast")
class SyncAuthBridgeTest {

    private val log = Logger.withTag("SyncAuthBridgeTest")

    private fun TestScope.bridge(
        scheduler: FakeSyncWorkScheduler = FakeSyncWorkScheduler(),
        session: Session = Session.SignedOut,
    ): Pair<SyncAuthBridge, FakeSyncAuthRepository> {
        val scope: AutoCloseableCoroutineScope = testScope(backgroundScope)
        val auth = FakeSyncAuthRepository(session)
        val bridge = SyncAuthBridge(
            authRepository = auth,
            scheduler = scheduler,
            scope = scope,
            log = log,
        )
        return bridge to auth
    }

    // ─── initial state ────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initial SignedIn calls enqueuePush`() = runTest {
        val scheduler = FakeSyncWorkScheduler()
        val (bridge, _) = bridge(
            scheduler = scheduler,
            session = Session.SignedIn(UserId("uid"), "e@x.com", "acc", "ref"),
        )

        runCurrent()

        assertTrue(scheduler.enqueuePushCalled, "SignedIn must call enqueuePush")
        assertFalse(scheduler.cancelPushCalled, "SignedIn must not call cancelPush")

        bridge.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initial SignedOut calls cancelPush`() = runTest {
        val scheduler = FakeSyncWorkScheduler()
        val (bridge, _) = bridge(scheduler = scheduler, session = Session.SignedOut)

        runCurrent()

        assertTrue(scheduler.cancelPushCalled, "SignedOut must call cancelPush")
        assertFalse(scheduler.enqueuePushCalled, "SignedOut must not call enqueuePush")

        bridge.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initial Anonymous calls cancelPush`() = runTest {
        val scheduler = FakeSyncWorkScheduler()
        val (bridge, _) = bridge(
            scheduler = scheduler,
            session = Session.Anonymous(UserId.generate()),
        )

        runCurrent()

        assertTrue(scheduler.cancelPushCalled, "Anonymous must call cancelPush")
        assertFalse(scheduler.enqueuePushCalled, "Anonymous must not call enqueuePush")

        bridge.close()
    }

    // ─── transitions ─────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SignedOut to SignedIn fires enqueuePush`() = runTest {
        val scheduler = FakeSyncWorkScheduler()
        val (bridge, auth) = bridge(scheduler = scheduler, session = Session.SignedOut)

        scheduler.reset()
        auth.signIn()

        runCurrent()

        assertTrue(scheduler.enqueuePushCalled, "after sign-in, enqueuePush must fire")
        assertFalse(scheduler.cancelPushCalled, "after sign-in, cancelPush must not fire")

        bridge.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SignedIn to SignedOut fires cancelPush`() = runTest {
        val scheduler = FakeSyncWorkScheduler()
        val (bridge, auth) = bridge(
            scheduler = scheduler,
            session = Session.SignedIn(UserId("uid"), "e@x.com", "acc", "ref"),
        )

        scheduler.reset()
        auth.set(Session.SignedOut)

        runCurrent()

        assertTrue(scheduler.cancelPushCalled, "after sign-out, cancelPush must fire")
        assertFalse(scheduler.enqueuePushCalled, "after sign-out, enqueuePush must not fire")

        bridge.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Anonymous to SignedIn fires enqueuePush`() = runTest {
        val scheduler = FakeSyncWorkScheduler()
        val (bridge, auth) = bridge(
            scheduler = scheduler,
            session = Session.Anonymous(UserId.generate()),
        )

        scheduler.reset()
        auth.signIn()

        runCurrent()

        assertTrue(scheduler.enqueuePushCalled, "after anonymous→SignedIn, enqueuePush must fire")

        bridge.close()
    }

    // ─── close ─────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `close cancels the bridge scope`() = runTest {
        val scheduler = FakeSyncWorkScheduler()
        val (bridge, auth) = bridge(
            scheduler = scheduler,
            session = Session.SignedIn(UserId("uid"), "e@x.com", "acc", "ref"),
        )

        runCurrent()
        assertTrue(scheduler.enqueuePushCalled, "precondition: SignedIn must have called enqueuePush")

        // Transition to SignedOut first, which triggers cancelPush via session change.
        // Then closing the bridge cancels its scope, which terminates the collector.
        scheduler.reset()
        auth.set(Session.SignedOut)
        runCurrent()
        assertTrue(scheduler.cancelPushCalled, "sign-out must call cancelPush")

        bridge.close()
    }
}
