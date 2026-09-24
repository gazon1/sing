package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.platform.todayFlow
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Agenda screen.
 *
 * Data flow:
 * 1. [deps.currentUser.scopedUserId] + [deps.clock.todayFlow()] → [flatMapLatest] → [watchTasks] with [TaskFilter.All]
 * 2. All tasks are evaluated against [definition] via [AgendaEvaluator.evaluate]
 * 3. [AgendaUiState.Loaded] → [state]
 * 4. One-shot events (task click → navigate) → [_events]
 *
 * Both user switch and date change trigger re-evaluation.
 * [distinctUntilChanged] suppresses redundant evaluations when only the instant changes.
 *
 * @param deps Injected dependencies (task repository, current user, clock, logger).
 * @param definition The agenda definition to evaluate. In MR1 this does not change
 *        at runtime; future MRs will support switching definitions.
 * @param scope CoroutineScope for all coroutine work. Tests pass `this` (TestScope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModel(
    private val deps: AgendaDeps,
    definition: AgendaDefinition,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** The definition being evaluated — stable reference. */
    val definition: AgendaDefinition = definition

    /** Title derived from the definition, for the Slot API. */
    val title: String get() = definition.title

    /** One-shot UI events. */
    private val _events = Channel<AgendaUiEvent>(Channel.BUFFERED)
    val events: Flow<AgendaUiEvent> = _events.receiveAsFlow()

    private val _state = MutableStateFlow<AgendaUiState>(AgendaUiState.Loading)
    val state: StateFlow<AgendaUiState> = _state

    init {
        scope.launch {
            todayFlow()
                .flatMapLatest { today ->
                    deps.taskRepo.observeByFilter(TaskFilter.All)
                        .map { tasks ->
                            val sections = AgendaEvaluator.evaluate(tasks, definition, today)
                            AgendaUiState.Loaded(
                                sections = sections,
                                today = today,
                            )
                        }
                }
                .collect { _state.value = it }
        }
    }

    /**
     * Processes a user [intent][AgendaIntent].
     */
    fun onIntent(intent: AgendaIntent) {
        when (intent) {
            is AgendaIntent.TaskClicked -> with(intent) {
                scope.launch {
                    _events.trySend(AgendaUiEvent.NavigateToTask(taskId))
                }
            }

            is AgendaIntent.TaskCheckClicked -> with(intent) {
                scope.launch {
                    deps.taskRepo.toggleComplete(taskId)
                }
            }

            is AgendaIntent.TaskLongClicked -> with(intent) {
                scope.launch {
                    _events.trySend(AgendaUiEvent.ShowTaskContextMenu(taskId))
                }
            }

            is AgendaIntent.TaskPinClicked -> with(intent) {
                scope.launch {
                    deps.taskRepo.togglePinned(taskId)
                }
            }

            is AgendaIntent.TaskDeleteClicked -> with(intent) {
                scope.launch {
                    deps.taskRepo.softDelete(taskId)
                }
            }

            is AgendaIntent.TaskExpandClicked -> with(intent) {
                scope.launch {
                    _events.trySend(AgendaUiEvent.ExpandTask(taskId))
                }
            }
        }
    }
}
