package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [HlcFactory] when the node id cannot be obtained.
 *
 * ## What this is a control for
 *
 * `nodeIdDeferred` was completed by `deferred.complete(deviceIdProvider.getOrInitDeviceId())`.
 * Those two expressions are not a composition — the provider call is an argument, so when it
 * threw, the launch body died *before* `complete` ran and the deferred stayed pending for the
 * life of the process. `getCompleted()` on a pending deferred raises `IllegalStateException`,
 * so a single failed device-id read at construction broke every `tick` and `tock` from then
 * on, with a message naming neither the provider nor the cause.
 *
 * This is the same defect as the one in `AuthRepository.restoreSession`, in a different
 * container: a value whose only exit is a suspend call, with no guarantee that the call is
 * reached. There the state was `Session.Loading` and the user saw a spinner; here it is a
 * deferred and sync stops minting ordering keys.
 *
 * ## Why "throws" is the assertion and not "still works"
 *
 * Fabricating a node id looks like the safer default and is not. The node id is half of the
 * HLC string, and two devices that both fell back to a placeholder would mint byte-identical
 * HLCs for concurrent edits — the collision [HlcFactoryConcurrencyTest] exists to prevent,
 * except silent and crossing a device boundary. Failing loudly, with the real cause, keeps the
 * conflict visible at the point of failure instead of deciding data at the server later.
 *
 * So the contract asserted here is that the deferred reaches a *terminal* state carrying the
 * original cause — not that `tick` succeeds.
 */
@Tag("fast")
class HlcFactoryNodeIdFailureTest {

    /**
     * A scope dispatching unconfined.
     *
     * The factory resolves its node id from a coroutine started in its constructor, so unconfined
     * dispatch runs that launch inline during construction. Without it the test would be racing
     * the scheduler, and a failure would be indistinguishable from the defect under test.
     * The [Job] is required too: `AutoCloseableCoroutineScope.close` calls `cancel()` on its own
     * context, and `cancel()` on a context with no `Job` throws.
     */
    private fun newScope() = AutoCloseableCoroutineScope(Dispatchers.Unconfined + Job())

    private class FailingNodeStore(private val failure: () -> Exception) : DeviceIdProvider {
        override suspend fun getOrInitDeviceId(): String = throw failure()
    }

    @Test
    fun `a device id provider that fails reports its own cause, not a pending deferred`() {
        val cause = IllegalStateException("keystore unreadable")
        val factory = HlcFactory(
            deviceIdProvider = FailingNodeStore { cause },
            clock = MutableClock(),
            scope = newScope(),
        )

        val thrown = assertFailsWith<Exception> { factory.tick() }

        assertTrue(
            thrown === cause || thrown.cause === cause,
            "the original cause has to survive; got ${thrown::class.simpleName}: ${thrown.message}",
        )
        assertTrue(
            thrown !is IllegalStateException || thrown.message?.contains("Deferred") != true,
            "an IllegalStateException about a pending deferred is the defect, not the contract",
        )
    }

    @Test
    fun `a failure is stable rather than flapping between outcomes`() {
        // Determinism is the reason to complete exceptionally rather than fall back: a caller
        // that retries must get the same answer, or "sometimes it works" becomes a support
        // question the log cannot answer.
        val factory = HlcFactory(
            deviceIdProvider = FailingNodeStore { IllegalStateException("nope") },
            clock = MutableClock(),
            scope = newScope(),
        )

        repeat(3) {
            assertFailsWith<Exception> { factory.tick() }
            assertFailsWith<Exception> { factory.tock(Hlc.zero("remote")) }
        }
    }

    @Test
    fun `current still answers after the node id failed`() {
        // The failure is confined to the operations that need a node id. A caller reading the
        // last timestamp must not be taken down by a provider that failed during construction.
        val factory = HlcFactory(
            deviceIdProvider = FailingNodeStore { IllegalStateException("nope") },
            clock = MutableClock(),
            scope = newScope(),
        )

        assertEquals(Hlc.zero("pending"), factory.current())
    }
}
