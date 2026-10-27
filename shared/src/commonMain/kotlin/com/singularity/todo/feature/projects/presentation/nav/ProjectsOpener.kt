package com.singularity.todo.feature.projects.presentation.nav

import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the menu asks the projects graph to show.
 *
 * Two cases, and the second one is why this is a type rather than a nullable
 * project id: the menu opens the graph either to show the list of projects or
 * to show one project. A nullable id cannot say "the list" — it can only say
 * "no project", which the graph would have to read as "do nothing".
 */
sealed interface ProjectsOpenRequest {

    /** The list of all projects. */
    data object List : ProjectsOpenRequest

    /** One project, by its id. */
    data class Project(val projectId: ProjectId) : ProjectsOpenRequest
}

/**
 * The rendezvous between the menu's project tree and the projects graph.
 *
 * The tree lives outside the projects graph — it is drawn in the app menu — but
 * tapping a project has to land on that project's own screen, which lives
 * inside the graph.
 *
 * ## Why a request and not a destination
 *
 * The obvious answer is a navigation destination that carries the project id.
 * It is rejected: a project id in the navigation key space turns the closed set
 * of destinations into an open one, and every deep link, every saved state and
 * every navigation test grows a case for a thing that is not a destination.
 *
 * So the request is a piece of app state instead: the menu says *open this
 * project*, and the graph, when it is there, picks the request up. The project
 * id never becomes a destination — it stays what it is, an id of a project.
 *
 * ## Why the list is a request too
 *
 * The graph's stack is hoisted on Desktop, so it survives the graph leaving the
 * screen. Without a request, opening the graph again would show wherever the
 * stack was left — the detail of a project the user opened a minute ago. The
 * "all projects" entry therefore asks for the list the same way the tree asks
 * for a project.
 */
class ProjectsOpener {

    private val _requested = MutableStateFlow<ProjectsOpenRequest?>(null)

    /** What the menu asked for, if anything. */
    val requested: StateFlow<ProjectsOpenRequest?> = _requested.asStateFlow()

    /** Asks for [projectId] to be opened. */
    fun requestOpen(projectId: ProjectId) {
        _requested.value = ProjectsOpenRequest.Project(projectId)
    }

    /** Asks for the list of all projects. */
    fun requestList() {
        _requested.value = ProjectsOpenRequest.List
    }

    /** Takes the request, if there is one. The graph calls this when it acts. */
    fun consume(): ProjectsOpenRequest? {
        val current = _requested.value
        if (current != null) {
            _requested.value = null
        }
        return current
    }
}
