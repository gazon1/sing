package com.singularity.todo.feature.tasks

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
internal fun TaskDao.withExtras(
    uid: UserId,
    source: Flow<List<TaskEntity>>,
): Flow<List<Task>> =
    combine(
        observeTagCrossRefs(uid.value),
        // One read of the dependency table, not two: the blocking edges and the
        // follow-ups are two projections of the same rows, and collecting the
        // table once per projection read the whole graph twice per emission.
        observeDependencyCrossRefs(uid.value),
    ) { tagRows, depRows ->
        taskExtras(tagRows, depRows)
    }.let { extrasFlow -> combine(source, extrasFlow) { rows, extras -> rows.enrichedWith(extras) } }

/**
 * The same enrichment, for the rows behind one **selector** — the subtasks of one
 * parent, the members of one series.
 *
 * [withExtras] asks the profile-wide question, which is right when the rows are the
 * whole profile and wrong when they are five: on a 50k profile the detail screen's
 * subtask list materialised ~75k tag edges to read the few that its own rows carry.
 * This reads the edges of the rows [source] emits, and nothing else.
 *
 * ## Why the cross-references are passed in, not derived from the rows
 *
 * The obvious shape derives the ids from [source] and asks `IN (:taskIds)`. That
 * shape has a ceiling: SQLite binds one host parameter per id and refuses the
 * statement past its limit (`too many SQL variables`), and **nothing bounds the
 * number of subtasks a parent may have** — `watchSubtasksForUser` has no `LIMIT`
 * and no domain rule caps it. A parent with enough children would have failed in
 * the read that was introduced to keep large profiles safe.
 *
 * So each caller names its selector instead: the query a subtask read already runs
 * is the subquery the cross-reference read joins on. The parent id is one bound
 * parameter whatever the number of children.
 *
 * Both cross-reference flows must select the same rows [source] does; the caller
 * builds them from the same selector for that reason.
 *
 * @param source the rows to enrich — the caller's own read of the selector.
 * @param tagsFlow the tag edges of that selector.
 * @param dependenciesFlow the dependency edges of that selector, of every verb.
 */
internal fun withScopedExtras(
    source: Flow<List<TaskEntity>>,
    tagsFlow: Flow<List<TaskTagCrossRef>>,
    dependenciesFlow: Flow<List<TaskDependencyCrossRef>>,
): Flow<List<Task>> =
    // One subscription in total: `source` is not collected a second time to pair the
    // result back together, so the extras cannot describe a previous list of rows.
    combine(source, tagsFlow, dependenciesFlow) { rows, tagRows, depRows ->
        rows.enrichedWith(taskExtras(tagRows, depRows))
    }

/** The rows as tasks, carrying the tags and edges [extras] holds for each. */
private fun List<TaskEntity>.enrichedWith(extras: TaskExtras): List<Task> =
    map { entity ->
        entity.toTask(
            tags = extras.tagsByTask[entity.id].orEmpty().map { TagId.fromString(it) },
            blockedBy =
                extras.depsByTask[entity.id]
                    .orEmpty()
                    .map { TaskId.fromString(it) }
                    .toSet(),
            followsUp =
                extras.followUpsByTask[entity.id]
                    .orEmpty()
                    .map { TaskId.fromString(it) }
                    .toSet(),
        )
    }

/**
 * Both projections of the dependency edges, indexed by the task that holds the
 * edge: the **blocking** graph and the ordering graph's **follow-up** half.
 *
 * One walk of the list, because the two answers are two questions about the same
 * rows. They used to be two `filter` + `groupBy` passes, which read the list
 * twice per emission to build two maps from it.
 *
 * A verb that is neither `BLOCKS` nor `FOLLOWS_UP` belongs to neither projection
 * and is dropped here, exactly as the two filters dropped it. The typed read
 * still sees every verb.
 */
private fun List<TaskDependencyCrossRef>.splitByVerb(): Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
    val blocking = mutableMapOf<String, MutableSet<String>>()
    val followUps = mutableMapOf<String, MutableSet<String>>()
    for (row in this) {
        val into =
            when (row.verb) {
                DependencyVerb.BLOCKS.name -> blocking

                DependencyVerb.FOLLOWS_UP.name -> followUps

                // Another verb is neither projection: the two maps answer the
                // blocking and the follow-up question, and nothing else.
                else -> continue
            }
        into.getOrPut(row.taskId, ::mutableSetOf) += row.dependsOnTaskId
    }
    return blocking to followUps
}

