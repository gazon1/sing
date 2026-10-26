package com.singularity.todo.feature.projects.domain.model

/**
 * A project as the menu's tree shows it.
 *
 * The tree is a way *into* the projects, so a node carries what the menu has to
 * draw and what a tap has to know: the project itself, how deep it sits, whether
 * it has anything behind it, and whether that anything is showing.
 *
 * @param project The project this node stands for.
 * @param depth How many levels below a root the node sits. A root is `0`.
 * @param hasChildren Whether the project has children at all. A node without
 *   children offers nothing to expand, so it carries no expander.
 * @param isExpanded Whether the children are showing.
 */
data class ProjectTreeNode(val project: Project, val depth: Int, val hasChildren: Boolean, val isExpanded: Boolean) {
    /** The project's id — the thing a tap on the node acts on. */
    val id: ProjectId get() = project.id

    /** The name the user reads. */
    val name: String get() = project.name
}
