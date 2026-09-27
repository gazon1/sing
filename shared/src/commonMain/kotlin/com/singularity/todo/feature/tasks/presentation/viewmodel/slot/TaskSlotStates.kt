package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlin.time.Instant

// Intent markers live with the intent hierarchy they partition — see TaskSlotIntents.kt.

/**
 * Everything the entity slot derives beyond the task row itself.
 *
 * [availableTasks] backs the dependency picker; it excludes the current task and
 * trashed tasks so the picker cannot offer the task as its own dependency.
 */
data class TaskEntityState(
    val project: Project? = null,
    val tags: List<Tag> = emptyList(),
    val availableTasks: List<Task> = emptyList(),
)

/** The completion-related projection the hero row renders. */
data class TaskCompletionState(
    val isCompleted: Boolean = false,
    val completedAt: Instant? = null,
    val hasRecurrence: Boolean = false,
)

/** The task's child collections. */
data class TaskChildrenState(
    val checklist: List<ChecklistItem> = emptyList(),
    val subtasks: List<Task> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
)

/** Scheduled reminders for the task. */
data class TaskRemindersState(val reminders: List<Reminder> = emptyList())

/**
 * Delete/archive bookkeeping.
 *
 * [recentlyDeleted] is the snapshot the undo action restores. It is deliberately not part of
 * `TaskDetailUi`: nothing on screen renders it, and keeping it out of the assembled state
 * means a delete does not recompute the whole screen state.
 */
data class TaskLifecycleState(val recentlyDeleted: Task? = null)

/** Whether an AI action is in flight, so the UI can disable its trigger. */
data class TaskAiState(val isRunning: Boolean = false)

/**
 * Notes and tasks that link to this task via the `task://` URL scheme.
 *
 * Read-only: produced by a plain collector, not a slot, because it has no intent surface.
 */
data class TaskBacklinksState(val notes: List<Note> = emptyList(), val tasks: List<Task> = emptyList())
