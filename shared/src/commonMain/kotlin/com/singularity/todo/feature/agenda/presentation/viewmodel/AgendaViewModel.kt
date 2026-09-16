package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * ViewModel for the Agenda screen.
 *
 * Data flow:
 * 1. [deps.currentUser.scopedUserId] → [flatMapLatest] → [watchTasks] with [TaskFilter.All]
 * 2. All tasks are evaluated against [definition] via [AgendaEvaluator.evaluate]
 * 3. [AgendaUiState.Loaded] → [state]
 * 4. One-shot events (task click → navigate) → [_events]
 *
 * @param deps Injected dependencies (task repository, current user, logger).
 * @param definition The agenda definition to evaluate. In MR1 this does not change
 *        at runtime; future MRs will support switching definitions.
 * @param scopeOverride For testing only — allows injecting a test CoroutineScope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModel(
    private val deps: AgendaDeps,
    definition: AgendaDefinition,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    /** Stable 'today' for the lifetime of this ViewModel. */
    private val today: LocalDate = todayInSystemZone()

    /** The definition being evaluated — stable reference. */
    val definition: AgendaDefinition = definition

    /** Title derived from the definition, for the Slot API. */
    val title: String get() = definition.title

    /** One-shot UI events. */
    private val _events = MutableSharedFlow<AgendaUiEvent>()
    val events: Flow<AgendaUiEvent> = _events.asSharedFlow()

    /**
     * Main state — watches all active tasks and evaluates them against [definition].
     * Produces [AgendaUiState.Loaded] with rendered sections.
     */
    val state: StateFlow<AgendaUiState> = deps.currentUser.scopedUserId
        .flatMapLatest { userId ->
            deps.taskRepo.watchTasks(userId, TaskFilter.All)
                .map { tasks ->
                    val sections = AgendaEvaluator.evaluate(tasks, definition, today)
                    AgendaUiState.Loaded(
                        sections = sections,
                        today = today,
                    )
                }
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            AgendaUiState.Loading,
        )

    /**
     * Processes a user [intent][AgendaIntent].
     */
    fun onIntent(intent: AgendaIntent) {
        when (intent) {
            is AgendaIntent.TaskClicked -> {
                scope.launch {
                    _events.emit(AgendaUiEvent.NavigateToTask(intent.taskId))
                }
            }

            is AgendaIntent.TaskCheckClicked -> {
                scope.launch {
                    deps.taskRepo.toggleComplete(intent.taskId)
                }
            }

            is AgendaIntent.TaskLongClicked -> {
                scope.launch {
                    _events.emit(AgendaUiEvent.ShowTaskContextMenu(intent.taskId))
                }
            }

            is AgendaIntent.TaskPinClicked -> {
                scope.launch {
                    deps.taskRepo.togglePinned(intent.taskId)
                }
            }

            is AgendaIntent.TaskDeleteClicked -> {
                scope.launch {
                    deps.taskRepo.softDelete(intent.taskId)
                }
            }

            is AgendaIntent.TaskExpandClicked -> {
                scope.launch {
                    _events.emit(AgendaUiEvent.ExpandTask(intent.taskId))
                }
            }
        }
    }
}
