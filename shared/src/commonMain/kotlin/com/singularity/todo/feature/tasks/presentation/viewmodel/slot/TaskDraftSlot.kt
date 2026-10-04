package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDraftIntent
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailDraft
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailDraftState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Inline editing of the task title and description.
 *
 * Keystrokes update the draft immediately so the text field stays responsive, while the
 * write to the repository is debounced. The two pipelines are separate on purpose:
 * [TaskDetailIntent.Domain.TitleChanged] pushes into the draft and into the edit flow,
 * the flow drives persistence, and the draft is what the screen renders.
 *
 * ## Why the task comes from a collector, not from the transform
 *
 * The persistence pipeline pairs the debounced text with the *current* task so a write
 * carries every field the user did not touch. The pairing is done in a `collect { }` block:
 * `combine` would re-pair on every emission of the task flow, and a write inside a
 * `combine` transform re-fires on emissions of the other side of the pair.
 *
 * ## Why [seed] is public
 *
 * Seeding is a one-time initialisation from the loaded task, not a projection of the
 * flows, so it cannot live in the `combine` chain. The coordinator calls [seed] from its
 * task collector — once per task change rather than once per unrelated child update.
 * [TaskDetailDraftState.seed] is idempotent, so a task arriving again never clobbers an
 * in-progress edit.
 */
@OptIn(FlowPreview::class)
class TaskDraftSlot(
    private val core: TaskCoreDeps,
    private val context: TaskContextDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
) : FeatureSlot<TaskDetailDraft, TaskDraftIntent> {

    private val draftState = TaskDetailDraftState()
    override val state: StateFlow<TaskDetailDraft> = draftState.state

    // replay = 0: a keystroke is only worth persisting once the user pauses.
    // buffer = 4: absorbs a burst across frames without dropping the latest value.
    private val titleEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    private val descriptionEdits = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)

    init {
        val debounceMs = context.debounceMs.milliseconds

        scope.launch {
            combine(
                taskFlow.filterNotNull(),
                titleEdits.debounce(debounceMs),
            ) { task, title -> task to title }
                .collect { (task, title) ->
                    core.updateTask(task.id) { it.copy(title = title) }
                        .onFailure { onError("Save failed") }
                }
        }

        scope.launch {
            combine(
                taskFlow.filterNotNull(),
                descriptionEdits.debounce(debounceMs),
            ) { task, description -> task to description }
                .collect { (task, description) ->
                    core.updateTask(task.id) { it.copy(description = description.ifBlank { null }) }
                        .onFailure { onError("Save failed") }
                }
        }
    }

    /**
     * Initialises the draft from the loaded task.
     *
     * Idempotent — a second call while the user is editing is ignored, so a remote task
     * update arriving mid-edit does not reset the text field.
     */
    fun seed(title: String, description: String) = draftState.seed(title, description)

    override fun onIntent(intent: TaskDraftIntent) {
        when (intent) {
            is TaskDetailIntent.Domain.TitleChanged -> {
                draftState.setTitle(intent.title)
                titleEdits.tryEmit(intent.title)
            }

            is TaskDetailIntent.Domain.DescriptionChanged -> {
                draftState.setDescription(intent.description)
                descriptionEdits.tryEmit(intent.description)
            }
        }
    }
}
