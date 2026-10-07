package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * JVM implementation of [PomodoroTaskListProvider].
 *
 * Desktop observes the Inbox in-process, so there is no platform obstacle — the task
 * list is empty only because this binding was never wired to a repository. Reporting
 * `isSupported = false` is the honest description: the screen must say "not available
 * here" rather than present an empty picker as the user's (empty) Inbox.
 */
class JvmPomodoroTaskListProvider : PomodoroTaskListProvider {
    override val isSupported: Boolean = false

    override fun tasks(): StateFlow<List<Task>> = MutableStateFlow(emptyList())
}