/**
 * The tags and both dependency projections of one read, built in one pass.
 *
 * [withExtras] and [withScopedExtras] ask the same question of the same rows, so
 * they build the answer here. Two hand-written copies of the grouping were how
 * the profile-wide read and the scoped read drifted apart in the first place.
 *
 * The dependency rows are walked **once**: the two projections used to be two
 * `filter` + `groupBy` passes over the same list, which on a 50k profile meant
 * materialising the blocking map and the follow-up map from two full scans.
 */
private fun taskExtras(
    tagRows: List<TaskTagCrossRef>,
    depRows: List<TaskDependencyCrossRef>,
): TaskExtras {
    val (depsByTask, followUpsByTask) = depRows.splitByVerb()
    return TaskExtras(
        tagsByTask = tagRows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.tagId } },
        depsByTask = depsByTask,
        followUpsByTask = followUpsByTask,
    )
}

/** The cross-references of a set of tasks, indexed by the task they belong to. */
private data class TaskExtras(
    val tagsByTask: Map<String, List<String>>,
    val depsByTask: Map<String, Set<String>>,
    val followUpsByTask: Map<String, Set<String>>,
)

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
internal fun TaskDao.blockingDependencyIdsForUser(
    taskId: String,
    userId: String,
): Flow<List<String>> = getBlockingEdgeIdsForUser(taskId, userId, DependencyVerb.BLOCKS.name)

/**
 * The ids of the tasks [taskId] follows up on.
 *
 * The ordering graph's other projection, for the single-task read. It has to
 * agree with [withExtras] for the same reason the blocking read has to: the
 * detail screen and the list must say the same thing about a task's edges.
 *
 * @param taskId the task whose follow-up edges are read.
 * @param userId the owner of the task.
 */
internal fun TaskDao.followUpDependencyIdsForUser(
    taskId: String,
    userId: String,
): Flow<List<String>> = getBlockingEdgeIdsForUser(taskId, userId, DependencyVerb.FOLLOWS_UP.name)

/**
 * The task as the database holds it — the row, the tags and the blocking edges.
 *
 * This is the assembly the wire needs. A task that travels to the server is a
 * claim about the work, and a claim has to be true: on the wire an empty tag
 * list says "the work has no tags" and an empty dependency set says "the work
 * waits for nothing". The server applies the claim, and every other device
 * loses the tags on the next pull.
 *
 * So the parts come from the database, not from the caller. A caller that read
 * a task through `get()` holds no tags and no edges — they live in cross-ref
 * tables, and the read was not interested in them — and the emptiness of that
 * read is not the truth about the work.
 *
 * @param id the task to assemble.
 * @param uid the owner of the task.
 * @return the task as the database holds it, or null when there is no such row.
 */
internal suspend fun TaskDao.freshTask(
    id: String,
    uid: UserId,
): Task? {
    val row = getByIdForUser(id, uid.value) ?: return null
    val tags = getTagIdsForUser(id, uid.value).first().map { TagId.fromString(it) }
    val deps = blockingDependencyIdsForUser(id, uid.value).first().map { TaskId.fromString(it) }.toSet()
    val followUps =
        getBlockingEdgeIdsForUser(id, uid.value, DependencyVerb.FOLLOWS_UP.name)
            .first()
            .map { TaskId.fromString(it) }
            .toSet()
    return row.toTask(tags = tags, blockedBy = deps, followsUp = followUps)
}

/**
 * The tasks as the database holds them — the row, the tags and the blocking
 * edges — read in one pass.
 *
 * The same answer [freshTask] gives for one task, for many: the cross-refs are
 * read once for the whole set rather than once per task. A bulk write that
 * pushes a row it has read without its cross-refs sends "the work has no tags"
 * for every row it touches.
 *
 * @param rows the task rows to assemble.
 * @param uid the owner of the tasks.
 */
internal suspend fun TaskDao.freshTasks(
    rows: List<TaskEntity>,
    uid: UserId,
): List<Task> {
    if (rows.isEmpty()) return emptyList()
    // The same three projections the reading paths build, through the same builder —
    // so a bulk write and a list read cannot answer differently about a task's tags
    // or its edges.
    return rows.enrichedWith(
        taskExtras(
            tagRows = listAllTagsForUser(uid.value),
            // One read of the dependency table for both projections: the blocking
            // set and the follow-up set are two questions about the same rows.
            depRows = listAllDependenciesForUser(uid.value),
        ),
    )
}
