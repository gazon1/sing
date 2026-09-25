package com.singularity.todo.core.graph

/**
 * Result returned when a cycle is detected during a graph mutation.
 *
 * Three distinct cases allow callers to handle each scenario differently:
 * - [SelfLoop]: node references itself directly
 * - [Cycle]: node participates in a cycle reachable through its edges
 * - [MissingNode]: the `node` itself does not exist in the graph (edge case for validation)
 */
sealed class CycleError : Exception() {
    /** The node that directly references itself. */
    data class SelfLoop(val node: String) : CycleError()

    /**
     * A directed cycle was detected.
     * [path] lists the nodes visited before the cycle closed; [edge] is the node
     * that closes the cycle (i.e. `path.last → edge` is the closing edge).
     *
     * Example: for a cycle A→B→C→A, detect(A, C) returns `Cycle(listOf(B, C), A)`.
     *
     * @property path The nodes on the path from `node` to (but not including) the repeated visit.
     * @property edge The node that caused the revisit (completes the cycle back to the first node in [path]).
     */
    data class Cycle(val path: List<String>, val edge: String) : CycleError()

    /** The [node] does not exist in the graph. */
    data class MissingNode(val node: String) : CycleError()
}

/**
 * Generic directed-graph cycle detector.
 *
 * Uses BFS from [node] following outgoing [edges]. If [node] is reached again
 * during traversal, a cycle exists.
 *
 * Returns [Result.failure] with a typed [CycleError] on cycle detection.
 * Returns [Result.success] when no cycle exists.
 *
 * ## When to use
 *
 * Use this when **adding or moving** a directed edge in a graph (e.g. setting
 * `task.dependsOn`, `task.parentTaskId`, `project.parentId`, `note.parentNoteId`).
 * The detector checks whether adding `node → newParent` would create a cycle.
 *
 * For **read-only** traversal with cycle detection, prefer [com.singularity.todo.core.tree.Cascade]
 * or [com.singularity.todo.core.tree.traverseDepthFirst] which throw on cycles.
 *
 * ## Algorithm
 *
 * BFS from [node] over [edges]. If any visited node equals [node], a cycle exists.
 * If all reachable nodes are exhausted without revisiting [node], no cycle exists.
 *
 * ## Usage
 *
 * ```kotlin
 * // Task dependencies: does adding dep → task create a cycle?
 * val result = CycleDetector.detect(
 *     node = taskId.value,
 *     newParent = newDepId.value,
 *     edges = { id -> taskDao.getDirectDependents(TaskId(id)) }
 *         .map { it.value },
 * )
 * result.onFailure { return when (it) {
 *     is CycleError.SelfLoop -> "Task cannot depend on itself"
 *     is CycleError.Cycle -> "Circular dependency: ${it.path.joinToString(" → ")} → ${it.edge}"
 *     is CycleError.MissingNode -> "Task not found"
 * }}
 * ```
 *
 * @param node The node whose outgoing edges will be traversed (starting point of BFS).
 * @param newParent The new parent/edge target of `node` (the edge being validated: `node → newParent`).
 * @param edges Returns the outgoing neighbours of a given node. Return empty for leaf nodes.
 *   The [String] parameter and return type make this function type-erased for simplicity;
 *   callers convert their `Id` types to/from `String` as needed.
 *
 * @see CycleError for the three failure cases.
 */
object CycleDetector {

    /**
     * Detects whether adding the directed edge `node → newParent` would create a cycle.
     *
     * @return [Result.success] if the edge is safe; [Result.failure] with [CycleError] otherwise.
     */
    fun detect(node: String, newParent: String, edges: (String) -> Iterable<String>): Result<Unit> {
        if (node == newParent) {
            return Result.failure(CycleError.SelfLoop(node))
        }

        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()

        // Adding `node → newParent` creates a cycle when `newParent` already
        // transitively depends on `node`.  BFS from `newParent` following what it
        // depends on — if we ever see `node`, the new edge would close a cycle.
        visited.add(newParent)
        queue.addLast(newParent)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            // current is something newParent depends on; keep walking
            for (neighbour in edges(current)) {
                if (neighbour == node) {
                    // newParent → ... → node is already a path; adding node → newParent closes it
                    val path = visited.toList()
                    return Result.failure(CycleError.Cycle(path = path, edge = current))
                }
                if (neighbour !in visited) {
                    visited.add(neighbour)
                    queue.addLast(neighbour)
                }
            }
        }

        return Result.success(Unit)
    }
}
