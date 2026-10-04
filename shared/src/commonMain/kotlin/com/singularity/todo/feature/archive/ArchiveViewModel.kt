package com.singularity.todo.feature.archive

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.archive.domain.port.ArchiveRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface ArchiveUiState {
    data object Loading : ArchiveUiState
    data class Content(val tasks: List<Task>, val refreshing: Boolean = false) : ArchiveUiState
    data class Error(val message: String) : ArchiveUiState
}

sealed interface ArchiveIntent : MviIntent {
    data object Refresh : ArchiveIntent
}

/**
 * Archive screen ViewModel (soft-deleted tasks).
 *
 * Owns: archived task list.
 * Triggers: restore task, permanently delete task.
 * One-shot events: [ArchiveUiEvent.Archived], [ArchiveUiEvent.Error].
 *
 * @see ArchiveUiState
 * @see ArchiveIntent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val archiveRepo: ArchiveRepository,
    taskRepo: TaskRepository,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ArchiveUiState, ArchiveIntent, ArchiveUiEvent>(
        initialState = ArchiveUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    private val refreshing = MutableStateFlow(false)

    init {
        addCloseable(scope)
        scope.launch {
            combine(
                taskRepo.observeByFilter(TaskFilter.Trash),
                refreshing,
            ) { tasks: List<Task>, r: Boolean ->
                ArchiveUiState.Content(tasks, refreshing = r) as ArchiveUiState
            }.catch { e ->
                // A flow that stops emitting is a defect, not a user-actionable error, so it
                // goes to the reporter and the screen shows the message. Reporting from here
                // rather than through catchTo: this is a Flow operator, not a suspend block
                // that yields a Result.
                crashReporter.report(e, ARCHIVE_OBSERVE_FAILED)
                updateState {
                    ArchiveUiState.Error(
                        e.message
                            ?: "Error",
                    )
                }
            }
                .collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: ArchiveIntent) {
        when (intent) {
            ArchiveIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        refreshing.value = true
        catchTo("Failed to refresh archive", { msg -> emit(ArchiveUiEvent.Error(msg)) }) {
            archiveRepo.archiveCompletedTasks().onSuccess { count ->
                if (count > 0) emit(ArchiveUiEvent.Archived("Moved $count tasks to archive"))
            }
        }.invokeOnCompletion { refreshing.value = false }
    }

    private companion object {
        /** Machine-shaped grouping key — it leaves the device. */
        const val ARCHIVE_OBSERVE_FAILED = "archive.observe_failed"
    }
}
