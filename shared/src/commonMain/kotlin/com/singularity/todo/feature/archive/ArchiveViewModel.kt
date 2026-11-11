package com.singularity.todo.feature.archive

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.ReloadTrigger
import com.singularity.todo.core.coroutines.reloadOn
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.archive.domain.port.ArchiveRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface ArchiveUiState {
    data object Loading : ArchiveUiState

    /**
     * @param tasks the archived tasks, newest first.
     * @param eligibleCount the repository's archivable count: completed live tasks,
     *   the same predicate as the bulk write's `UPDATE`.
     * @param archiving a bulk archive is in flight. The action is unavailable while
     *   it runs, so one confirmation is one write.
     */
    data class Content(val tasks: List<Task>, val eligibleCount: Int = 0, val archiving: Boolean = false) :
        ArchiveUiState

    data class Error(val message: String) : ArchiveUiState
}

sealed interface ArchiveIntent : MviIntent {
    /** Move every completed live task to the archive. Dispatched after confirmation. */
    data object ArchiveCompletedTasks : ArchiveIntent

    data object Retry : ArchiveIntent
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
    private val taskRepo: TaskRepository,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ArchiveUiState, ArchiveIntent, ArchiveUiEvent>(
        initialState = ArchiveUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {
    private val archiving = MutableStateFlow(false)

    /**
     * The request to observe the archive again after a failure.
     *
     * The failure handling lives inside the attempt it applies to — see
     * [reloadOn] — so a failed load leaves the trigger armed and the Retry
     * intent starts a fresh subscription instead of adding a second collector.
     */
    private val reloadTrigger = ReloadTrigger()

    init {
        addCloseable(scope)
        scope.launch {
            reloadOn(
                trigger = reloadTrigger,
                onFailure = { e ->
                    // A flow that stops emitting is a defect, not a user-actionable error, so it
                    // goes to the reporter and the screen shows the message. Reporting from here
                    // rather than through catchTo: this is a Flow operator, not a suspend block
                    // that yields a Result.
                    // The failure crossed a channel hop, and a channel carries a *copy* of
                    // the throwable — the original is its cause, so the copy is unwrapped
                    // before it is reported.
                    val reported = e.cause ?: e
                    crashReporter.report(reported, ARCHIVE_OBSERVE_FAILED)
                    updateState {
                        ArchiveUiState.Error(
                            reported.message
                                ?: "Error",
                        )
                    }
                },
            ) {
                combine(
                    taskRepo.observeByFilter(TaskFilter.Trash),
                    taskRepo.observeArchivableCount(),
                    archiving,
                ) { tasks: List<Task>, eligible: Int, busy: Boolean ->
                    ArchiveUiState.Content(
                        tasks = tasks,
                        eligibleCount = eligible,
                        archiving = busy,
                    ) as ArchiveUiState
                }
            }.collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: ArchiveIntent) {
        when (intent) {
            ArchiveIntent.ArchiveCompletedTasks -> archiveCompletedTasks()
            ArchiveIntent.Retry -> reloadTrigger.reload()
        }
    }

    private fun archiveCompletedTasks() {
        // One confirmation is one write. The screen also disables the action while
        // this flag is set, but the dialog's confirm button has no such gate, so the
        // guard lives here where every dispatch meets it.
        if (archiving.value) return
        archiving.value = true
        // `catchTo` already routes a `Result.failure` from the repository: it folds
        // the outer result and calls `onFailure` on the inner one. An explicit
        // `getOrThrow()` here would only move the same failure to the other branch.
        catchTo("Failed to archive completed tasks", { msg -> emit(ArchiveUiEvent.Error(msg)) }) {
            archiveRepo.archiveCompletedTasks().onSuccess { count ->
                if (count > 0) {
                    val noun = if (count == 1) "task" else "tasks"
                    emit(ArchiveUiEvent.Archived("Moved $count $noun to archive"))
                } else {
                    // Another device can move the same work between the dialog and
                    // the confirmation. The write is idempotent, so the only honest
                    // answer is that there was nothing left to move.
                    emit(ArchiveUiEvent.NothingToArchive)
                }
            }
        }.invokeOnCompletion { archiving.value = false }
    }

    private companion object {
        /** Machine-shaped grouping key — it leaves the device. */
        const val ARCHIVE_OBSERVE_FAILED = "archive.observe_failed"
    }
}
