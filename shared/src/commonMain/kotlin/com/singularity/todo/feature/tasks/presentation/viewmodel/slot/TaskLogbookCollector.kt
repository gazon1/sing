package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.timetracking.domain.TimeTrackingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Read-only collector that surfaces the task logbook feed (notes + time entries).
 *
 * Not a [com.singularity.todo.core.ui.featureSlot.FeatureSlot] — it has no intent surface.
 *
 * Uses the canonical VM pattern (no [kotlin.flow.stateIn]) — [MutableStateFlow] + [scope.launch] collect.
 *
 * @param notesRepo Repository for notes attached to tasks.
 * @param timeTrackingRepo Repository for time entries.
 * @param scope Drives the collection coroutine and is cancelled when the task detail screen leaves.
 * @param taskFlow Emits the current task; null while loading or after task is deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLogbookCollector(
    private val notesRepo: NotesRepository,
    private val timeTrackingRepo: TimeTrackingRepository,
    scope: AutoCloseableCoroutineScope,
    taskFlow: StateFlow<Task?>,
) {
    private val _state = MutableStateFlow<TaskLogbookState>(TaskLogbookState.Empty)
    val state: StateFlow<TaskLogbookState> = _state.asStateFlow()

    init {
        scope.launch {
            taskFlow.filterNotNull()
                .flatMapLatest { task ->
                    combine(
                        notesRepo.watchForTask(task.id),
                        timeTrackingRepo.watchEntries(task.id),
                    ) { notes, entries ->
                        val noteEntries = notes.map { LogbookEntry.NoteEntry(it) }
                        val timeEntries = entries.map { LogbookEntry.TimeEntryRow(it) }
                        (noteEntries + timeEntries).sortedByDescending { entry ->
                            when (entry) {
                                is LogbookEntry.NoteEntry -> entry.note.createdAt
                                is LogbookEntry.TimeEntryRow -> entry.entry.startedAt
                            }
                        }
                    }
                }
                .collect { mergedEntries ->
                    _state.value = if (mergedEntries.isEmpty()) {
                        TaskLogbookState.Empty
                    } else {
                        TaskLogbookState.Loaded(mergedEntries)
                    }
                }
        }
    }
}
