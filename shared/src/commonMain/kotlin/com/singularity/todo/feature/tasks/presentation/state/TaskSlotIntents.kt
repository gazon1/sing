package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.ui.MviIntent

/**
 * Slot markers for [TaskDetailIntent.Domain].
 *
 * Each slot accepts only the intents that carry its marker, so routing a reminder intent
 * to the checklist slot is a compile error rather than a silently unmatched `when` branch.
 * The coordinator routes variants with a single exhaustive `when` over [TaskDetailIntent.Domain],
 * so adding a variant without routing it also fails to compile.
 *
 * The markers are `sealed` so that a slot's `onIntent` can narrow exhaustively; the
 * `else` branch inside a slot is unreachable through the coordinator and exists only
 * because the marker carries no members of its own.
 */
sealed interface TaskDetailSlotIntent : MviIntent

/** Task metadata: dates, priority, project, tags, kind, pin, recurrence, dependencies. */
sealed interface TaskEntityIntent : TaskDetailSlotIntent

/** Inline editing of the title and description, with debounced persistence. */
sealed interface TaskDraftIntent : TaskDetailSlotIntent

/** Marking complete, including rolling a recurring task forward. */
sealed interface TaskCompletionIntent : TaskDetailSlotIntent

/** Child collections owned by the task: checklist items, subtasks, attachments. */
sealed interface TaskChildrenIntent : TaskDetailSlotIntent

/** Reminders and their platform scheduling. */
sealed interface TaskRemindersIntent : TaskDetailSlotIntent

/** Soft delete, archive, and undo-restore. */
sealed interface TaskLifecycleIntent : TaskDetailSlotIntent

/** AI-assisted actions. */
sealed interface TaskAiIntent : TaskDetailSlotIntent
