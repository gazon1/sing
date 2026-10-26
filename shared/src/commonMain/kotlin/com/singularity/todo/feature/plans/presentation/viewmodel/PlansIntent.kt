package com.singularity.todo.feature.plans.presentation.viewmodel

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.tasks.domain.model.TaskId

/** What the user can ask the plan screen to do. */
sealed interface PlansIntent : MviIntent {

    /**
     * Switch the timeline to another granularity.
     *
     * The choice is written to the profile's settings, so it survives leaving
     * the screen — the screen reads the granularity back rather than keeping
     * it in memory.
     */
    data class SetGranularity(val granularity: PlansGranularity) : PlansIntent

    /**
     * Tick a task off — or back on.
     *
     * A task that leaves the timeline this way is gone from the plan, because
     * the plan shows the work that is still ahead.
     */
    data class ToggleCompleted(val taskId: TaskId) : PlansIntent

    /**
     * Swipe a task away.
     *
     * The same gesture the other task lists offer, and the same write: the
     * task is soft-deleted, and its reminders are cancelled with it.
     */
    data class TaskSwipedAway(val taskId: TaskId) : PlansIntent
}
