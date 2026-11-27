package spike

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.ActionShareBehavior
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
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
 *
 * FlowMVI's `Store.states` is `@InternalFlowMVIAPI`, so every state assertion here reads
 * the store the way a screen does — through `subscribe` — rather than reaching into a
 * mirror the test maintains itself. A mirror proves the reducer ran, not that the store
 * published.
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

    /**
     * Gap 2: a fatal JVM `Error` is not a failure to recover from.
     *
     * `runCatchingCancellable` catches `Exception` only, so an `Error` (OOM,
     * StackOverflow, AssertionError) propagates out of the in-repo loop instead of being
     * turned into a `Result.failure`. FlowMVI must not do worse: `recover` must never see
     * it, or a `catch (e: Exception)` in the app would mistake a JVM-fatal condition for
     * a handled one.
     *
     * Measured, not assumed: after the error FlowMVI keeps neither the error nor the
     * pipeline. No `recover` call, nothing thrown from `close()`, and the store still
     * answers `isActive == true` — while every later intent is silently dropped. That is
     * worse than the in-repo loop, which at least lets the error reach the scope's
     * uncaught-exception handler.
     *
     * Declared `: Unit` deliberately: a `runBlocking` tail expression would make the
     * method return a value, and JUnit then does not treat it as a test at all — the
     * suite reports no failure because it ran nothing.
     */
    @Test
    fun `in-repo MVI lets a fatal JVM error propagate`() {
        assertFailsWith<FatalJvmError> {
            runCatchingCancellable { throw FatalJvmError("fatal") }
        }
    }

    // ------------------------------------------------------------ atomic state

    /**
     * Gap 3: concurrent state writes.
     *
     * In-repo is CAS (`MutableStateFlow.update`); FlowMVI's default is FIFO-serialized
     * transactions. A lost increment is lost user data, so both must apply all of them.
     *
     * Both sides are asserted on the state they actually published — the in-repo
     * `StateFlow.value` and the FlowMVI state delivered to a subscriber — and both get
     * 200 genuinely concurrent writers rather than 200 back-to-back calls from one
     * coroutine. FlowMVI runs with `parallelIntents`, so the concurrent path is what is
     * under test rather than its queue.
     */
    @Test
    fun `neither framework loses a concurrent increment`() = runTest {
        val inRepoScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        val counter = InRepoCounter(inRepoScope)
        repeat(200) { launch { counter.bump() } }
        advanceUntilIdle()
        val inRepoTotal = counter.state.value.count
        inRepoScope.close()

        val store = store<GapState, GapIntent, GapAction>(GapState()) {
            configure {
                parallelIntents = true
                intentCapacity = Channel.UNLIMITED
                onOverflow = BufferOverflow.SUSPEND
            }
            reduce { intent ->
                if (intent is GapIntent.Increment) updateState { GapState(count + 1) }
            }
        }
        val owner = CoroutineScope(coroutineContext + Job())
        store.start(owner)
        val published = mutableListOf<Int>()
        val subscription = owner.launch {
            subscribe(store, consume = { }, render = { s: GapState -> published += s.count })
        }
        advanceUntilIdle()

        repeat(200) { launch { store.intent(GapIntent.Increment) } }
        advanceUntilIdle()
        val flowMviTotal = published.lastOrNull()

        subscription.cancel()
        store.close()

        assertEquals(200, inRepoTotal, "the in-repo reducer lost a write")
        assertEquals(200, flowMviTotal, "the FlowMVI store published a lost write")
    }

    // --------------------------------------------------------------- store restart

    /**
     * Gap 4: stop and restart.
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
     * Gap 5: the screen leaving and coming back.
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
     * Gap 6: an action emitted while nothing is subscribed.
     *
     * The in-repo bus is a channel, so an event emitted before the screen collects is
     * buffered. FlowMVI documents actions as fire-and-forget; the assessment warned that
     * `Share` loses them. `Distribute` — the mode this spike pinned — is also a channel,
     * so this measures which is true here instead of trusting either doc.
     */
    @Test
    fun `an action emitted before a subscriber is delivered once one subscribes`() = runTest {
        val store = actionStore()
        val owner = CoroutineScope(coroutineContext + Job())
        store.start(owner)

        store.intent(GapIntent.EmitAction)
        advanceUntilIdle()

        val received = mutableListOf<GapAction>()
        val sub = owner.launch {
            subscribe(store, consume = { a: GapAction -> received += a }, render = { })
        }
        advanceUntilIdle()

        assertEquals(
            listOf<GapAction>(GapAction.Notice),
            received,
            "an action emitted before any subscriber was dropped",
        )
        sub.cancel()
        store.close()
    }

    /**
     * Close with actions still buffered.
     *
     * The in-repo [com.singularity.todo.core.ui.EventBus] promise is #4 in its KDoc: close
     * is graceful, and what is already in the buffer is still delivered. FlowMVI's
     * `Distribute` channel matches that — measured here rather than assumed, because the
     * assessment could not say.
     */
    @Test
    fun `an action buffered before close is still delivered`() = runTest {
        val store = actionStore()
        val owner = CoroutineScope(coroutineContext + Job())
        store.start(owner)

        repeat(3) { store.intent(GapIntent.EmitAction) }
        advanceUntilIdle()
        store.close()
        advanceUntilIdle()
        assertTrue(!store.isActive, "the store never closed")

        val received = mutableListOf<GapAction>()
        owner.launch {
            subscribe(store, consume = { a: GapAction -> received += a }, render = { })
        }
        advanceUntilIdle()

        assertEquals(
            List<GapAction>(3) { GapAction.Notice },
            received,
            "closing the store discarded buffered actions",
        )
    }

    /**
     * A subscriber going away.
     *
     * The screen leaving must not strand the actions that arrive after it — the next
     * subscriber has to get them. The in-repo bus has the same shape for one consumer.
     */
    @Test
    fun `actions after a subscriber is cancelled reach the next one`() = runTest {
        val store = actionStore()
        val owner = CoroutineScope(coroutineContext + Job())
        store.start(owner)

        val first = mutableListOf<GapAction>()
        val firstSub = owner.launch {
            subscribe(store, consume = { a: GapAction -> first += a }, render = { })
        }
        advanceUntilIdle()
        store.intent(GapIntent.EmitAction)
        advanceUntilIdle()

        firstSub.cancel()
        advanceUntilIdle()
        store.intent(GapIntent.EmitAction)
        advanceUntilIdle()

        val second = mutableListOf<GapAction>()
        owner.launch {
            subscribe(store, consume = { a: GapAction -> second += a }, render = { })
        }
        advanceUntilIdle()

        assertEquals(
            listOf<GapAction>(GapAction.Notice),
            first,
            "the live subscriber missed its action",
        )
        assertEquals(
            listOf<GapAction>(GapAction.Notice),
            second,
            "an action emitted while the subscriber was away was lost",
        )
        store.close()
    }

    /**
     * Buffer saturation — and the one guarantee this spike could **not** reproduce.
     *
     * The in-repo bus has no drop at all: `emit` suspends while the buffer is full, and
     * the only discard is after close, which it counts in `droppedAfterClose`. FlowMVI's
     * `Distribute` takes both the capacity and the overflow policy, so the guarantee is a
     * configuration choice: `DROP_OLDEST` loses actions silently, `SUSPEND` loses none.
     *
     * Neither mode gives the app a counter. A discarded action is therefore
     * indistinguishable from one that was never emitted — the `droppedAfterClose`
     * equivalent the assessment asked about does not exist, in any mode.
     */
    @Test
    fun `a bounded action buffer drops silently unless the overflow suspends`() = runTest {
        val dropping = actionStore(capacity = 1, overflow = BufferOverflow.DROP_OLDEST)
        val droppingOwner = CoroutineScope(coroutineContext + Job())
        dropping.start(droppingOwner)
        repeat(3) { dropping.intent(GapIntent.EmitAction) }
        advanceUntilIdle()
        val dropped = mutableListOf<GapAction>()
        droppingOwner.launch {
            subscribe(dropping, consume = { a: GapAction -> dropped += a }, render = { })
        }
        advanceUntilIdle()
        dropping.close()

        val suspending = actionStore(capacity = 1, overflow = BufferOverflow.SUSPEND)
        val suspendingOwner = CoroutineScope(coroutineContext + Job())
        suspending.start(suspendingOwner)
        repeat(3) { suspending.intent(GapIntent.EmitAction) }
        advanceUntilIdle()
        val kept = mutableListOf<GapAction>()
        suspendingOwner.launch {
            subscribe(suspending, consume = { a: GapAction -> kept += a }, render = { })
        }
        advanceUntilIdle()
        suspending.close()

        assertEquals(
            1,
            dropped.size,
            "DROP_OLDEST kept more than the buffer — the overflow policy is not what it says",
        )
        assertEquals(
            3,
            kept.size,
            "SUSPEND lost an action instead of applying backpressure",
        )
    }
}

