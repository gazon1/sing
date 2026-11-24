package com.singularity.todo.feature.tasks

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
        TaskExtras(
            tagsByTask = tagRows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.tagId } },
            depsByTask = depRows.groupByVerb(DependencyVerb.BLOCKS) { it.dependsOnTaskId },
            followUpsByTask = depRows.groupByVerb(DependencyVerb.FOLLOWS_UP) { it.dependsOnTaskId },
        )
    }.let { extrasFlow -> combine(source, extrasFlow) { rows, extras -> rows.enrichedWith(extras) } }

/**
 * The same enrichment, for a set of rows that is small by construction — the
 * subtasks of one task, the members of one series.
 *
 * [withExtras] asks the profile-wide question, which is right when the rows are
 * the whole profile and wrong when they are five: on a 50k profile the detail
 * screen's subtask list materialised ~75k tag edges to read the few that its own
 * rows carry. This reads the edges of the rows given, and nothing else.
 *
 * **The rows must stay small.** The ids travel as bound `IN (:taskIds)` variables,
 * and SQLite caps how many a statement may bind. Both callers are bounded by
 * construction — the subtasks of one parent, and a series, which
 * `RecurringSeriesWindow.MAX_FUTURE` caps at ten occurrences — so the limit is not
 * reachable today. A caller that named thousands of rows would have to batch them
 * or read the profile-wide path instead.
 *
 * @param uid the owner of the tasks.
 * @param source the rows to enrich — small enough to name in a query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TaskDao.withScopedExtras(
    uid: UserId,
    source: Flow<List<TaskEntity>>,
): Flow<List<Task>> =
    // One collection of `source` drives both the ids and the rows they end in. The
    // earlier shape derived the ids from one subscription and paired the extras with
    // a second one, so the two could disagree for an emission and the source query
    // ran twice — in the very path this read exists to make cheap.
    source.flatMapLatest { rows ->
        val ids = rows.map { it.id }
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                observeTagCrossRefsForTasks(ids, uid.value),
                observeDependencyCrossRefsForTasks(ids, uid.value),
            ) { tagRows, depRows ->
                rows.enrichedWith(
                    TaskExtras(
                        tagsByTask = tagRows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.tagId } },
                        depsByTask = depRows.groupByVerb(DependencyVerb.BLOCKS) { it.dependsOnTaskId },
                        followUpsByTask = depRows.groupByVerb(DependencyVerb.FOLLOWS_UP) { it.dependsOnTaskId },
                    ),
                )
            }
        }
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
 * One projection of the dependency edges, by the verb that gives it its meaning.
 *
 * The blocking graph and the ordering graph's follow-up half are the same table
 * asked two different questions; both are indexed by the task that holds the edge.
 */
private fun List<TaskDependencyCrossRef>.groupByVerb(
    verb: DependencyVerb,
    target: (TaskDependencyCrossRef) -> String,
): Map<String, Set<String>> =
    filter { it.verb == verb.name }
        .groupBy { it.taskId }
        .mapValues { (_, rows) -> rows.map(target).toSet() }

/** The cross-references of a set of tasks, indexed by the task they belong to. */
private data class TaskExtras(
    val tagsByTask: Map<String, List<String>> = emptyMap(),
    val depsByTask: Map<String, Set<String>> = emptyMap(),
    val followUpsByTask: Map<String, Set<String>> = emptyMap(),
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
    val tagsByTask =
        listAllTagsForUser(uid.value)
            .groupBy { it.taskId }
            .mapValues { (_, refs) -> refs.map { TagId.fromString(it.tagId) } }
    val depsByTask =
        listAllDependenciesForUser(uid.value)
            .filter { it.verb == DependencyVerb.BLOCKS.name }
            .groupBy { it.taskId }
            .mapValues { (_, refs) -> refs.map { TaskId.fromString(it.dependsOnTaskId) }.toSet() }
    val followUpsByTask =
        listAllDependenciesForUser(uid.value)
            .filter { it.verb == DependencyVerb.FOLLOWS_UP.name }
            .groupBy { it.taskId }
            .mapValues { (_, refs) -> refs.map { TaskId.fromString(it.dependsOnTaskId) }.toSet() }
    return rows.map { row ->
        row.toTask(
            tags = tagsByTask[row.id].orEmpty(),
            blockedBy = depsByTask[row.id].orEmpty(),
            followsUp = followUpsByTask[row.id].orEmpty(),
        )
    }
}
