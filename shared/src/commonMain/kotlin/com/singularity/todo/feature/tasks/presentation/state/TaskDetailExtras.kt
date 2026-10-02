package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState

/**
 * Partition that combines time tracking, first-run state, and (future) AI proposals into
 * one flow, so the coordinator's top-level [com.singularity.todo.core.ui.featureSlot.combineStates]
 * stays at eight inputs instead of growing an overload per concern.
 *
 * Proposals are absent in MR-3; MR-7 adds them.
 */
sealed interface TaskDetailExtras {
    /** No partition has resolved yet — the screen holds its loading shell. */
    data object Unresolved : TaskDetailExtras

    /** Every partition resolved. */
    data class Ready(val timeSlotState: TaskTimeSlotState, val firstRun: FirstRun) : TaskDetailExtras
}

/**
 * Whether the task-detail screen should offer first-run scaffolding.
 *
 * Three states, not a boolean: a task that *may* still turn out to be empty and a task
 * that is known to be empty are different situations, and collapsing them makes the
 * banner flash on every keystroke.
 */
sealed interface FirstRun {
    /** Cheap answers are still outstanding — do not decide yet. */
    data object Unresolved : FirstRun

    /** Newly created and empty: offer the three suggested actions. */
    data object Offer : FirstRun

    /** Has content, or is old enough that suggestions would be noise. */
    data object Established : FirstRun
}
