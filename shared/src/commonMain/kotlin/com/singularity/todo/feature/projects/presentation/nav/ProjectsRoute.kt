package com.singularity.todo.feature.projects.presentation.nav

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.projects.ProjectId
import kotlinx.serialization.Serializable

/**
 * Navigation routes for the projects nested graph.
 *
 * Lives inside [ProjectsNavGraph] which provides its own NavBackStack.
 *
 * @Serializable because [ProjectsNavGraph] uses a [SavedStateConfiguration]-backed
 * [androidx.navigation3.runtime.rememberNavBackStack], which serializes the stack.
 * All [NavKey] subtypes must be serializable for the [SaveableStateHolder] encoder.
 */
@Serializable
sealed interface ProjectsRoute : NavKey {

    @Serializable
    data object List : ProjectsRoute

    @Serializable
    data class Editor(val projectId: ProjectId? = null) : ProjectsRoute

    @Serializable
    data class Detail(val projectId: ProjectId) : ProjectsRoute
}
