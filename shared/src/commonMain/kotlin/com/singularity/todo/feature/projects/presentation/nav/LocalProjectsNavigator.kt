package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.ProjectsRoute

/**
 * Provides [ProjectsNavigator] to the projects feature screens.
 * Must be provided by [ProjectsNavGraph].
 *
 * Screens MUST NOT access [LocalProjectsNavBackStack] directly — use [ProjectsNavigator]
 * to preserve back semantics (size <= 1 → onExitGraph).
 */
val LocalProjectsNavigator = compositionLocalOf<ProjectsNavigator> {
    error("ProjectsNavigator not provided — wrap with ProjectsNavGraph")
}

/**
 * Internal back stack accessor for use within the nav package only.
 * Not accessible outside `feature/projects/presentation/nav/`.
 */
internal val LocalProjectsNavBackStack = compositionLocalOf<NavBackStack<ProjectsRoute>> {
    error("NavBackStack<ProjectsRoute> not provided")
}
