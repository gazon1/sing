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
 * Seeding is a projection of the loaded task onto the draft, not a projection of the
 * flows, so it cannot live in the `combine` chain. The coordinator calls [seed] from its
 * task collector — once per task change rather than once per unrelated child update.
 * [TaskDetailDraftState.seed] decides what the incoming task means: the echo of our own
 * save changes nothing, an in-progress edit is never clobbered, and a rewrite from
 * outside is taken and counted in the draft's revision.
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
    //
    // The flow carries the intent, not the bare text, because the intent is what
    // says whether the write belongs to the whole series or to this occurrence —
    // and that question has exactly one answer in the codebase.
    private val titleEdits =
        MutableSharedFlow<TaskDetailIntent.Domain.TitleChanged>(replay = 0, extraBufferCapacity = 4)
    private val descriptionEdits =
        MutableSharedFlow<TaskDetailIntent.Domain.DescriptionChanged>(replay = 0, extraBufferCapacity = 4)

    init {
        val debounceMs = context.debounceMs.milliseconds

        scope.launch {
            combine(
                taskFlow.filterNotNull(),
                titleEdits.debounce(debounceMs),
            ) { task, edit -> task to edit }
                .collect { (task, edit) ->
                    write(task, edit) { copy(title = edit.title) }
                }
        }

        scope.launch {
            combine(
                taskFlow.filterNotNull(),
                descriptionEdits.debounce(debounceMs),
            ) { task, edit -> task to edit }
                .collect { (task, edit) ->
                    write(task, edit) { copy(description = edit.description.ifBlank { null }) }
                }
        }
    }

    /**
     * Writes the edit to the whole series when the field is shared, and to this
     * occurrence alone when it is not.
     *
     * Title and description describe the *work*, so in a series they belong to
     * every member: a series whose three rows carry three titles says three
     * different things about the same job, and no rule says which is right.
     */
    private fun write(
        task: Task,
        intent: TaskDetailIntent.Domain,
        transform: Task.() -> Task,
    ) = scope.launch {
        val seriesId = task.seriesId
        if (seriesId != null && sharedFieldOf(intent) != null) {
            core.updateSeries.updateSeries(seriesId) { it.transform() }
                .onFailure { onError("Save failed") }
        } else {
            core.updateTask(task.id) { it.transform() }
                .onFailure { onError("Save failed") }
        }
    }

    /**
     * Seeds the draft from the loaded task.
     *
     * The seed decides what the incoming task means — see
     * [TaskDetailDraftState.seed]. A task arriving again is not a reason to
     * reset the text field: only a rewrite from outside, on a draft that holds
     * no unsaved edits, replaces the text.
     */
    fun seed(title: String, description: String) = draftState.seed(title, description)

    override fun onIntent(intent: TaskDraftIntent) {
        when (intent) {
            is TaskDetailIntent.Domain.TitleChanged -> {
                draftState.setTitle(intent.title)
                titleEdits.tryEmit(intent)
            }

            is TaskDetailIntent.Domain.DescriptionChanged -> {
                draftState.setDescription(intent.description)
                descriptionEdits.tryEmit(intent)
            }
        }
    }
}
