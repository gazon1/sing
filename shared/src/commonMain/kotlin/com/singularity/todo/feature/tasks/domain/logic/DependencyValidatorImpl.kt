package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.DependencyAnalysis
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.TaskDependency
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator
import kotlinx.coroutines.flow.first

/**
 * Production [DependencyValidator].
 *
 * [assertNoCycles] performs a full BFS from each new dependency to detect any
 * path that would lead back to [taskId] — catching self-loops AND multi-node cycles.
 *
 * [analyzeDependencies] performs a full BFS of the transitive dependency graph
 * rooted at [taskId], returning whether any path leads back to [taskId] (a directed cycle).
 * The blocking edges returned are the subset of outgoing edges from the cycle that,
 * if removed, would break the cycle.
 */
class DependencyValidatorImpl(
    private val taskDao: TaskDao,
    private val currentUser: ProfileAwareCurrentUser,
) : DependencyValidator {

    override suspend fun assertNoCycles(taskId: TaskId, newDeps: Set<TaskId>): Result<Unit> {
        // Self-loop check
        if (taskId.value in newDeps.map { it.value }) {
            return Result.failure(IllegalArgumentException("Task cannot depend on itself: ${taskId.value}"))
        }
        // Multi-node cycle check: for each new dep, BFS to see if taskId is reachable
        val uid = currentUser.scopedUserId.value
        for (dep in newDeps) {
            val reachable = bfsReachableFrom(dep.value, taskId.value, uid.value)
            if (taskId.value in reachable) {
                return Result.failure(
                    IllegalArgumentException(
                        "Adding dependency ${taskId.value} → ${dep.value} would create a cycle",
                    ),
                )
            }
        }
        return Result.success(Unit)
    }

    /**
     * BFS from [startId] following outgoing dependency edges.
     * Returns the set of all task IDs reachable from [startId].
     */
    private suspend fun bfsReachableFrom(startId: String, targetId: String, uid: String): Set<String> {
        val visited = mutableSetOf<String>()
        val worklist = ArrayDeque<String>()
        worklist.add(startId)

        while (worklist.isNotEmpty()) {
            val current = worklist.removeFirst()
            if (current in visited) continue
            if (current == targetId) return setOf(targetId) // early exit: target reachable
            visited.add(current)
            val deps = taskDao.getDependencyIdsForUser(current, uid).first()
            for (depId in deps) {
                if (depId !in visited) worklist.add(depId)
            }
        }
        return visited
    }

    /**
     * Performs BFS from [taskId] following outgoing dependency edges.
     * Returns [DependencyAnalysis] describing the cycle state.
     *
     * The algorithm uses BFS to explore all reachable nodes from [taskId].
     * If [taskId] is reached via a path that doesn't start with the initial edge
     * (i.e., a non-trivial cycle), a cycle exists.
     */
    override suspend fun analyzeDependencies(taskId: TaskId): DependencyAnalysis {
        val uid = currentUser.scopedUserId.value
        val startId = taskId.value

        // visited tracks nodes we've already processed to avoid re-exploring
        val visited = mutableSetOf<String>()
        // cycleEdges records the edges that form the cycle
        val cycleEdges = mutableListOf<Pair<String, String>>()
        // path stores (from, to) edges for backtracking
        val path = mutableListOf<Pair<String, String>>()
        // worklist: nodes to explore
        val worklist = ArrayDeque<String>()
        worklist.add(startId)

        while (worklist.isNotEmpty()) {
            val current = worklist.removeFirst()
            if (current in visited) continue
            visited.add(current)

            // Get immediate dependencies of current task — take only first emission
            val deps = taskDao.getDependencyIdsForUser(current, uid.value).first()

            for (depId in deps) {
                if (depId == startId) {
                    // Found a path from taskId back to itself — cycle detected
                    val cycleEdge = current to startId
                    if (cycleEdge !in cycleEdges) {
                        cycleEdges.add(cycleEdge)
                    }
                    // Add all edges on the cycle path
                    val cycleStartIdx = path.indexOfFirst { it.second == depId }
                    if (cycleStartIdx >= 0) {
                        for (i in cycleStartIdx until path.size) {
                            if (path[i] !in cycleEdges) {
                                cycleEdges.add(path[i])
                            }
                        }
                        if (cycleEdge !in cycleEdges) {
                            cycleEdges.add(cycleEdge)
                        }
                    }
                    continue
                }

                if (depId !in visited) {
                    val edge = current to depId
                    path.add(edge)
                    worklist.add(depId)
                }
            }
        }

        if (cycleEdges.isEmpty()) {
            return DependencyAnalysis(containsCycle = false, blockers = emptyList())
        }

        // Convert cycle edges to TaskDependency objects
        val blockers = cycleEdges.mapNotNull { (from, to) ->
            val allDeps = taskDao.observeTypedDependenciesForUser(from, uid.value).first()
            val crossRef = allDeps.find { it.dependsOnTaskId == to }
            crossRef?.let {
                TaskDependency(
                    ownerTaskId = TaskId.fromString(from),
                    dependencyTaskId = TaskId.fromString(to),
                    verb = DependencyVerb.valueOf(it.verb),
                )
            }
        }

        return DependencyAnalysis(containsCycle = true, blockers = blockers)
    }
}
