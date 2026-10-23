package com.singularity.todo.feature.proposals.domain.usecase

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.notes.domain.port.NotesRepository
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
 * One row a proposal's write creates.
 *
 * ## Why the id is here and not in the result of the write
 *
 * A write that is cut short *after* the database has committed leaves a row
 * behind: the scope is gone, the row is not. The take-back can only address a
 * row whose id it knows — and if the id is the result of the write, the write
 * that was cut short is exactly the one whose id never arrived. So the id is
 * named before the row is written, and the row carries it.
 *
 * @property id The row's identity, known before the write.
 * @property write Writes the row.
 * @property takeBack Makes the row not be there. Null when the write cannot be
 *   undone — an update that sets a field again is the same update, so a retry
 *   of it needs no take-back.
 * @property isGone Whether the row is not there. Asked only after a take-back
 *   failed, to tell "the row was never created" from "the row stayed".
 */
private class ProposedRow<out Id>(
    val id: Id,
    val write: suspend () -> Unit,
    val takeBack: (suspend () -> Result<*>)?,
    val isGone: (suspend () -> Boolean)?,
)

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
        writeAllOrNone(rowsOf(plan, userId))
    }

    /**
     * The rows a plan writes.
     *
     * This is the table of writers: one place that says what each plan does, and
     * every row it writes carries the id the row will have. A plan that writes
     * three rows says so here, and the take-back of those three rows is the same
     * code for all of them.
     */
    private suspend fun rowsOf(plan: ProposalPlan, userId: UserId): List<ProposedRow<*>> = when (plan) {
        is ProposalPlan.AddTags -> tagRow(plan.taskId) { taskId ->
            val current = tasks.getTagIds(taskId).first().toSet()
            tasks.setTags(taskId, (current + plan.tagIds).toList(), TagEditActor.AiProposal).getOrThrow()
        }

        is ProposalPlan.RemoveTags -> tagRow(plan.taskId) { taskId ->
            val current = tasks.getTagIds(taskId).first().toSet()
            tasks.setTags(taskId, (current - plan.tagIds).toList(), TagEditActor.AiProposal).getOrThrow()
        }

        is ProposalPlan.SetTaskTags -> tagRow(plan.taskId) { taskId ->
            // A set, not an addition: the proposal says the task carries these tags
            // and no others, so the current set is replaced rather than merged.
            tasks.setTags(taskId, plan.tagIds.toList(), TagEditActor.AiProposal).getOrThrow()
        }

        is ProposalPlan.AddChecklistItems -> checklistRows(plan)

        is ProposalPlan.AddSubtasks -> subtaskRows(plan.titles, plan.taskId, userId)

        is ProposalPlan.AddTimeEntries -> timeEntryRows(plan, userId)

        is ProposalPlan.ExtractNoteActions -> subtaskRows(
            plan.actions.map { it.trim() }.filter { it.isNotEmpty() },
            parentTaskId = null,
            userId = userId,
        )

        is ProposalPlan.WriteTask -> writeRow(plan.task.id) { tasks.update(plan.task).getOrThrow() }

        is ProposalPlan.WriteNote -> writeRow(plan.note.id) { notes.upsert(plan.note) }

        is ProposalPlan.WriteProject -> writeRow(plan.project.id) { projects.update(plan.project).getOrThrow() }

        is ProposalPlan.WriteTag -> writeRow(plan.tag.id) { tags.update(plan.tag).getOrThrow() }

        is ProposalPlan.CreateTask -> createRow(
            id = plan.task.id,
            write = { tasks.create(plan.task).getOrThrow() },
            takeBack = { tasks.delete(plan.task.id) },
            isGone = { !tasks.exists(plan.task.id) },
        )

        is ProposalPlan.CreateProject -> createRow(
            id = plan.project.id,
            write = { projects.create(plan.project).getOrThrow() },
            takeBack = { projects.delete(plan.project.id) },
            isGone = { projects.get(plan.project.id) == null },
        )

        is ProposalPlan.CreateNote -> createRow(
            id = plan.note.id,
            write = { notes.create(plan.note).getOrThrow() },
            takeBack = { notes.delete(plan.note.id) },
            isGone = { notes.get(plan.note.id) == null },
        )

        is ProposalPlan.CreateTag -> createRow(
            id = plan.tag.id,
            write = { tags.create(plan.tag).getOrThrow() },
            takeBack = { tags.delete(plan.tag.id) },
            isGone = { tags.get(plan.tag.id) == null },
        )

        is ProposalPlan.DeleteNote -> deleteRow(plan.noteId) { notes.archive(plan.noteId).getOrThrow() }

        is ProposalPlan.DeleteTask -> deleteRow(plan.delTaskId) { tasks.softDelete(plan.delTaskId).getOrThrow() }

        is ProposalPlan.DeleteProject -> deleteRow(plan.projectId) { deleteProject(plan.projectId).getOrThrow() }

        is ProposalPlan.DeleteTag -> deleteRow(plan.tagId) { tags.delete(plan.tagId).getOrThrow() }
    }

    /**
     * The row of an update.
     *
     * An update has no take-back: setting a field again is the same setting, so a
     * retry of it needs no undo — and a take-back that wrote the previous state
     * back would overwrite whatever the user did in the meantime.
     */
    private fun writeRow(id: Any, write: suspend () -> Unit): List<ProposedRow<*>> = listOf(
        ProposedRow(id = id, write = write, takeBack = null, isGone = null),
    )

    /**
     * The row of a creation.
     *
     * A creation has a take-back: the id is known before the write, so a write
     * that is cut short after the commit leaves a row the take-back can address.
     */
    private fun createRow(
        id: Any,
        write: suspend () -> Unit,
        takeBack: suspend () -> Result<*>,
        isGone: suspend () -> Boolean,
    ): List<ProposedRow<*>> = listOf(
        ProposedRow(id = id, write = write, takeBack = takeBack, isGone = isGone),
    )

    /**
     * The row of a removal.
     *
     * A removal has no take-back: the thing is gone, and putting it back is a
     * decision the user has not made.
     */
    private fun deleteRow(id: Any, write: suspend () -> Unit): List<ProposedRow<*>> = listOf(
        ProposedRow(id = id, write = write, takeBack = null, isGone = null),
    )

    /**
     * The task's tags become what the proposal says.
     *
     * The write sets a set of tags, so a retry of it sets the same set: there is
     * nothing to take back. The id is the task's — the row this write touches.
     */
    private fun tagRow(taskId: TaskId, write: suspend (TaskId) -> Unit): List<ProposedRow<*>> = listOf(
        ProposedRow(
            id = taskId,
            write = { write(taskId) },
            takeBack = null,
            isGone = null,
        ),
    )

    /**
     * The checklist rows a proposal asked for.
     *
     * All of them, or none of them: a row written before a later row fails would be
     * there twice on the next attempt, and the attempt is the user's — the change goes
     * back to waiting when the write fails. So the rows this call wrote are taken back
     * when a later row fails.
     */
    private fun checklistRows(plan: ProposalPlan.AddChecklistItems): List<ProposedRow<*>> = plan.texts.map { text ->
        val id = ChecklistItemId.generate()
        ProposedRow(
            id = id,
            write = { checklist.addItem(plan.taskId.value, text, id).getOrThrow() },
            takeBack = { checklist.delete(id) },
            isGone = { checklist.watchByTask(plan.taskId.value).first().none { it.id == id } },
        )
    }

    /**
     * The subtasks a proposal asked for — and the tasks a note's actions describe.
     *
     * One writer for both: a task created by a proposal is the same row whichever
     * proposal asked for it, and the id is named before the write so that a write
     * cut short after the commit leaves a row the take-back can address.
     */
    private fun subtaskRows(
        titles: List<String>,
        parentTaskId: TaskId?,
        userId: UserId,
    ): List<ProposedRow<*>> = titles.map { title ->
        val id = TaskId.generate()
        val now = clock.now()
        ProposedRow(
            id = id,
            write = {
                tasks.create(
                    Task(
                        id = id,
                        title = title,
                        parentTaskId = parentTaskId,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ).getOrThrow()
            },
            // A delete travels to the server as an archive, so the take-back of a
            // subtask is seen by the other device too — the row does not come back
            // there as a live task.
            takeBack = { tasks.delete(id) },
            isGone = { !tasks.exists(id) },
        )
    }

    /** The time entries a proposal asked for. All of them, or none — see [checklistRows]. */
    private fun timeEntryRows(plan: ProposalPlan.AddTimeEntries, userId: UserId): List<ProposedRow<*>> =
        plan.entries.map { entry ->
            val id = TimeEntryId.generate()
            ProposedRow(
                id = id,
                write = {
                    timeTracking.createManualEntry(
                        taskId = plan.taskId,
                        userId = userId,
                        startedAt = entry.startedAt,
                        endedAt = entry.endedAt,
                        kind = TimeEntryKind.Work,
                        note = entry.note,
                        source = TimeEntrySource.AiProposal,
                        id = id,
                    ).getOrThrow()
                },
                takeBack = { timeTracking.delete(id) },
                isGone = { timeTracking.watchEntries(plan.taskId).first().none { it.id == id } },
            )
        }

    /**
     * Writes every row, or none of them.
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
    private suspend fun writeAllOrNone(rows: List<ProposedRow<*>>) {
        val written = mutableListOf<ProposedRow<*>>()
        // The rows that are gone. A row that was taken back successfully is not
        // taken back a second time: the second attempt would fail on a row that
        // is already gone, and that failure would be reported as a row that
        // stayed — a row the user would be told about that is not there.
        val takenBack = mutableSetOf<Any?>()
        var settled = false
        try {
            val result = runCatchingCancellable {
                for (row in rows) {
                    // The row is remembered before it is written: a write that is cut
                    // short after the commit leaves a row behind, and the take-back
                    // has to know which row it is.
                    written += row
                    row.write()
                }
            }
            if (result.isFailure) {
                // The take-back runs where a cancellation cannot stop it, on this
                // path too: the scope may be cancelled while the rows are being
                // taken back, and a take-back that is cut short leaves the change
                // in a state where the next attempt writes the row twice.
                val stuck = withContext(NonCancellable) { takeBackAll(written, takenBack) }
                settled = true
                throw failureWith(result, stuck)
            }
            settled = true
        } finally {
            if (!settled) {
                // The write was cut short: the scope was cancelled. The rows that
                // were written are taken back under a scope that is not cancelled
                // any more, and the ones that could not be taken back are
                // reported — the user is not looking at this screen, and a row
                // that stayed would be there twice after the next attempt.
                val stuck = withContext(NonCancellable) { takeBackAll(written, takenBack) }
                reportStuckRows(stuck)
            }
        }
    }

    /**
     * Takes back the rows that were written, and says which of them stayed.
     *
     * A row that was taken back successfully is gone, and it is not taken back
     * again: the second attempt would fail on a row that is not there any more,
     * and that failure would be reported as a row that stayed. A row whose
     * take-back failed is not there to be seen either, so it is tried again by
     * the caller — and it is named if it stayed.
     */
    private suspend fun takeBackAll(
        written: List<ProposedRow<*>>,
        takenBack: MutableSet<Any?>,
    ): List<Result<*>> {
        val results = mutableListOf<Result<*>>()
        for (row in written) {
            if (row.id in takenBack) continue
            val result = takeBackSafely(row)
            if (result.isSuccess) takenBack += row.id
            results += result
        }
        return results
    }

    /**
     * Takes one row back, and tells "the row was never there" from "the row stayed".
     *
     * The take-back fails on a row that is not there — and a row that was never
     * created is not there, which is what the take-back wanted. So the row is
     * asked about once: if it is gone, the take-back has done its work.
     */
    private suspend fun takeBackSafely(row: ProposedRow<*>): Result<*> {
        val takeBack = row.takeBack ?: return Result.success(Unit)
        val result = takeBack()
        if (result.isSuccess) return result
        val gone = row.isGone?.invoke() ?: false
        return if (gone) Result.success(Unit) else result
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
