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
 * @property containsCycle `true` if adding [cycleEdges] would create a directed cycle.
 * @property cycleEdges The subset of edges that participate in the cycle, if any.
 *
 * ## Why these are not called blockers
 *
 * A **blocker** is an open prerequisite: the thing a task waits for, and the
 * thing that refuses a close. A **cycle edge** is a different thing: it is a
 * relation that orders the work — "follows up on", "duplicates", "fixes" — and
 * takes part in a cycle of *order*. The two graphs overlap and are not the
 * same, so the two words are not the same: two tasks that follow up on each
 * other are in a cycle, and neither of them is waiting for the other.
 */
data class DependencyAnalysis(val containsCycle: Boolean, val cycleEdges: List<TaskDependency>)
