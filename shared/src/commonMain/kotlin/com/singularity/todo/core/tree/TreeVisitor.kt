package com.singularity.todo.core.tree

/**
 * Directives returned by [traverseDepthFirst] visitor callbacks to control traversal.
 *
 * Analog of `org-element-map` directives in Org-mode.
 *
 * @see traverseDepthFirst
 */
sealed interface TraversalDirective {
    /** Continue traversal normally — descend into children if they exist. */
    data object Continue : TraversalDirective

    /** Do not descend into this node's children. */
    data object SkipSubtree : TraversalDirective

    /** Stop traversal entirely. */
    data object Stop : TraversalDirective
}

/**
 * Performs a depth-first traversal of a tree, calling [visit] on each visited node.
 *
 * The traversal order is pre-order: the parent is visited before its children.
 *
 * ## Usage
 *
 * ```kotlin
 * // Find the first task with a specific title, stop early
 * var result: Task? = null
 * tasks.traverseDepthFirst(
 *     childrenOf = { task -> tasks.filter { it.parentTaskId == task.id } },
 *     visit = { node, depth ->
 *         if (node.title == targetTitle) {
 *             result = node
 *             TraversalDirective.Stop
 *         } else {
 *             TraversalDirective.Continue
 *         }
 *     },
 * )
 *
 * // Count subtasks without descending into completed branches
 * var count = 0
 * tasks.traverseDepthFirst(
 *     childrenOf = { task -> tasks.filter { it.parentTaskId == task.id } },
 *     visit = { node, depth ->
 *         count++
 *         if (node.isCompleted) TraversalDirective.SkipSubtree
 *         else TraversalDirective.Continue
 *     },
 * )
 * ```
 *
 * @param roots The collection of root nodes to start traversal from.
 * @param childrenOf Returns the children of a given node.
 * @param visit Called for each node. Return a [TraversalDirective] to control traversal.
 *
 * @throws IllegalStateException if a cycle is detected (a node appears as its own ancestor).
 */
fun <T> traverseDepthFirst(roots: List<T>, childrenOf: (T) -> List<T>, visit: (T, depth: Int) -> TraversalDirective) {
    val visited = LinkedHashSet<Int>()

    fun walk(node: T, depth: Int) {
        if (!visited.add(node.hashCode())) {
            throw IllegalStateException(
                "Cycle detected during depth-first traversal. " +
                    "Node was revisited.",
            )
        }
        when (visit(node, depth)) {
            TraversalDirective.Stop -> return

            TraversalDirective.SkipSubtree -> { /* don't descend */ }

            TraversalDirective.Continue -> {
                for (child in childrenOf(node)) {
                    walk(child, depth + 1)
                }
            }
        }
    }

    for (root in roots) {
        walk(root, depth = 0)
    }
}

/**
 * Performs a depth-first traversal of a single tree rooted at [root].
 *
 * @see traverseDepthFirst
 */
fun <T> T.traverseDepthFirst(childrenOf: (T) -> List<T>, visit: (T, depth: Int) -> TraversalDirective) {
    traverseDepthFirst(roots = listOf(this), childrenOf = childrenOf, visit = visit)
}

// ─── Common applications ───────────────────────────────────────────────────────

/**
 * Returns all nodes in the tree rooted at [root], visited in pre-order depth-first order.
 *
 * Use when you need the full list (e.g. for caching or bulk processing).
 */
fun <T> preOrderList(root: T, childrenOf: (T) -> List<T>): List<T> {
    val result = mutableListOf<T>()
    root.traverseDepthFirst(
        childrenOf = childrenOf,
        visit = { node, _ ->
            result.add(node)
            TraversalDirective.Continue
        },
    )
    return result
}

/**
 * Counts the total number of nodes in the tree, optionally stopping at [maxDepth].
 *
 * Use for quota checks (e.g. "don't load subtrees larger than N items").
 *
 * @param maxDepth If non-null, stop counting beyond this depth.
 */
fun <T> countNodes(root: T, childrenOf: (T) -> List<T>, maxDepth: Int? = null): Int {
    var count = 0
    root.traverseDepthFirst(
        childrenOf = childrenOf,
        visit = { _, depth ->
            if (maxDepth != null && depth >= maxDepth) {
                count++
                TraversalDirective.SkipSubtree
            } else {
                count++
                TraversalDirective.Continue
            }
        },
    )
    return count
}
