package com.singularity.todo.feature.tasks.domain.model

import kotlinx.serialization.Serializable

/**
 * The state the user has claimed for a task's work.
 *
 * A task can be overdue, blocked, pinned or repeating without the user having
 * said anything about it — those are facts the app derives. This enum is the
 * opposite: it records what the user has said. "The one I am doing next" and
 * "the one I am doing now" are statements only the user can make, so they are
 * stored rather than inferred.
 *
 * ## Three values, not four
 *
 * `Open` is the default and means nothing has been claimed. `NextUp` is the one
 * the user will do next. `InProgress` is the one being worked on right now.
 *
 * There is deliberately no `Waiting` value. Waiting on someone else is already
 * derived from the task's real prerequisites — a task whose prerequisite is
 * unfinished is blocked, and it stops being blocked the moment the prerequisite
 * is finished. A stored flag for it would give one fact two sources of truth
 * that could disagree: the flag says waiting while the graph says the
 * prerequisite is done.
 *
 * ## Independent of completion
 *
 * A task that was in progress when it was finished is a fact about how the work
 * went, and clearing it on completion loses that. So completing a task does not
 * change its state, and reopening it restores the state it held. The state stops
 * being actionable on a completed task — it is shown, not editable.
 *
 * ## Shared across a series
 *
 * A series is one piece of work scheduled on several dates. The workflow state
 * describes *the work*, so it is a shared field of the series: marking one
 * occurrence "in progress" marks the work in progress, not one of its dates.
 *
 * @see com.singularity.todo.feature.tasks.domain.model.TaskSharedField
 * @see com.singularity.todo.feature.tasks.domain.model.Task
 */
@Serializable
enum class TaskWorkflowState {
    /** Nothing claimed. The default for every task, existing or new. */
    Open,

    /** The user marked this as the one they will do next. */
    NextUp,

    /** The user is working on this now. */
    InProgress,
}
