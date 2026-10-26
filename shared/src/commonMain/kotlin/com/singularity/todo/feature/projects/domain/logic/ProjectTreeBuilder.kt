package com.singularity.todo.feature.projects.domain.logic

import com.singularity.todo.core.tree.TraversalDirective
import com.singularity.todo.core.tree.traverseDepthFirst
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode

/**
 * Arranges the projects into the tree the menu shows.
 *
 * Pure: projects in, nodes out. The wall clock and the database are not read
 * here.
 *
 * ## The rules
 *
 * - A project **without a parent** is a root. A project **whose parent is gone**
 *   is a root too — it is not dropped, because a project the user can still
 *   open must be somewhere they can reach.
 * - A **closed** project is not in the tree. It has left the work, and the full
 *   project list is where closed projects are managed.
 * - A project with **no children** carries no expander.
 * - A **cycle** does not hang the menu. The walk stops where the cycle closes
 *   and the projects it did reach stay in the tree; the rest are shown as roots
 *   rather than lost.
 */
object ProjectTreeBuilder {

    /**
     * The nodes the menu draws, in display order.
     *
     * @param projects Every project the user has.
     * @param collapsedIds The projects whose children the user has folded away.
     *   A project that is not in the set shows its children — the tree starts
     *   open, so the hierarchy is visible without a tap.
     */
    fun build(projects: List<Project>, collapsedIds: Set<ProjectId>): List<ProjectTreeNode> {
        val open = projects.filter { !it.isDeleted && it.closedAt == null }
        if (open.isEmpty()) return emptyList()

        val byId = open.associateBy { it.id }
        val childrenByParent = open.groupBy { it.parentId }
        val order = compareBy<Project>({ it.sortOrder }, { it.name.lowercase() }, { it.id.value })

        fun childrenOf(project: Project): List<Project> =
            childrenByParent[project.id].orEmpty().sortedWith(order)

        val nodes = mutableListOf<ProjectTreeNode>()
        val visited = mutableSetOf<ProjectId>()

        /**
         * Marks everything below [project] as seen without drawing it.
         *
         * The children of a folded project are not in the tree, but they are not
         * missing either: they are behind the fold. Counting them as "never
         * reached" would put them at the top level, and a folded project would
         * show its children twice — once behind the fold and once beside it.
         */
        fun markHidden(project: Project) {
            val seen = mutableSetOf<ProjectId>()
            val queue = ArrayDeque(childrenOf(project))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!seen.add(next.id)) continue
                visited += next.id
                queue.addAll(childrenOf(next))
            }
        }

        /**
         * Walks one root's subtree.
         *
         * The traversal is the app's own depth-first walk, which turns a cycle
         * into an error rather than a hang. The error is caught here on purpose:
         * a menu that refuses to open because two projects point at each other
         * is a worse failure than a menu that shows one of them.
         */
        fun walk(root: Project) {
            try {
                traverseDepthFirst(
                    roots = listOf(root),
                    childrenOf = ::childrenOf,
                ) { project, depth ->
                    val firstVisit = visited.add(project.id)
                    if (firstVisit) {
                        val children = childrenOf(project)
                        val hasChildren = children.isNotEmpty()
                        val isExpanded = hasChildren && project.id !in collapsedIds
                        nodes += ProjectTreeNode(
                            project = project,
                            depth = depth,
                            hasChildren = hasChildren,
                            isExpanded = isExpanded,
                        )
                        if (isExpanded) {
                            TraversalDirective.Continue
                        } else {
                            markHidden(project)
                            TraversalDirective.SkipSubtree
                        }
                    } else {
                        // Already shown — a project that two others claim.
                        TraversalDirective.SkipSubtree
                    }
                }
            } catch (_: IllegalStateException) {
                // A cycle. Whatever the walk reached is already in the list; the
                // rest is picked up as roots below.
            }
        }

        val roots = ArrayDeque(
            open.filter { it.parentId == null || it.parentId !in byId }.sortedWith(order),
        )
        while (roots.isNotEmpty()) {
            walk(roots.removeFirst())
        }

        // A project caught in a cycle was never reached. It is shown as a root
        // rather than dropped: the user can still open it.
        var unreachable = open.filter { it.id !in visited }
        while (unreachable.isNotEmpty()) {
            unreachable.forEach { walk(it) }
            unreachable = open.filter { it.id !in visited }
        }

        return nodes
    }
}
