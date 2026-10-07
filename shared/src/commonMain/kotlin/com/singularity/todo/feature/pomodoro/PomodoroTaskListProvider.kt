package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides the current task list for the Pomodoro timer screen.
 *
 * - androidMain: [AndroidPomodoroTaskListProvider][com.singularity.todo.feature.pomodoro.AndroidPomodoroTaskListProvider]
 *   — observes inbox tasks from [com.singularity.todo.feature.tasks.domain.port.TaskRepository]
 * - jvmMain: [JvmPomodoroTaskListProvider][com.singularity.todo.feature.pomodoro.JvmPomodoroTaskListProvider]
 *   — returns an empty, never-updating flow
 *
 * ## Why [isSupported] exists
 *
 * The Desktop implementation returns an empty list that never updates, and an empty
 * picker is indistinguishable from a genuinely empty Inbox: the user sees a working
 * screen with no tasks in it and concludes they have none. Android shows the real Inbox,
 * so the two platforms disagree about whether the feature works at all.
 *
 * [isSupported] lets the screen say so instead. The same reasoning as
 * [com.singularity.todo.feature.reminders.ReminderScheduler.isSupported] — the
 * implementation is the authority on what it can provide.
 */
fun interface PomodoroTaskListProvider {
    /**
     * Whether [tasks] can return a meaningful list on this platform.
     *
     * False means the empty list is an artifact of the platform, not a fact about the
     * user's tasks, and the screen must say which it is.
     */
    val isSupported: Boolean get() = true

    /** Returns the current inbox task list as a state flow. */
    fun tasks(): StateFlow<List<Task>>
}
