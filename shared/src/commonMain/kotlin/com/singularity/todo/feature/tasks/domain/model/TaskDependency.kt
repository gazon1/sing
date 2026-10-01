package com.singularity.todo.feature.tasks.domain.model

/**
 * A typed edge in the task dependency graph.
 *
 * @property ownerTaskId The task that "owns" this dependency edge (has the link).
 * @property dependencyTaskId The target of the edge.
 * @property verb The semantic relationship from [ownerTaskId] to [dependencyTaskId].
 */
data class TaskDependency(val ownerTaskId: TaskId, val dependencyTaskId: TaskId, val verb: DependencyVerb)

/**
 * Result of a dependency-cycle analysis pass.
 *
 * @property containsCycle `true` if adding [blockers] would create a directed cycle.
 * @property blockers The subset of edges that participate in the cycle, if any.
 */
data class DependencyAnalysis(val containsCycle: Boolean, val blockers: List<TaskDependency>)
