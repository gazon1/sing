package com.singularity.todo.feature.projects.presentation.nav

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.projects.ProjectId

/**
 * Navigation routes for the projects nested graph.
 *
 * Lives inside [ProjectsNavGraph] which provides its own NavBackStack.
 * NOT @Serializable — the nested graph uses an empty SavedStateConfiguration
 * so routes are kept in memory only.
 */
sealed interface ProjectsRoute : NavKey {

    data object List : ProjectsRoute

    data class Editor(val projectId: ProjectId? = null) : ProjectsRoute

    data class Detail(val projectId: ProjectId) : ProjectsRoute
}
