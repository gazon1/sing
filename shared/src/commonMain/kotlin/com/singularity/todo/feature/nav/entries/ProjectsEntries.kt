package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavStacks
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.ProjectsRoute
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.nav.toProjectsRoute
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavGraph

/**
 * The app-level entries the projects feature owns.
 *
 * A contributor rather than an `entry { }` block in the platform's entry
 * provider: the entry bodies are the same on both platforms, and the only thing
 * that differed was where the stack came from — now a parameter.
 *
 * @param nav the outer navigation callbacks.
 * @param stacks the stacks the platform hoists.
 */
fun EntryProviderScope<AppDestination>.projectsEntries(nav: NavCallbacks, stacks: AppNavStacks) {
    entry<AppDestination.ProjectEditor>(clazzContentKey = { it.stableContentKey() }) { route ->
        ProjectsNavGraph(
            start = ProjectsRoute.Editor(route.projectId),
            onExitGraph = nav.graphExit,
        )
    }

    entry<AppDestination.ProjectDetail>(clazzContentKey = { it.stableContentKey() }) { route ->
        ProjectsNavGraph(
            start = ProjectsRoute.Detail(route.projectId),
            onExitGraph = nav.graphExit,
        )
    }

    entry<AppDestination.ProjectsGraph>(clazzContentKey = { it.stableContentKey() }) { route ->
        ProjectsNavGraph(
            start = route.start.toProjectsRoute(),
            onExitGraph = nav.graphExit,
            // The list is hoisted on the desktop, so the graph keeps its place
            // when a task is opened from a project and the graph leaves the
            // screen. An entry that starts somewhere else — the editor — gets
            // a stack of its own, or it would show the hoisted stack's top
            // instead of the route it was opened for.
            backStack = if (route.start == AppDestination.ProjectsStartRoute.List) stacks.projects else null,
        )
    }
}
