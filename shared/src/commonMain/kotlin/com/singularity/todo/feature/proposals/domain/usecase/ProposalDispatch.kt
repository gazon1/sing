package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.model.TagEditActor
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Executes a validated [ProposalPlan].
 *
 * Called only after a successful claim, against the row the claim read back.
 * This class is purely imperative — it has no routing logic of its own, only the
 * `when` over [ProposalPlan] variants.
 *
 * @param tasks For task writes.
 * @param tags For tag writes.
 * @param checklist For checklist item creation.
 * @param timeTracking For time entry creation.
 * @param notes For note writes.
 * @param deleteProject For project deletion.
 * @param clock For timestamps in subtask creation.
 */
class ProposalDispatch(
    private val tasks: TaskRepository,
    private val tags: TagsRepository,
    private val checklist: ChecklistRepository,
    private val timeTracking: TimeTrackingRepository,
    private val notes: NotesRepository,
    private val projects: ProjectsRepository,
    private val deleteProject: DeleteProjectUseCase,
    private val clock: Clock,
) {
    /**
     * Applies [plan].
     *
     * This is the only `when` over [ProposalPlan] in the codebase; adding a new
     * [ProposalPlan] variant makes the compiler point here.
     */
    suspend fun dispatch(plan: ProposalPlan, userId: UserId) {
        when (plan) {
            is ProposalPlan.WriteTask -> tasks.update(plan.task).getOrThrow()

            is ProposalPlan.AddTags -> {
                val current = tasks.getTagIds(plan.taskId).first().toSet()
                tasks.setTags(plan.taskId, (current + plan.tagIds).toList(), TagEditActor.AiProposal).getOrThrow()
            }

            is ProposalPlan.RemoveTags -> {
                val current = tasks.getTagIds(plan.taskId).first().toSet()
                tasks.setTags(plan.taskId, (current - plan.tagIds).toList(), TagEditActor.AiProposal).getOrThrow()
            }

            is ProposalPlan.SetTaskTags -> {
                // A set, not an addition: the proposal says the task carries these tags
                // and no others, so the current set is replaced rather than merged.
                tasks.setTags(plan.taskId, plan.tagIds.toList(), TagEditActor.AiProposal).getOrThrow()
            }

            is ProposalPlan.AddChecklistItems -> addChecklistItems(plan)

            is ProposalPlan.AddSubtasks -> addSubtasks(plan, userId)

            is ProposalPlan.AddTimeEntries -> addTimeEntries(plan, userId)

            is ProposalPlan.WriteNote -> {
                val updated = when (plan.value) {
                    is NoteValue.Title -> plan.note.copy(title = plan.value.value.trim())
                    is NoteValue.Body -> plan.note.copy(bodyHtml = plan.value.value)
                    is NoteValue.Summary -> plan.note.copy(bodyHtml = plan.value.value)
                }
                notes.upsert(updated)
            }

            is ProposalPlan.WriteProject -> {
                projects.update(plan.project).getOrThrow()
            }

            is ProposalPlan.CreateTask -> {
                tasks.create(plan.task).getOrThrow()
            }

            is ProposalPlan.CreateProject -> {
                projects.create(plan.project).getOrThrow()
            }

            is ProposalPlan.CreateNote -> {
                notes.create(plan.note).getOrThrow()
            }

            is ProposalPlan.DeleteNote -> {
                notes.archive(plan.noteId).getOrThrow()
            }

            is ProposalPlan.ExtractNoteActions -> extractNoteActions(plan, userId)

            is ProposalPlan.DeleteTask -> {
                tasks.softDelete(plan.delTaskId).getOrThrow()
            }

            is ProposalPlan.DeleteProject -> {
                deleteProject(plan.projectId).getOrThrow()
            }

            is ProposalPlan.WriteTag -> {
                tags.update(plan.tag).getOrThrow()
            }

            is ProposalPlan.CreateTag -> {
                tags.create(plan.tag).getOrThrow()
            }

            is ProposalPlan.DeleteTag -> {
                tags.delete(plan.tagId).getOrThrow()
            }
        }
    }

    /**
     * Adds the checklist rows a proposal asked for.
     *
     * All of them, or none of them: a row written before a later row fails would be
     * there twice on the next attempt, and the attempt is the user's — the change goes
     * back to waiting when the write fails. So the rows this call wrote are taken back
     * when a later row fails.
     */
    private suspend fun addChecklistItems(plan: ProposalPlan.AddChecklistItems) {
        val written = mutableListOf<ChecklistItemId>()
        val result = runCatchingCancellable {
            plan.texts.forEach { text ->
                written += checklist.addItem(plan.taskId.value, text).getOrThrow()
            }
        }
        if (result.isFailure) {
            val takenBack = written.map { checklist.delete(it) }
            throw failureWith(result, takenBack)
        }
    }

    /** Creates the subtasks a proposal asked for. All of them, or none — see [addChecklistItems]. */
    private suspend fun addSubtasks(plan: ProposalPlan.AddSubtasks, userId: UserId) {
        val written = mutableListOf<TaskId>()
        val result = runCatchingCancellable {
            plan.titles.forEach { title ->
                val now = clock.now()
                val created = tasks.create(
                    Task(
                        id = TaskId.generate(),
                        title = title,
                        parentTaskId = plan.taskId,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ).getOrThrow()
                written += created.id
            }
        }
        if (result.isFailure) {
            val takenBack = written.map { tasks.delete(it) }
            throw failureWith(result, takenBack)
        }
    }

    /** Logs the time entries a proposal asked for. All of them, or none — see [addChecklistItems]. */
    private suspend fun addTimeEntries(plan: ProposalPlan.AddTimeEntries, userId: UserId) {
        val written = mutableListOf<TimeEntryId>()
        val result = runCatchingCancellable {
            plan.entries.forEach { entry ->
                written += timeTracking.createManualEntry(
                    taskId = plan.taskId,
                    userId = userId,
                    startedAt = entry.startedAt,
                    endedAt = entry.endedAt,
                    kind = TimeEntryKind.Work,
                    note = entry.note,
                    source = TimeEntrySource.AiProposal,
                ).getOrThrow()
            }
        }
        if (result.isFailure) {
            val takenBack = written.map { timeTracking.delete(it) }
            throw failureWith(result, takenBack)
        }
    }

    /**
     * The failure of a write, with the rows that could not be taken back named in it.
     *
     * A row that stayed behind is not a detail: the change goes back to waiting and the
     * user will try again, so a row that stayed would be there twice. The message says
     * so, because the user has to know that this one needs more than a retry.
     *
     * "Taken back" is what the repository allows — a checklist row is deleted, a task is
     * archived, a time entry is marked as deleted. What matters for the retry is that
     * the row is not there to be seen.
     */
    private fun failureWith(failure: Result<*>, takenBack: List<Result<*>>): Throwable {
        val error = failure.exceptionOrNull() ?: IllegalStateException("The change could not be applied")
        val stuck = takenBack.count { it.isFailure }
        return if (stuck == 0) {
            error
        } else {
            IllegalStateException(
                "${error.message}; and $stuck of the rows that were written could not be taken " +
                    "back — applying the change again would add them twice",
                error,
            )
        }
    }

    /**
     * Creates the tasks a note's actions describe.
     *
     * `ExtractActions` creates tasks directly from the note content, so an empty
     * action is skipped rather than becoming a task with no title.
     */
    private suspend fun extractNoteActions(plan: ProposalPlan.ExtractNoteActions, userId: UserId) {
        val now = clock.now()
        val written = mutableListOf<TaskId>()
        val result = runCatchingCancellable {
            plan.actions.forEach { actionText ->
                val title = actionText.trim()
                if (title.isNotEmpty()) {
                    val created = tasks.create(
                        Task(
                            id = TaskId.generate(),
                            title = title,
                            userId = userId,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ).getOrThrow()
                    written += created.id
                }
            }
        }
        if (result.isFailure) {
            // All of them, or none — see [addChecklistItems].
            val takenBack = written.map { tasks.delete(it) }
            throw failureWith(result, takenBack)
        }
    }
}
