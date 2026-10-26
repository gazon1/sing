package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskChildrenDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskEntityIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Task metadata: dates, priority, project, tags, kind, pin, recurrence, dependencies.
 *
 * Owns the two reads that are not the task row itself — the task's project and the full
 * tag catalogue — plus the candidate list backing the dependency picker.
 *
 * Mutations go through [TaskCoreDeps.updateTask] against the task from [taskFlow], never
 * against a snapshot the screen last observed. That is what keeps a concurrent remote edit
 * from being reverted by a field the user did not touch. A field that describes the
 * *work* rather than the occurrence goes through [TaskCoreDeps.updateSeries] instead —
 * see [sharedFieldOf] for which fields those are.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEntitySlot(
    private val taskId: TaskId,
    private val core: TaskCoreDeps,
    private val children: TaskChildrenDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
) : FeatureSlot<TaskEntityState, TaskEntityIntent> {

    private val _state = MutableStateFlow(TaskEntityState())
    override val state: StateFlow<TaskEntityState> = _state.asStateFlow()

    init {
        scope.launch {
            val projectFlow = taskFlow.flatMapLatest { task ->
                task?.projectId?.let { children.projectsRepo.observe(it) }
                    ?: flowOf(null)
            }
            // The picker must not offer the task as its own dependency, nor trashed tasks.
            val availableFlow = core.taskRepo.observeByFilter(TaskFilter.All)
                .map { all -> all.filter { !it.isTrashed && it.id != taskId } }
            val allTags = children.tagsRepo.observeAll()
            // The members of the series, if the task belongs to one. A task that
            // stands alone has no series, and the flow says so without a special
            // case elsewhere.
            val seriesFlow = taskFlow.flatMapLatest { task ->
                task?.seriesId?.let { core.taskRepo.observeSeries(it) }
                    ?: flowOf(emptyList())
            }

            combine(
                projectFlow,
                allTags,
                availableFlow,
                taskFlow,
                seriesFlow,
            ) { project, tags, available, task, series ->
                val taskTags = task?.tags.orEmpty()
                TaskEntityState(
                    project = project,
                    tags = tags.filter { it.id in taskTags },
                    availableTasks = available,
                    seriesMembers = series,
                )
            }.collect { _state.value = it }
        }
    }

    override fun onIntent(intent: TaskEntityIntent) {
        val task = taskFlow.value
            ?: return
        when (intent) {
            is TaskDetailIntent.Domain.SetDueDate -> mutate(task, intent) { copy(dueDate = intent.date) }

            is TaskDetailIntent.Domain.SetDueTime -> mutate(task, intent) { copy(dueTime = intent.time) }

            is TaskDetailIntent.Domain.SetStartDate -> mutate(task, intent) { copy(startDate = intent.date) }

            is TaskDetailIntent.Domain.SetStartTime -> mutate(task, intent) { copy(startTime = intent.time) }

            is TaskDetailIntent.Domain.SetPriority -> mutate(task, intent) { copy(priority = intent.priority) }

            is TaskDetailIntent.Domain.SetWorkflowState ->
                mutate(task, intent) { copy(workflowState = intent.state) }

            is TaskDetailIntent.Domain.SetProject -> mutate(task, intent) { copy(projectId = intent.projectId) }

            is TaskDetailIntent.Domain.SetTags -> setTags(task, intent.tagIds)

            is TaskDetailIntent.Domain.RemoveTag -> setTags(task, task.tags - intent.tagId)

            is TaskDetailIntent.Domain.SetKind -> mutate(
                task,
                intent,
                "Failed to set kind",
            ) { copy(kind = intent.kind) }

            // `data object` branches are equality checks, not type checks, so the
            // smart cast does not happen: the object is named explicitly.
            TaskDetailIntent.Domain.ToggleSomeday -> mutate(
                task,
                TaskDetailIntent.Domain.ToggleSomeday,
                "Failed to set someday",
            ) { copy(someday = !someday) }

            TaskDetailIntent.Domain.TogglePinned -> mutate(
                task,
                TaskDetailIntent.Domain.TogglePinned,
            ) { copy(isPinned = !isPinned) }

            is TaskDetailIntent.Domain.SetRecurrence -> setRecurrence(task, intent.spec)

            is TaskDetailIntent.Domain.SetDependencies -> setDependencies(task, intent)

            is TaskDetailIntent.Domain.SetDependencyRelations -> setDependencyRelations(task, intent)

            is TaskDetailIntent.Domain.SetEstimate -> mutate(
                task,
                intent,
                "Failed to set estimate",
            ) { copy(estimateMinutes = intent.minutes) }

            TaskDetailIntent.Domain.ApplyToAllOccurrences -> applyToAllOccurrences(task)

            TaskDetailIntent.Domain.DetachFromSeries -> detachFromSeries(task)
        }
    }

    /**
     * Writes the tags of the task.
     *
     * The tags live in a cross-ref table, and the path that writes them is the
     * one that knows what they should be: `setTags`. A write that carried them
     * only in the task would leave the database saying something else about the
     * work than the screen does — and the push would carry the emptiness of a
     * read as the claim that the work has no tags.
     *
     * A series is handled by the same path: the tags are a shared field of a
     * series, and `setTags` repairs the series around the task it changed.
     */
    private fun setTags(task: Task, tagIds: List<TagId>) = scope.launch {
        core.taskRepo.setTags(task.id, tagIds)
            .onFailure { onError("Failed to set tags") }
    }

    /**
     * Sets the rule on the task.
     *
     * A task that is already in a series changes the rule of the whole series —
     * the recurrence is a shared field, so the write goes through
     * [TaskCoreDeps.updateSeries] — and the series is re-planned so the future
     * occurrences follow the new rule.
     *
     * A task that **stands alone** and is given a rule becomes a recurring series:
     * the row is the current occurrence and the rule's future dates are materialised
     * beside it (FR-001). Removing the rule from a task that stands alone is an
     * ordinary field write — there is nothing to re-plan.
     */
    private fun setRecurrence(
        task: Task,
        spec: com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec?,
    ) = scope.launch {
        val seriesId = task.seriesId
        when {
            seriesId != null -> core.updateSeries.updateSeries(seriesId) { it.copy(recurrence = spec) }
                .onFailure { onError("Failed to set recurrence") }

            spec != null -> core.startRecurringSeries(task.id, spec)
                .onFailure { onError("Failed to set recurrence") }

            else -> core.updateTask(task.id) { it.copy(recurrence = null) }
                .onFailure { onError("Failed to set recurrence") }
        }
    }

    /**
     * Writes the change to the whole series when the field is a shared one, and
     * to this occurrence alone when it is not.
     *
     * The decision comes from [sharedFieldOf] — the one place that knows which
     * fields describe the work rather than the occurrence — and not from the
     * call sites, so a field added to [TaskSharedField] cannot be written to one
     * occurrence by accident.
     */
    private fun mutate(
        task: Task,
        intent: TaskDetailIntent.Domain,
        error: String = "Save failed",
        transform: Task.() -> Task,
    ) = scope.launch {
        val seriesId = task.seriesId
        if (seriesId != null && sharedFieldOf(intent) != null) {
            core.updateSeries.updateSeries(seriesId) { it.transform() }
                .onFailure { onError(error) }
        } else {
            core.updateTask(task.id) { it.transform() }
                .onFailure { onError(error) }
        }
    }

    /**
     * Copies the time of day and the pin of this occurrence to every other
     * member of the series.
     *
     * The two are the only occurrence-scoped fields where "the same for all of
     * them" makes sense: a reminder that fires at 09:00 should fire at 09:00 on
     * every date, and a pinned work should be pinned on every date. The date
     * itself is not offered — every occurrence has its own, by definition.
     */
    private fun applyToAllOccurrences(task: Task) = scope.launch {
        val seriesId = task.seriesId ?: return@launch
        core.updateSeries.updateSeries(seriesId) {
            it.copy(dueTime = task.dueTime, isPinned = task.isPinned)
        }.onFailure { onError("Failed to apply to all occurrences") }
    }

    /**
     * Takes this occurrence out of its series.
     *
     * The occurrence keeps its date and becomes a task that stands alone; the
     * series it left keeps its id while it still has more than one member.
     */
    private fun detachFromSeries(task: Task) = scope.launch {
        core.detachFromSeries(task.id)
            .onFailure { onError("Failed to detach the occurrence") }
    }

    private fun setDependencies(task: Task, intent: TaskDetailIntent.Domain.SetDependencies) = scope.launch {
        core.taskRepo.setDependencies(task.id, intent.blockedBy)
            .onFailure { onError("Failed to set dependencies") }
    }

    /**
     * Turns the picker's "desired relations" into the smallest set of edge writes.
     *
     * The picker returns the whole map, because that is what the user edited. The
     * repository's write is per edge and per verb, because an edge is one row with a
     * verb on it — so the diff is what decides whether a click removed an edge,
     * added one, or changed what it means. Writing the map as a bulk replacement
     * would have to delete every verb it did not mention, and the follow-up edges
     * the picker does not show would be the first casualties.
     */
    private fun setDependencyRelations(
        task: Task,
        intent: TaskDetailIntent.Domain.SetDependencyRelations,
    ) = scope.launch {
        val current = task.blockedBy.associateWith { DependencyVerb.BLOCKS } +
            task.followsUp.associateWith { DependencyVerb.FOLLOWS_UP }
        val touched = current.keys + intent.relations.keys
        touched.forEach { depId ->
            val was = current[depId]
            val now = intent.relations[depId]
            val result = when {
                was == null && now != null ->
                    core.taskRepo.setDependency(task.id, depId, now, enabled = true)

                was != null && now == null ->
                    core.taskRepo.setDependency(task.id, depId, was, enabled = false)

                was != null && now != null && was != now -> {
                    core.taskRepo.setDependency(task.id, depId, was, enabled = false)
                    core.taskRepo.setDependency(task.id, depId, now, enabled = true)
                }

                else -> null
            }
            result?.onFailure { onError("Failed to set dependencies") }
        }
    }
}
