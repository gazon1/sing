package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.TasksRoute

/**
 * Provides [TasksNavigator] to the tasks feature screens.
 * Must be provided by [TasksNavGraph].
 *
 * Screens MUST NOT access [LocalNavBackStack] directly — use [TasksNavigator]
 * to preserve back semantics (size <= 1 → onExitGraph).
 */
val LocalTasksNavigator = compositionLocalOf<TasksNavigator> {
    error("TasksNavigator not provided — wrap with TasksNavGraph")
}

/**
 * Internal back stack accessor for use within the nav package only.
 * Not accessible outside `feature/tasks/presentation/nav/`.
 */
internal val LocalNavBackStack = compositionLocalOf<NavBackStack<TasksRoute>> {
    error("NavBackStack<TasksRoute> not provided")
}
