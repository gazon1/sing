package spike.variantA

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import spike.scenario.ListEvent
import spike.scenario.ListIntent
import spike.scenario.ListState
import spike.scenario.TaskFilter
import spike.scenario.TaskListScenario
import spike.scenario.TaskRow
import spike.scenario.TaskSource

/** The app's own intent/event markers, so the variant is the production shape. */
sealed interface ListVmIntent : MviIntent {
    data class Changed(val payload: ListIntent) : ListVmIntent
}

sealed interface ListVmEvent : MviEvent {
    data class Message(val text: String) : ListVmEvent
}

/**
 * Variant A: the in-repo `MviViewModel`, subclassed exactly as a production feature
 * ViewModel is. The only addition is [asScenario], which lets the shared suite drive
 * it without naming the framework.
 */
class InRepoTaskListViewModel(
    private val source: TaskSource,
    private val scope: AutoCloseableCoroutineScope,
) : MviViewModel<ListState, ListVmIntent, ListVmEvent>(
        initialState = ListState.Loading,
        scope = scope,
    ) {

    init {
        // Production shape: background work through catchTo, so a repository failure
        // becomes an event instead of an uncaught exception on the scope.
        watchSource()
    }

    private fun watchSource() = catchTo("list_watch_failed", onError = ::report) {
        source.watch { rows ->
            updateState { current ->
                val filter = (current as? ListState.Ready)?.filter ?: TaskFilter.All
                ListState.Ready(rows = rows.forFilter(filter), filter = filter)
            }
        }
        Result.success(Unit)
    }

    override fun onIntent(intent: ListVmIntent) {
        when (intent) {
            is ListVmIntent.Changed -> handle(intent.payload)
        }
    }

    private fun handle(payload: ListIntent) {
        when (payload) {
            ListIntent.Refresh -> Unit // the source flow is live; nothing to re-fetch
            is ListIntent.SetFilter -> updateState { current ->
                if (current is ListState.Ready) {
                    current.copy(filter = payload.filter, rows = current.rows.forFilter(payload.filter))
                } else {
                    current
                }
            }
            is ListIntent.Complete -> catchTo("list_complete_failed", onError = ::report) {
                source.complete(payload.id)
            }
        }
    }

    private suspend fun report(message: String) {
        emit(ListVmEvent.Message(message))
    }

    fun asScenario(): TaskListScenario = ScenarioOf(
        vm = this,
        scope = scope,
    )
}

private class ScenarioOf(
    private val vm: InRepoTaskListViewModel,
    scope: CoroutineScope,
) : TaskListScenario {
    override val name: String = "in-repo MviViewModel"

    private val received = mutableListOf<ListEvent>()

    /**
     * The screen's effect layer: collects the one-shot events.
     *
     * Started here rather than left to the caller, because [events] is part of the
     * contract — a scenario whose event channel nobody collects reports "no events" for
     * both success and failure, which is the same blindness as not looking.
     */
    init {
        scope.launch {
            vm.events.collect { event ->
                when (event) {
                    is ListVmEvent.Message -> received += ListEvent.ShowMessage(event.text)
                }
            }
        }
    }

    override fun intent(intent: ListIntent) = vm.onIntent(ListVmIntent.Changed(intent))

    override fun rendered(): ListState = vm.state.value

    override fun events(): List<ListEvent> = received.toList()
}

private fun List<TaskRow>.forFilter(filter: TaskFilter): List<TaskRow> = when (filter) {
    TaskFilter.All -> this
    TaskFilter.Active -> filterNot { it.completed }
    TaskFilter.Completed -> filter { it.completed }
}