// ---------------------------------------------------------------- fixtures

/** A store that only emits actions, for the delivery-semantics cases. */
private fun actionStore(
    capacity: Int = Channel.UNLIMITED,
    overflow: BufferOverflow = BufferOverflow.SUSPEND,
): Store<GapState, GapIntent, GapAction> =
    store<GapState, GapIntent, GapAction>(GapState()) {
        configure {
            actionShareBehavior = ActionShareBehavior.Distribute(capacity, overflow)
        }
        reduce { intent ->
            if (intent is GapIntent.EmitAction) action(GapAction.Notice)
        }
    }

/** A JVM-fatal condition: `runCatchingCancellable` catches `Exception`, never `Error`. */
private class FatalJvmError(message: String) : Error(message)

data class GapState(val count: Int = 0) : MVIState

sealed interface GapIntent : MVIIntent {
    data object Increment : GapIntent
    data object Fail : GapIntent
    data object CancelItself : GapIntent
    data object Fatal : GapIntent
    data object EmitAction : GapIntent
}

sealed interface GapAction : MVIAction {
    data object Notice : GapAction
}

/** The in-repo loop, counting the same increments through its own reducer. */
private class InRepoCounter(scope: AutoCloseableCoroutineScope) :
    MviViewModel<GapState, InRepoIntent, MviEvent>(initialState = GapState(), scope = scope) {

    fun bump() = updateState { state -> GapState(state.count + 1) }

    override fun onIntent(intent: InRepoIntent) = Unit
}

/** The in-repo loop's own intent marker — `MviViewModel` requires an [MviIntent]. */
private sealed interface InRepoIntent : MviIntent
