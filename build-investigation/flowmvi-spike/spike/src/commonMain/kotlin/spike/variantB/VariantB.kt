package spike.variantB

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.dsl.subscribe
import pro.respawn.flowmvi.plugins.asyncInit
import pro.respawn.flowmvi.plugins.reduce
import spike.scenario.ListEvent
import spike.scenario.ListIntent
import spike.scenario.ListState
import spike.scenario.TaskFilter
import spike.scenario.TaskListScenario
import spike.scenario.TaskRow
import spike.scenario.TaskSource

/**
 * Variant B: FlowMVI 3.2.1, configured to what the compatibility assessment said a fair
 * comparison needs — sequential intents, an unbounded queue that suspends instead of
 * dropping, the default atomic state strategy and `Distribute` actions — rather than to
 * its bare defaults, which drop intents (`DROP_OLDEST`) and bound the actions.
 */
data class FlowMviListState(val value: ListState) : MVIState

sealed interface FlowMviIntent : MVIIntent {
    data class Changed(val payload: ListIntent) : FlowMviIntent
}

sealed interface FlowMviAction : MVIAction {
    data class Message(val text: String) : FlowMviAction
}

fun buildTaskListStore(
    source: TaskSource,
): Store<FlowMviListState, FlowMviIntent, FlowMviAction> =
    store(FlowMviListState(ListState.Loading)) {
        configure {
            parallelIntents = false
            intentCapacity = Channel.UNLIMITED
            onOverflow = BufferOverflow.SUSPEND
        }

        // `asyncInit`, not `init`: `init` blocks the store's startup until its block
        // returns, and `watch` never returns — so `init` here would leave the store
        // permanently starting and not one intent would ever be processed. Measured:
        // with `init`, filter/completion intents were silently not applied.
        asyncInit {
            source.watch { rows ->
                updateState {
                    val filter = (value as? ListState.Ready)?.filter ?: TaskFilter.All
                    FlowMviListState(ListState.Ready(rows = rows.forFilter(filter), filter = filter))
                }
            }
        }

        reduce { incoming ->
            when (val payload = (incoming as FlowMviIntent.Changed).payload) {
                ListIntent.Refresh -> Unit
                is ListIntent.SetFilter -> {
                    val filter = payload.filter
                    updateState { withFilter(filter) }
                }
                is ListIntent.Complete -> source.complete(payload.id).fold(
                    onSuccess = { },
                    onFailure = { e -> action(FlowMviAction.Message(e.message ?: "complete failed")) },
                )
            }
        }
    }

/**
 * The scenario facade.
 *
 * `Store.states` is `@InternalFlowMVIAPI`, so the supported way to observe a store is
 * `subscribe` — which is also what a Compose screen calls. The subscription mirrors the
 * rendered state into a [StateFlow] [rendered] returns, and [unsubscribe]/[resubscribe]
 * exist so the leave/return case is a real unsubscription rather than a claim.
 */
class FlowMviScenario(
    private val store: Store<FlowMviListState, FlowMviIntent, FlowMviAction>,
    private val scope: CoroutineScope,
) : TaskListScenario {
    override val name: String = "FlowMVI 3.2.1"

    private val mirror: MutableStateFlow<ListState> = MutableStateFlow<ListState>(ListState.Loading)
    private val rendered: StateFlow<ListState> = mirror.asStateFlow()
    private var subscription: Job? = null

    /** The effect layer's inbox, part of the contract — see [TaskListScenario.events]. */
    private val received = mutableListOf<ListEvent>()

    init {
        resubscribe()
    }

    fun resubscribe() {
        subscription?.cancel()
        subscription = scope.launch {
            subscribe(
                store = store,
                consume = { action: FlowMviAction ->
                    when (action) {
                        is FlowMviAction.Message -> received += ListEvent.ShowMessage(action.text)
                    }
                },
                render = { state: FlowMviListState -> mirror.value = state.value },
            )
        }
    }

    /** The screen going away: the subscriber stops, the store keeps running. */
    fun unsubscribe() {
        subscription?.cancel()
        subscription = null
    }

    override fun intent(intent: ListIntent) = store.intent(FlowMviIntent.Changed(intent))

    override fun rendered(): ListState = mirror.value

    override fun events(): List<ListEvent> = received.toList()
}

private fun List<TaskRow>.forFilter(filter: TaskFilter): List<TaskRow> = when (filter) {
    TaskFilter.All -> this
    TaskFilter.Active -> filterNot { it.completed }
    TaskFilter.Completed -> filter { it.completed }
}

private fun FlowMviListState.withFilter(filter: TaskFilter): FlowMviListState {
    val ready = value as? ListState.Ready ?: return this
    return FlowMviListState(ready.copy(filter = filter, rows = ready.rows.forFilter(filter)))
}
