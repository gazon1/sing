package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Notes and tasks that link to this task through the `task://` URL scheme.
 *
 * **Not a `FeatureSlot`.** It has no intent surface — the only thing the screen can do with
 * a backlink is open it, which is a navigation callback handled by the screen. Implementing
 * `FeatureSlot` with an `onIntent` that ignores every argument would advertise a mutation
 * path that does not exist, so this stays a plain collector.
 *
 * The previous implementation kept these two lists in `MutableStateFlow` fields and read
 * them with `.value` inside the state-building `combine`, which did not depend on them. The
 * result was that loading a backlink never recomputed the assembled state, and the card
 * stayed empty. Here the state is a `StateFlow` the coordinator merges, so an arrival
 * re-emits like any other input.
 */
class TaskBacklinksCollector(
    private val deps: TaskDetailDeps,
    private val scope: AutoCloseableCoroutineScope,
    taskFlow: StateFlow<Task?>,
) {
    private val _state = MutableStateFlow(TaskBacklinksState())
    val state: StateFlow<TaskBacklinksState> = _state.asStateFlow()

    init {
        scope.launch {
            taskFlow.filterNotNull().collect { task ->
                val linkRepo = deps.linkRepo
                _state.value = if (linkRepo == null) {
                    TaskBacklinksState()
                } else {
                    TaskBacklinksState(
                        notes = linkRepo.getNotesLinkingToTask(task.id.value),
                        tasks = linkRepo.getBacklinkTasks(task.id.value),
                    )
                }
            }
        }
    }
}
