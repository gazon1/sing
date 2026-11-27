package spike

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.dsl.subscribe
import pro.respawn.flowmvi.plugins.recover
import pro.respawn.flowmvi.plugins.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gap 2: a fatal JVM `Error` is not a failure to recover from.
 *
 * The in-repo contract is stated by `runCatchingCancellable`, which catches `Exception`
 * only, so an `Error` (OOM, StackOverflow, AssertionError) propagates instead of being
 * turned into a `Result.failure`. See
 * `SemanticGapTest.in-repo MVI lets a fatal JVM error propagate` for that half.
 *
 * FlowMVI must not do worse: `recover` must never see the error, or a `catch (e:
 * Exception)` in the app would mistake a JVM-fatal condition for a handled one.
 *
 * ## Why this is its own class, in its own JVM
 *
 * Measured: FlowMVI does not hand the error to `recover`, to the store's
 * `CoroutineExceptionHandler`, or to the owner scope's — the pipeline simply stops, the
 * store keeps answering `isActive == true`, and every later intent is dropped. Both
 * observed channels — `recover` and the owner scope's handler — are proven live inside
 * the test before the fatal case runs, so "nothing arrived" is a measurement rather than
 * an absence of instrumentation.
 *
 * The error is not fully swallowed, though: it resurfaces on an unrelated thread, and
 * `runTest` fails whichever test runs next with `UncaughtExceptionsBeforeTest`. That
 * surface is *not* reachable from a `CoroutineExceptionHandler` — handlers only see
 * exceptions from coroutines in their own scope — which is why the leak had to be found
 * by running a sibling test, and why containing it means containing the JVM. The probe's
 * `jvmTest` task is split by class name (see `build.gradle.kts`), and this class gets a
 * fork to itself.
 */
class FatalErrorContractTest {

    /**
     * After the error the store must stop reducing, must not offer the error to
     * `recover`, and must never claim to have been stopped cleanly.
     *
     * ## Every absent observation is proven live *by this test*
     *
     * "Nothing received the error" only means something if the channels *would* have
     * received it. A handler whose body ignores its argument stays empty whatever the
     * library does, so both observed channels are proven to work **inside this test,
     * before the fatal case runs** — no assertion here depends on another test having
     * exercised them:
     *
     * - **`recover`**: an ordinary `IllegalStateException` is sent through the same store
     *   and must land in the hook.
     * - **the owner scope's handler**: a throw from a coroutine on that scope must reach
     *   the handler.
     *
     * The controls also pin down the baseline the fatal result is read against: after the
     * ordinary failure the pipeline is shown to **keep reducing** (a second increment
     * still publishes), so the stall observed after the fatal error is attributable to
     * the `Error` and not to the earlier recovery.
     *
     * The store's own `CoroutineExceptionHandler` (from `config.coroutineContext`) is
     * deliberately **not** used as an instrument: measured, FlowMVI never invokes it —
     * not for a fatal `Error`, and not for a plain `IllegalStateException` either. An
     * assertion on it would rest on a channel that cannot observe anything.
     */
    @Test
    fun `FlowMVI does not offer a fatal JVM error to recovery`(): Unit = runBlocking {
        val recovered = mutableListOf<Throwable>()
        val published = mutableListOf<Int>()
        val ownerReports = mutableListOf<Throwable>()
        val owner = CoroutineScope(
            SupervisorJob() + CoroutineExceptionHandler { _, e -> ownerReports += e },
        )

        // Control 1: the owner handler is live, or the "nothing arrived" assertion below
        // is worthless. A throw from a coroutine on this scope must reach it.
        owner.launch { throw IllegalStateException("control: the handler is wired") }
        withTimeoutOrNull(2_000) { while (ownerReports.isEmpty()) delay(10) }
        assertEquals(
            1,
            ownerReports.size,
            "the owner handler never fired for a control failure — it cannot be an instrument",
        )
        ownerReports.clear()

        val store = store<ErrState, ErrIntent, ErrAction>(ErrState()) {
            recover { e ->
                recovered += e
                null
            }
            reduce { intent ->
                when (intent) {
                    ErrIntent.Fatal -> throw FatalThrowable("fatal from an intent")
                    ErrIntent.Ordinary -> throw IllegalStateException("ordinary, recoverable")
                    ErrIntent.Increment -> updateState { ErrState(count + 1) }
                }
            }
        }
        store.start(owner)
        owner.launch {
            subscribe(store, consume = { }, render = { s: ErrState -> published += s.count })
        }
        delay(200)

        store.intent(ErrIntent.Increment)
        assertEquals(1, awaitCount(published, 1), "the store never reduced a healthy intent")

        // Control 2: `recover` is live, observed on this very store.
        store.intent(ErrIntent.Ordinary)
        withTimeoutOrNull(2_000) { while (recovered.isEmpty()) delay(10) }
        assertEquals(
            1,
            recovered.size,
            "the recover hook never fired for a control failure — it cannot be an instrument",
        )
        assertTrue(
            recovered.single() is IllegalStateException,
            "the recover hook received the wrong control throwable: $recovered",
        )
        recovered.clear()

        // Control 3: the pipeline is still healthy after an ordinary recovery, so the
        // stall below can be attributed to the fatal error rather than to the control.
        store.intent(ErrIntent.Increment)
        assertEquals(
            2,
            awaitCount(published, 2),
            "the pipeline stopped after an ordinary recovery — this test cannot attribute the stall to the Error",
        )

        store.intent(ErrIntent.Fatal)
        delay(500)

        // The pipeline must not resume: that is the measured consequence of the error.
        store.intent(ErrIntent.Increment)
        val afterFatal = awaitCount(published, 3)

        assertTrue(
            recovered.none { it is FatalThrowable },
            "a fatal JVM error was offered to the recover hook: $recovered",
        )
        assertTrue(
            ownerReports.none { it is FatalThrowable },
            "the owner scope's CoroutineExceptionHandler received the fatal error: $ownerReports",
        )
        assertNull(afterFatal, "the intent pipeline survived a fatal JVM error")
        assertTrue(
            store.isActive,
            "the store reported itself stopped — that would at least be visible",
        )
        runCatching { store.close() }
    }
}

/** Awaits the published count reaching [expected]; null means it never did. */
private suspend fun awaitCount(published: List<Int>, expected: Int): Int? =
    withTimeoutOrNull(2_000) {
        while (published.lastOrNull() != expected) delay(10)
        expected
    }

/** A JVM-fatal condition: `runCatchingCancellable` catches `Exception`, never `Error`. */
private class FatalThrowable(message: String) : Error(message)

data class ErrState(val count: Int = 0) : MVIState

sealed interface ErrIntent : MVIIntent {
    data object Fatal : ErrIntent
    data object Ordinary : ErrIntent
    data object Increment : ErrIntent
}

sealed interface ErrAction : MVIAction
