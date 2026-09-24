package com.singularity.todo.core.tree

import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * Looks up the nearest non-null value of [extract] along the ancestor chain of [startNode],
 * walking upward via [parentOf].
 *
 * Analog of `org-entry-get` with `org-agenda-use-tag-inheritance` in Org-mode.
 *
 * ## Algorithm
 *
 * Walks from [startNode] to root collecting visited keys in a `LinkedHashSet`.
 * If the same key appears twice (cycle), throws [IllegalStateException].
 * The first non-null [extract] result that satisfies [stopOn] is returned.
 *
 * ## Usage
 *
 * ```kotlin
 * // Inherit project colour up the ancestor chain
 * val color = tasks.cascadeUp(
 *     startNode = task,
 *     keyOf = Task::id,
 *     parentOf = Task::parentTaskId,
 *     extract = { t -> t.projectId?.let { projectColors[it] } },
 * )
 * ```
 *
 * @param startNode The node to start the cascade from.
 * @param keyOf Returns the key (identity) of a node.
 * @param parentOf Returns the parent's key of a node, or `null` if the node is a root.
 * @param extract Returns the value to inherit, or `null` if this node has none.
 * @param stopOn Optional predicate — stop traversal and return when it returns `true`.
 * @return The first non-null [extract] result satisfying [stopOn], or `null` if none found.
 *
 * @throws IllegalStateException if a cycle is detected (same key visited twice).
 */
inline fun <T, K, V> List<T>.cascadeUp(
    startNode: T,
    keyOf: (T) -> K,
    parentOf: (T) -> K?,
    extract: (T) -> V?,
    stopOn: (V) -> Boolean = { false },
): V? {
    val index = associateBy(keyOf)
    val visited = LinkedHashSet<K>()
    var currentKey: K? = keyOf(startNode)

    while (currentKey != null) {
        if (!visited.add(currentKey)) {
            throw IllegalStateException("Cycle detected in cascade chain at key: $currentKey")
        }
        val node = index[currentKey] ?: break
        val value = extract(node)
        if (value != null && stopOn(value)) {
            return value
        }
        currentKey = parentOf(node)
    }
    return null
}

// ─── Common applications ───────────────────────────────────────────────────────

/**
 * Returns the nearest ancestor project's ARGB colour for [this] task, walking up
 * the [Task.parentTaskId] chain.
 *
 * Returns `null` if no ancestor has a project with a colour defined.
 *
 * @param allTasks All tasks in the current context — used for ancestor lookup.
 * @param allProjects All projects — each project carries a colour value.
 */
fun Task.cascadeProjectColor(allTasks: List<Task>, allProjects: List<Project>): Int? {
    val projectIndex = allProjects.associateBy { it.id }
    return allTasks.cascadeUp(
        startNode = this,
        keyOf = { it.id },
        parentOf = { it.parentTaskId },
        extract = { task ->
            task.projectId?.let { projectId -> projectIndex[projectId]?.color }
        },
    )
}
