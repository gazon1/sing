package spike

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.dsl.subscribe
import pro.respawn.flowmvi.plugins.recover
import pro.respawn.flowmvi.plugins.reduce
import spike.scenario.ListIntent
import spike.scenario.ListState
import spike.scenario.TaskFilter
import spike.scenario.TaskRow
import spike.variantB.FlowMviScenario
import spike.variantB.buildTaskListStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The semantic gaps the compatibility assessment named, measured rather than read.
 * Where the frameworks are *supposed* to differ, the test asserts the difference; where
 * they are supposed to agree, it asserts agreement.
 */
class SemanticGapTest {

    // ---------------------------------------------------------------- cancellation

    /**
     * Gap 1: cancellation is not a failure.
     *
     * The in-repo loop routes failures through `runCatchingCancellable`, which re-throws
     * [CancellationException]. The question for FlowMVI is whether its recovery pipeline
     * offers a cancellation to the application's `recover` hook — where a plausible
     * `catch (e: Exception)` would swallow it and leave a cancelled coroutine running.
     */
    @Test
    fun `in-repo MVI rethrows cancellation instead of handling it`() {
        assertFailsWith<CancellationException> {
            runCatchingCancellable { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `FlowMVI does not offer cancellation to recovery`() = runTest {
        val recovered = mutableListOf<Throwable>()
        val store = store<GapState, GapIntent, GapAction>(GapState()) {
            recover { e ->
                recovered += e
                null
            }
            reduce { intent ->
                if (intent is GapIntent.CancelItself) throw CancellationException("from an intent")
            }
        }
        store.start(CoroutineScope(coroutineContext + Job()))
        store.intent(GapIntent.CancelItself)
        advanceUntilIdle()

        assertTrue(
            recovered.none { it is CancellationException },
            "cancellation was offered to the recover hook: $recovered",
        )
        store.close()
    }

    @Test
    fun `FlowMVI does offer an ordinary failure to recovery`() = runTest {
        val recovered = mutableListOf<Throwable>()
        val store = store<GapState, GapIntent, GapAction>(GapState()) {
            recover { e ->
                recovered += e
                null
            }
            reduce { intent ->
                if (intent is GapIntent.Fail) throw IllegalStateException("ordinary")
            }
        }
        store.start(CoroutineScope(coroutineContext + Job()))
        store.intent(GapIntent.Fail)
        advanceUntilIdle()

        assertEquals(1, recovered.size, "an ordinary failure must reach recovery")
        store.close()
    }

    // ------------------------------------------------------------ atomic state

    /**
     * Gap 2: concurrent state writes.
     *
     * In-repo is CAS (`MutableStateFlow.update`); FlowMVI's default is FIFO-serialized
     * transactions. A lost increment is lost user data, so both must apply all of them.
     */
    @Test
    fun `neither framework loses a concurrent increment`() = runTest {
        val inRepoScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        val counter = InRepoCounter(inRepoScope)
        repeat(200) { counter.bump() }
        advanceUntilIdle()
        val inRepoTotal = counter.seen.count
        inRepoScope.close()

        val mirrored = java.util.concurrent.atomic.AtomicInteger()
        val store = store<GapState, GapIntent, GapAction>(GapState()) {
            reduce { intent ->
                if (intent is GapIntent.Increment) {
                    updateState { GapState(count + 1) }
                    mirrored.incrementAndGet()
                }
            }
        }
        val owner = CoroutineScope(coroutineContext + Job())
        store.start(owner)
        repeat(200) { store.intent(GapIntent.Increment) }
        advanceUntilIdle()
        val flowMviTotal = mirrored.get()
        store.close()

        assertEquals(200, inRepoTotal, "the in-repo reducer lost a write")
        assertEquals(200, flowMviTotal, "the FlowMVI reducer lost a write")
    }

    // --------------------------------------------------------------- store restart

    /**
     * Gap 3: stop and restart.
     *
     * In-repo ownership is `addCloseable(scope)`: once cleared, the ViewModel is gone and
     * a new instance must be built. A FlowMVI store can be stopped and started again and
     * resumes from its own state — a capability the in-repo loop has no equivalent for,
     * and the reason the two lifecycles are not interchangeable.
     */
    @Test
    fun `FlowMVI retains state across stop and restart`() = runTest {
        val store = buildTaskListStore(FakeTaskSource())
        val first = CoroutineScope(coroutineContext + Job())
        store.start(first)
        val scenario = FlowMviScenario(store, first)
        advanceUntilIdle()
        scenario.intent(ListIntent.SetFilter(TaskFilter.Active))
        advanceUntilIdle()
        val before = scenario.rendered()

        store.close()
        advanceUntilIdle()
        scenario.unsubscribe()

        val second = CoroutineScope(coroutineContext + Job())
        store.start(second)
        advanceUntilIdle()
        // A new subscriber receives the retained state asynchronously — the store holds
        // it, the delivery does not happen at subscription time. That is a real
        // difference from a `StateFlow`, whose new collector reads `.value` immediately.
        val restarted = FlowMviScenario(store, second)
        advanceUntilIdle()
        val after = restarted.rendered()

        assertEquals(before, after, "a restarted store resumes from its previous state")
        store.close()
    }

    // ----------------------------------------------------------------- leave/return

    /**
     * Gap 4: the screen leaving and coming back.
     *
     * The store keeps running while the subscriber is away (by design — that is the
     * difference from a ViewModel scope). The returning screen must therefore see the
     * change it missed rather than a stale list.
     */
    @Test
    fun `a returning subscriber sees what changed while it was away`() = runTest {
        val source = FakeTaskSource()
        val store = buildTaskListStore(source)
        val owner = CoroutineScope(coroutineContext + Job())
        store.start(owner)
        val scenario = FlowMviScenario(store, owner)
        advanceUntilIdle()
        scenario.rendered()

        scenario.unsubscribe()
        advanceUntilIdle()

        source.externalInsert(TaskRow("9", "While away", completed = false))
        advanceUntilIdle()

        scenario.resubscribe()
        advanceUntilIdle()

        val back = scenario.rendered() as ListState.Ready
        assertTrue(back.rows.any { it.id == "9" }, "stale list after returning: ${back.rows}")
        scenario.unsubscribe()
        store.close()
    }

    // ------------------------------------------------- Action delivery semantics

    /**
     * Gap 5: an action emitted while nothing is subscribed.
     *
     * The in-repo bus is a channel, so an event emitted before the screen collects is
     * buffered. FlowMVI documents actions as fire-and-forget; the assessment warned that
     * `Share` loses them. `Distribute` — the mode this spike pinned — is also a channel,
     * so this measures which is true here instead of trusting either doc.
     */
    @Test
    fun `an action emitted before a subscriber is delivered once one subscribes`() = runTest {
        val store = store<GapState, GapIntent, GapAction>(GapState()) {
            reduce { intent ->
                if (intent is GapIntent.EmitAction) action(GapAction.Notice)
            }
        }
        val owner = CoroutineScope(coroutineContext + Job())
        store.start(owner)

        store.intent(GapIntent.EmitAction)
        advanceUntilIdle()

        val received = mutableListOf<GapAction>()
        val sub = owner.launch {
            subscribe(
                store = store,
                consume = { action: GapAction -> received += action },
                render = { },
            )
        }
        advanceUntilIdle()

        assertTrue(
            received.contains(GapAction.Notice),
            "an action emitted before any subscriber was dropped: $received",
        )
        sub.cancel()
        store.close()
    }
}

// ---------------------------------------------------------------- fixture types

data class GapState(val count: Int = 0) : MVIState

sealed interface GapIntent : MVIIntent {
    data object Increment : GapIntent
    data object Fail : GapIntent
    data object CancelItself : GapIntent
    data object EmitAction : GapIntent
}

sealed interface GapAction : MVIAction {
    data object Notice : GapAction
}

/** The in-repo loop, counting the same increments through its own reducer. */
private class InRepoCounter(scope: AutoCloseableCoroutineScope) :
    MviViewModel<GapState, InRepoIntent, MviEvent>(initialState = GapState(), scope = scope) {

    var seen: GapState = GapState()

    fun bump() {
        updateState { state ->
            val next = GapState(state.count + 1)
            seen = next
            next
        }
    }

    override fun onIntent(intent: InRepoIntent) = Unit
}

/** The in-repo loop's own intent marker — `MviViewModel` requires an [MviIntent]. */
private sealed interface InRepoIntent : MviIntent
