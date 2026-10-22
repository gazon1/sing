package com.singularity.todo.feature.tasks

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The tags and the dependencies of a set of tasks, loaded with the tasks.
 *
 * ## Why this is here and not in the repository
 *
 * A task carries two things that live in other tables: the tags it has and
 * the tasks it depends on. Reading a task therefore means reading the task
 * *and* those two cross-references.
 *
 * The reading was a private method of the task repository, which was the only
 * reader — until the search index started reading tasks too. A second reader
 * would have been a second copy of the same three-flow `combine`, and the two
 * copies would have drifted: a task found by the search would have carried
 * its tags, and a task found by the repository would not.
 *
 * So the reading is here, next to the mapper it ends in, and both readers use
 * it.
 *
 * ## Only the blocking edges become dependencies
 *
 * The cross-reference table carries the *kind* of every relationship. A task's
 * dependency set is only the relationships that block it: a task that "follows
 * up" on another is not waiting for it, and reporting it as waiting is the
 * bug this filter fixes.
 *
 * This is the **blocking graph**. The other projection of the same table is
 * the **ordering graph** — every edge, whatever its verb — and it is where the
 * cycle analysis looks. The two are named apart in the DAO
 * ([TaskDao.getBlockingEdgeIdsForUser] and
 * [TaskDao.getOrderingEdgeIdsForUser]) so that a reader has to say which one
 * it means: the same table, read for two different questions, and the
 * difference between them is the whole point.
 *
 * @param uid the user whose tasks are being read.
 * @param source the task rows to enrich.
 */
internal fun TaskDao.withExtras(uid: UserId, source: Flow<List<TaskEntity>>): Flow<List<Task>> {
    val tagsFlow = observeTagCrossRefs(uid.value).map { rows ->
        rows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.tagId } }
    }
    val depsFlow = observeDependencyCrossRefs(uid.value).map { rows ->
        rows.filter { it.verb == DependencyVerb.BLOCKS.name }
            .groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.map { it.dependsOnTaskId }.toSet() }
    }
    val extrasFlow = combine(tagsFlow, depsFlow) { tags, deps ->
        TaskExtras(tagsByTask = tags, depsByTask = deps)
    }
    return combine(source, extrasFlow) { rows, extras ->
        rows.map { entity ->
            entity.toTask(
                tags = extras.tagsByTask[entity.id].orEmpty().map { TagId.fromString(it) },
                blockedBy = extras.depsByTask[entity.id].orEmpty().map { TaskId.fromString(it) }.toSet(),
            )
        }
    }
}

/** The cross-references of a set of tasks, indexed by the task they belong to. */
private data class TaskExtras(val tagsByTask: Map<String, List<String>>, val depsByTask: Map<String, Set<String>>)

/**
 * The ids of the dependencies of [taskId] that block it — the **blocking
 * graph**.
 *
 * The same filter [withExtras] applies, for the single-task read. The two have
 * to agree: a task that carries one dependency set in a list and another when
 * it is opened on its own is a task whose answer depends on where it was read.
 *
 * @param taskId the task whose dependencies are read.
 * @param userId the owner of the task.
 */
internal fun TaskDao.blockingDependencyIdsForUser(taskId: String, userId: String): Flow<List<String>> =
    getBlockingEdgeIdsForUser(taskId, userId, DependencyVerb.BLOCKS.name)
