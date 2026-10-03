package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.proposals.domain.model.NoteField
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.TagId
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

            is ProposalPlan.AddChecklistItems -> plan.texts.forEach { text ->
                checklist.addItem(plan.taskId.value, text).getOrThrow()
            }

            is ProposalPlan.AddSubtasks -> plan.titles.forEach { title ->
                val now = clock.now()
                tasks.create(
                    Task(
                        id = TaskId.generate(),
                        title = title,
                        parentTaskId = plan.taskId,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ).getOrThrow()
            }

            is ProposalPlan.AddTimeEntries -> plan.entries.forEach { entry ->
                timeTracking.createManualEntry(
                    taskId = plan.taskId,
                    userId = userId,
                    startedAt = entry.startedAt,
                    endedAt = entry.endedAt,
                    kind = TimeEntryKind.Work,
                    note = entry.note,
                    source = TimeEntrySource.AiProposal,
                ).getOrThrow()
            }

            is ProposalPlan.WriteNote -> {
                val updated = when (plan.field) {
                    NoteField.Title -> plan.note.copy(title = plan.value.trim())
                    NoteField.Body -> plan.note.copy(bodyHtml = plan.value)
                    NoteField.Summary -> plan.note.copy(bodyHtml = plan.value)
                }
                notes.upsert(updated)
            }

            is ProposalPlan.DeleteNote -> {
                notes.archive(plan.noteId).getOrThrow()
            }

            is ProposalPlan.ExtractNoteActions -> {
                // ExtractActions creates tasks directly from the note content.
                val now = clock.now()
                plan.actions.forEach { actionText ->
                    val title = actionText.trim()
                    if (title.isNotEmpty()) {
                        tasks.create(
                            Task(
                                id = TaskId.generate(),
                                title = title,
                                userId = userId,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        ).getOrThrow()
                    }
                }
            }

            is ProposalPlan.DeleteTask -> {
                tasks.softDelete(plan.delTaskId).getOrThrow()
            }

            is ProposalPlan.DeleteProject -> {
                deleteProject(plan.projectId).getOrThrow()
            }

            is ProposalPlan.DeleteTag -> {
                tags.delete(plan.tagId).getOrThrow()
            }
        }
    }
}
