package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Единая точка входа для [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator].
 *
 * Все варианты обрабатываются VM через [Domain].
 *
 * Каждый вариант [Domain] дополнительно реализует маркер своего слота
 * ([TaskEntityIntent], [TaskChildrenIntent], …). Слот принимает только свой маркер,
 * поэтому передать чужой intent в слот — ошибка компиляции, а не молчаливо неверная
 * ветка `when`. Координатор маршрутизирует варианты в слоты единственным
 * исчерпывающим `when` по [Domain].
 */
sealed interface TaskDetailIntent : MviIntent {

    // ── Domain: owned by ViewModel ─────────────────────────────────────────

    sealed interface Domain : TaskDetailIntent {

        // ── Hero ────────────────────────────────────────────────────────────

        data object ToggleComplete : Domain, TaskCompletionIntent
        data class TitleChanged(val title: String) :
            Domain,
            TaskDraftIntent
        data class DescriptionChanged(val description: String) :
            Domain,
            TaskDraftIntent
        data object ToggleSomeday : Domain, TaskEntityIntent
        data class SetKind(val kind: TaskKind) :
            Domain,
            TaskEntityIntent

        // ── Meta fields ─────────────────────────────────────────────────────

        data class SetDueDate(val date: LocalDate?) :
            Domain,
            TaskEntityIntent
        data class SetDueTime(val time: LocalTime?) :
            Domain,
            TaskEntityIntent
        data class SetStartDate(val date: LocalDate?) :
            Domain,
            TaskEntityIntent
        data class SetStartTime(val time: LocalTime?) :
            Domain,
            TaskEntityIntent
        data class SetPriority(val priority: TaskPriority) :
            Domain,
            TaskEntityIntent
        data class SetProject(val projectId: ProjectId?) :
            Domain,
            TaskEntityIntent

        // ── Tags ────────────────────────────────────────────────────────────

        data class SetTags(val tagIds: List<TagId>) :
            Domain,
            TaskEntityIntent
        data class RemoveTag(val tagId: TagId) :
            Domain,
            TaskEntityIntent

        // ── Checklist ───────────────────────────────────────────────────────

        data class ToggleChecklistItem(val item: ChecklistItem) :
            Domain,
            TaskChildrenIntent
        data class DeleteChecklistItem(val id: ChecklistItemId) :
            Domain,
            TaskChildrenIntent
        data class AddChecklistItem(val title: String) :
            Domain,
            TaskChildrenIntent

        // ── Subtasks ─────────────────────────────────────────────────────

        data class ToggleSubtask(val task: Task) :
            Domain,
            TaskChildrenIntent
        data class DeleteSubtask(val task: Task) :
            Domain,
            TaskChildrenIntent
        data class AddSubtask(val title: String) :
            Domain,
            TaskChildrenIntent

        // ── Reminders ─────────────────────────────────────────────────────

        data class SetReminder(val offset: ReminderOffset) :
            Domain,
            TaskRemindersIntent
        data object DeleteReminder : Domain, TaskRemindersIntent

        // ── Lifecycle ─────────────────────────────────────────────────────

        /** Soft-delete + show Undo snackbar. */
        data object Delete : Domain, TaskLifecycleIntent

        /** Soft-delete without Undo (archive). */
        data object Archive : Domain, TaskLifecycleIntent

        /** Restore the last soft-deleted task. */
        data object Restore : Domain, TaskLifecycleIntent

        /**
         * Restore the currently open archived (trashed) task — clears [Task.archivedAt]
         * so the task returns to the active lists. Distinct from [Restore]: that one
         * replays a delete that just happened on this screen (undo), this one un-trashes
         * a task opened from the Archive screen, where no delete happened here.
         */
        data object Unarchive : Domain, TaskLifecycleIntent

        // ── Pin ─────────────────────────────────────────────────────────────

        data object TogglePinned : Domain, TaskEntityIntent

        // ── Dependencies ─────────────────────────────────────────────────────

        data class SetDependencies(val dependsOn: Set<TaskId>) :
            Domain,
            TaskEntityIntent

        // ── Recurrence ───────────────────────────────────────────────────────

        data class SetRecurrence(val spec: RecurrenceSpec?) :
            Domain,
            TaskEntityIntent

        // ── Estimate ────────────────────────────────────────────────────────

        data class SetEstimate(val minutes: Int?) :
            Domain,
            TaskEntityIntent

        // ── Attachments ──────────────────────────────────────────────────────

        data class AddUrlAttachment(val url: String, val title: String?) :
            Domain,
            TaskChildrenIntent

        /**
         * Copy a file the user picked into this task's attachment store.
         *
         * [sourcePath] is what the platform picker returned: a plain path on desktop, a
         * `content://` URI on Android. The copy — and the size check that must precede
         * it — belongs to `AttachmentStorage`, not to this intent.
         */
        data class AddFileAttachment(val sourcePath: String, val mimeType: String?) :
            Domain,
            TaskChildrenIntent

        data class DeleteAttachment(val id: AttachmentId) :
            Domain,
            TaskChildrenIntent

        // ── AI ────────────────────────────────────────────────────────────────

        /** Run an AI action (RefineTitle, GenerateDescription, etc.) and apply the result. */
        data class RunAiAction(val action: TaskAiAction) :
            Domain,
            TaskAiIntent

        // ── Proposals ────────────────────────────────────────────────────

        /** Confirm one proposal item. */
        data class ConfirmProposalItem(val itemId: ProposalItemId) : Domain

        /** Reject one proposal item, optionally with a reason. */
        data class RejectProposalItem(val itemId: ProposalItemId, val reason: String? = null) : Domain

        /** Confirm all pending items across all proposals for this task. */
        data class ConfirmAllProposalItems(val proposalId: ProposalId) : Domain

        /** Dismiss (retract) a proposal and all its pending items. */
        data class DismissProposal(val proposalId: ProposalId) : Domain

        // ── Time Tracking ─────────────────────────────────────────────────

        /** Start the time tracker for this task. */
        data object Start : Domain

        /** Stop the running time tracker. */
        data object Stop : Domain

        /**
         * Create a manual time entry.
         * @param startedAtMs Start time in epoch milliseconds.
         * @param endedAtMs End time in epoch milliseconds.
         * @param kind Work or Recording.
         * @param note Optional note.
         */
        data class CreateManual(
            val startedAtMs: Long,
            val endedAtMs: Long,
            val kind: TimeEntryKind,
            val note: String?,
        ) : Domain

        /**
         * Update the displayed elapsed time (called by the UI ticker).
         */
        data class Tick(val elapsedMs: Long) :
            Domain,
            TaskTimeSlotIntent
    }
}
