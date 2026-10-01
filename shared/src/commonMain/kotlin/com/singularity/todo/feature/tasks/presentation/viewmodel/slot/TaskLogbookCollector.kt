package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Read-only collector that surfaces the task-logbook feed (notes attached to the current task).
 *
 * Not a [com.singularity.todo.core.ui.featureSlot.FeatureSlot] — it has no intent surface.
 * The only thing callers can do with a logbook entry is open it, which is a navigation
 * callback handled by the screen. Implementing `FeatureSlot` with an `onIntent` that ignores
 * every argument would advertise a mutation path that does not exist.
 *
 * Uses the canonical VM pattern (no [kotlin.flow.stateIn]) — [MutableStateFlow] + [scope.launch] collect.
 *
 * @param notesRepo mandatory dependency — logbook is part of task detail, not optional
 * @param scope drives the collection coroutine and is cancelled when the task detail screen leaves
 * @param taskFlow emits the current task; null while loading or after task is deleted
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLogbookCollector(
    private val notesRepo: NotesRepository,
    scope: AutoCloseableCoroutineScope,
    taskFlow: StateFlow<Task?>,
) {
    private val _state = MutableStateFlow<TaskLogbookState>(TaskLogbookState.Empty)
    val state: StateFlow<TaskLogbookState> = _state.asStateFlow()

    init {
        scope.launch {
            taskFlow.filterNotNull()
                .flatMapLatest { task ->
                    notesRepo.watchForTask(task.id)
                }
                .collect { notes ->
                    _state.value = if (notes.isEmpty()) {
                        TaskLogbookState.Empty
                    } else {
                        TaskLogbookState.Loaded(notes)
                    }
                }
        }
    }
}
