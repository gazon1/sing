package com.singularity.todo.feature.projects.domain.logic

import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * One project that matched a picker search, with the ancestor context that
 * tells where in the hierarchy it belongs.
 *
 * [ancestorNames] lists the names from the root down to the match's parent,
 * excluding the match itself. It is empty for a root match. The UI renders it
 * as a concise path (e.g. `Work → Android`) so a match can be distinguished
 * from a project with the same name elsewhere without turning ancestors into
 * selectable targets.
 */
data class ProjectPickerMatch(val project: Project, val ancestorNames: ImmutableList<String>)

/**
 * Filters the active project hierarchy for the task Project Picker.
 *
 * Pure: projects and query in, matches out. Only open, non-deleted projects are
 * considered. A project matches when [SEARCH_MODE] matches it; its ancestors are
 * retained as context, but unrelated siblings are never emitted unless they
 * match themselves. Results follow the hierarchy's display order.
 */
object ProjectPickerSearch {
    /**
     * The mode the picker searches in. The picker filters the projects it
     * already holds, and the term may occur anywhere in the name — the same
     * contains behavior the project surfaces state.
     */
    val SEARCH_MODE: SearchMode = SearchMode.Contains

    fun filter(
        projects: List<Project>,
        query: String,
    ): ImmutableList<ProjectPickerMatch> {
        if (query.isBlank()) return persistentListOf()

        val open = projects.filter { !it.isDeleted && it.closedAt == null }
        if (open.isEmpty()) return persistentListOf()

        val byId = open.associateBy { it.id }
        val childrenByParent = open.groupBy { it.parentId }
        val order = compareBy<Project>({ it.sortOrder }, { it.name.lowercase() }, { it.id.value })

        fun childrenOf(project: Project): List<Project> = childrenByParent[project.id].orEmpty().sortedWith(order)

        val out = mutableListOf<ProjectPickerMatch>()
        val visited = mutableSetOf<ProjectId>()

        fun walk(
            project: Project,
            ancestors: ImmutableList<String>,
        ) {
            if (!visited.add(project.id)) return
            if (SEARCH_MODE.matches(project, query)) {
                out += ProjectPickerMatch(project = project, ancestorNames = ancestors)
            }
            val nextAncestors = (ancestors + project.name).toImmutableList()
            for (child in childrenOf(project)) {
                walk(child, nextAncestors)
            }
        }

        val roots = open.filter { it.parentId == null || it.parentId !in byId }.sortedWith(order)
        for (root in roots) {
            walk(root, persistentListOf())
        }

        // A project caught in a cycle was never reached from a root. Visit it
        // rather than drop a matching project the user could still select.
        var unvisited = open.filter { it.id !in visited }
        while (unvisited.isNotEmpty()) {
            for (project in unvisited.sortedWith(order)) {
                if (project.id !in visited) {
                    walk(project, persistentListOf())
                }
            }
            unvisited = open.filter { it.id !in visited }
        }

        return out.toImmutableList()
    }
}
