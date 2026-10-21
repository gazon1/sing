package com.singularity.todo.feature.tasks

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tags.TagId
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
 * @param uid the user whose tasks are being read.
 * @param source the task rows to enrich.
 */
internal fun TaskDao.withExtras(uid: UserId, source: Flow<List<TaskEntity>>): Flow<List<Task>> {
    val tagsFlow = observeTagCrossRefs(uid.value).map { rows ->
        rows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.tagId } }
    }
    val depsFlow = observeDependencyCrossRefs(uid.value).map { rows ->
        rows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.dependsOnTaskId }.toSet() }
    }
    val extrasFlow = combine(tagsFlow, depsFlow) { tags, deps ->
        TaskExtras(tagsByTask = tags, depsByTask = deps)
    }
    return combine(source, extrasFlow) { rows, extras ->
        rows.map { entity ->
            entity.toTask(
                tags = extras.tagsByTask[entity.id].orEmpty().map { TagId.fromString(it) },
                dependsOn = extras.depsByTask[entity.id].orEmpty().map { TaskId.fromString(it) }.toSet(),
            )
        }
    }
}

/** The cross-references of a set of tasks, indexed by the task they belong to. */
private data class TaskExtras(
    val tagsByTask: Map<String, List<String>>,
    val depsByTask: Map<String, Set<String>>,
)
