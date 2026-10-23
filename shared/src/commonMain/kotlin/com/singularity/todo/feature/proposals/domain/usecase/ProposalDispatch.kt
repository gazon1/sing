package com.singularity.todo.feature.proposals.domain.usecase

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
 * @param log For the rows that stayed behind a cancelled write.
 * @param crashReporter For the same rows: there is no user to tell.
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
    private val log: Logger = Logger.withTag("ProposalDispatch"),
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
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

            is ProposalPlan.WriteNote -> writeNote(plan)

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
        writeAllOrNone(
            items = plan.texts,
            write = { text -> checklist.addItem(plan.taskId.value, text).getOrThrow() },
            takeBack = { id -> checklist.delete(id) },
        )
    }

    /** Creates the subtasks a proposal asked for. All of them, or none — see [addChecklistItems]. */
    private suspend fun addSubtasks(plan: ProposalPlan.AddSubtasks, userId: UserId) {
        writeAllOrNone(
            items = plan.titles,
            write = { title ->
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
                ).getOrThrow().id
            },
            takeBack = { id -> tasks.delete(id) },
        )
    }

    /** Logs the time entries a proposal asked for. All of them, or none — see [addChecklistItems]. */
    private suspend fun addTimeEntries(plan: ProposalPlan.AddTimeEntries, userId: UserId) {
        writeAllOrNone(
            items = plan.entries,
            write = { entry ->
                timeTracking.createManualEntry(
                    taskId = plan.taskId,
                    userId = userId,
                    startedAt = entry.startedAt,
                    endedAt = entry.endedAt,
                    kind = TimeEntryKind.Work,
                    note = entry.note,
                    source = TimeEntrySource.AiProposal,
                ).getOrThrow()
            },
            takeBack = { id -> timeTracking.delete(id) },
        )
    }

    /**
     * Creates the tasks a note's actions describe.
     *
     * `ExtractActions` creates tasks directly from the note content, so an empty
     * action is skipped rather than becoming a task with no title.
     */
    private suspend fun extractNoteActions(plan: ProposalPlan.ExtractNoteActions, userId: UserId) {
        val now = clock.now()
        writeAllOrNone(
            items = plan.actions.map { it.trim() }.filter { it.isNotEmpty() },
            write = { title ->
                tasks.create(
                    Task(
                        id = TaskId.generate(),
                        title = title,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ).getOrThrow().id
            },
            takeBack = { id -> tasks.delete(id) },
        )
    }

    /** Writes the field of a note a proposal asked for. */
    private suspend fun writeNote(plan: ProposalPlan.WriteNote) {
        val updated = when (plan.value) {
            is NoteValue.Title -> plan.note.copy(title = plan.value.value.trim())

            is NoteValue.Body -> plan.note.copy(bodyHtml = plan.value.value)

            is NoteValue.Summary -> plan.note.copy(bodyHtml = plan.value.value)

            // The planner refuses this value before the claim; the branch is here
            // because the `when` is exhaustive, and it says the same thing.
            is NoteValue.Unreadable -> error(plan.value.readFailure ?: "This value cannot be read.")
        }
        notes.upsert(updated)
    }

    /**
     * Writes every item, or none of them.
     *
     * A change that writes three rows and fails on the second leaves the first
     * behind, and the change goes back to waiting — so the row that stayed is
     * there twice on the next attempt. The same is true of a write that is cut
     * short: the scope is gone, the rows that were written are not, and the
     * change waits again.
     *
     * So the rows that were written are taken back on both paths, and the
     * take-back runs where a cancellation cannot stop it.
     */
    private suspend fun <T, Id> writeAllOrNone(
        items: List<T>,
        write: suspend (T) -> Id,
        takeBack: suspend (Id) -> Result<*>,
    ) {
        val written = mutableListOf<Id>()
        var settled = false
        try {
            val result = runCatchingCancellable {
                for (item in items) {
                    written += write(item)
                }
            }
            if (result.isFailure) {
                val takenBack = written.map { takeBack(it) }
                settled = true
                throw failureWith(result, takenBack)
            }
            settled = true
        } finally {
            if (!settled) {
                // The write was cut short: the scope was cancelled. The rows that
                // were written are taken back under a scope that is not cancelled
                // any more, and the ones that could not be taken back are
                // reported — the user is not looking at this screen, and a row
                // that stayed would be there twice after the next attempt.
                val takenBack = withContext(NonCancellable) { written.map { takeBack(it) } }
                reportStuckRows(takenBack)
            }
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
     * Reports the rows that stayed behind a cancelled write.
     *
     * There is no user to tell: the scope they were in is gone. So the fact goes to
     * the log and to the crash reporter, where it is a fact about the data rather
     * than a message nobody reads.
     */
    private fun reportStuckRows(takenBack: List<Result<*>>) {
        val stuck = takenBack.count { it.isFailure }
        if (stuck == 0) return
        log.e { "ProposalDispatch: $stuck written rows could not be taken back after a cancellation" }
        crashReporter.report(
            IllegalStateException("$stuck written rows could not be taken back after a cancellation"),
            "proposals.rows_stuck_after_cancellation",
        )
    }
}
