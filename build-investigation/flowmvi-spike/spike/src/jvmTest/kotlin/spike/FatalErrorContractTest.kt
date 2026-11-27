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
 * store keeps answering `isActive == true`, and every later intent is dropped. The error
 * is not fully swallowed, though: it resurfaces on an unrelated thread, and
 * `runTest` fails whichever test runs next with `UncaughtExceptionsBeforeTest`. That is
 * a process-wide effect, so containing it means containing the JVM — the probe's
 * `jvmTest` task is split by class name (see `build.gradle.kts`), and this class gets a
 * fork to itself.
 */
class FatalErrorContractTest {

    /**
     * After the error the store must stop reducing, must not offer the error to
     * `recover`, and must never claim to have been stopped cleanly.
     */
    @Test
    fun `FlowMVI does not offer a fatal JVM error to recovery`(): Unit = runBlocking {
        val recovered = mutableListOf<Throwable>()
        val published = mutableListOf<Int>()
        val store = store<ErrState, ErrIntent, ErrAction>(ErrState()) {
            // On the store's own context: the error escapes on the store's coroutines, and
            // neither the owner's handler nor this one observes it — asserted below.
            configure { coroutineContext = CoroutineExceptionHandler { _, _ -> } }
            recover { e ->
                recovered += e
                null
            }
            reduce { intent ->
                when (intent) {
                    ErrIntent.Fatal -> throw FatalThrowable("fatal from an intent")
                    ErrIntent.Increment -> updateState { ErrState(count + 1) }
                }
            }
        }
        val owner = CoroutineScope(
            SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
        )
        store.start(owner)
        owner.launch {
            subscribe(store, consume = { }, render = { s: ErrState -> published += s.count })
        }
        delay(200)

        store.intent(ErrIntent.Increment)
        assertEquals(1, awaitCount(published, 1), "the store never reduced a healthy intent")

        store.intent(ErrIntent.Fatal)
        delay(200)

        // The pipeline must not resume: that is the measured consequence of the error.
        store.intent(ErrIntent.Increment)
        val afterFatal = awaitCount(published, 2)

        assertTrue(
            recovered.none { it is FatalThrowable },
            "a fatal JVM error was offered to the recover hook: $recovered",
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
    data object Increment : ErrIntent
}

sealed interface ErrAction : MVIAction
