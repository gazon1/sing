package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * Why an item is on the attention list.
 *
 * The declaration order **is** the relevance order: the first signal outranks the
 * ones after it. An item that qualifies under several signals appears once, under
 * the most relevant one — a task that is both late and waiting is late, and a list
 * that named both reasons would be twice as long and no more useful.
 */
enum class AttentionSignal {
    /** Past its due date, still open. */
    OVERDUE,

    /** Due today or due tomorrow. */
    DUE_SOON,

    /** Waiting on an unfinished prerequisite. */
    BLOCKED,

    /** The project's deadline has arrived and its work is not finished. */
    PROJECT_DEADLINE,
}

/** What an attention item points at. */
sealed interface AttentionDestination {
    data class Task(val id: TaskId) : AttentionDestination

    data class Project(val id: ProjectId) : AttentionDestination
}

/**
 * One item on the attention list.
 *
 * @param key a stable identity for the item — `task:<id>` or `project:<id>`. The
 *   screen uses it to remember that the user set an item aside, so it must not
 *   change between two openings of the same list.
 * @param title the item's own name — a task's title or a project's name.
 * @param reason the one reason the item is there, in the user's words.
 */
data class AttentionNudge(
    val key: String,
    val signal: AttentionSignal,
    val title: String,
    val reason: String,
    val destination: AttentionDestination,
)

/**
 * The attention list: the items that want the user's attention, most relevant first.
 *
 * ## Why this is derived and never stored
 *
 * Every value here is a statement about a row — it is late, it is due soon, it is
 * waiting, its project ran out of time. Storing the statement would create a second
 * version of the truth that disagrees with the row it was derived from after the
 * first edit, so the list is rebuilt from the rows every time it is shown. This is
 * the same rule `TaskComputed` states for every other derived value.
 *
 * ## Why the existing rules are asked, not rewritten
 *
 * Overdue is `TaskComputed.isOverdue` — the rule the health buckets and the agenda
 * use. Blocked is `Blocking.blockedTaskIds`, which reads only the edges that block
 * (a task that follows up on another is not waiting for it). Due soon is the app's
 * own two date buckets. Each is asked through `Selector.matches`, so the list cannot
 * disagree with the counts shown beside it.
 *
 * ## What is left out, and why
 *
 * - **Finished, discarded and deferred work.** A nudge is about work still to do.
 *   Deferred work was parked on purpose; a nudge for it would be the app arguing
 *   with the user.
 * - **Work in a closed project.** A closed project says its work is finished.
 * - **Stagnation.** The attention list answers "what should I deal with first", and
 *   an idle task is not more urgent than a late one. Stagnation is the Health tab's
 *   own bucket.
 *
 * @param tasks the whole task list — the blocked set is computed from it, because a
 *   blocker that is finished must not make its dependant wait.
 * @param projects the project list, closed ones included: a closed project is what
 *   keeps its tasks out of the list.
 * @param today the reference day.
 * @param now the current instant, for the selector context.
 */
fun buildAttentionNudges(
    tasks: List<Task>,
    projects: List<Project>,
    today: LocalDate,
    now: Instant,
): List<AttentionNudge> {
    val closedProjectIds = projects.filter { it.closedAt != null }.map { it.id }.toSet()
    val blockedIds = Blocking.blockedTaskIds(tasks)
    val context = SelectorContext(today = today, now = now, blockedIds = blockedIds)

    val overdue = Selector.DateBucket(RelativeBucket.Overdue)
    val dueSoon =
        Selector.AnyOf(
            listOf(
                Selector.DateBucket(RelativeBucket.Today),
                Selector.DateBucket(RelativeBucket.Tomorrow),
            ),
        )
    val blocked = Selector.Blocked

    val taskNudges =
        tasks
            .filter { it.isAttentionCandidate(closedProjectIds) }
            .mapNotNull { task ->
                val dueDate = task.dueDate
                when {
                    overdue.matches(task, context) -> {
                        dueDate?.let { date ->
                            Ranked(
                                task.nudge(AttentionSignal.OVERDUE, "Overdue since ${date.relativeLabel(today)}"),
                                date,
                            )
                        }
                    }

                    dueSoon.matches(task, context) -> {
                        dueDate?.let { date ->
                            Ranked(
                                task.nudge(AttentionSignal.DUE_SOON, "Due ${date.relativeLabel(today)}"),
                                date,
                            )
                        }
                    }

                    blocked.matches(task, context) -> {
                        Ranked(
                            task.nudge(AttentionSignal.BLOCKED, "Waiting on something that is not finished"),
                            dueDate,
                        )
                    }

                    else -> {
                        null
                    }
                }
            }

    // A project's deadline is a promise about a body of work, so it is worth
    // attention while the promise is unmet: the deadline has arrived, the project
    // is still open, and there is work left in it. A deferred task still counts as
    // work left — it is parked, not finished.
    val unfinishedByProject =
        tasks
            .filter { !it.isCompleted && !it.isTrashed }
            .groupingBy { it.projectId }
            .eachCount()

    val projectNudges =
        projects
            .filter { !it.isDeleted && it.closedAt == null }
            .mapNotNull { project ->
                val deadline = project.dueDate
                if (deadline == null || deadline > today) return@mapNotNull null
                val unfinished = unfinishedByProject[project.id] ?: 0
                if (unfinished == 0) return@mapNotNull null
                val noun = if (unfinished == 1) "task" else "tasks"
                Ranked(
                    AttentionNudge(
                        key = projectKey(project.id),
                        signal = AttentionSignal.PROJECT_DEADLINE,
                        title = project.name,
                        reason = "Its deadline has passed with $unfinished unfinished $noun",
                        destination = AttentionDestination.Project(project.id),
                    ),
                    deadline,
                )
            }

    return (taskNudges + projectNudges)
        .sortedWith(
            compareBy(
                { it.nudge.signal.ordinal },
                { it.dueDate },
                { it.nudge.key },
            ),
        ).map { it.nudge }
}

/**
 * A nudge and the date that makes it due — the sort key, and nothing else.
 *
 * The date is not part of the nudge the user sees: the reason line already says
 * when the item is due, and a date the screen never shows is a field the screen
 * has to keep in step with the reason.
 */
private data class Ranked(val nudge: AttentionNudge, val dueDate: LocalDate?)

/**
 * Work still to do: open, not discarded, not deferred, and not part of a project
 * whose work was declared finished.
 */
private fun Task.isAttentionCandidate(closedProjectIds: Set<ProjectId>): Boolean =
    !isCompleted &&
        !isTrashed &&
        !someday &&
        (projectId == null || projectId !in closedProjectIds)

private fun Task.nudge(
    signal: AttentionSignal,
    reason: String,
): AttentionNudge =
    AttentionNudge(
        key = taskKey(id),
        signal = signal,
        title = title,
        reason = reason,
        destination = AttentionDestination.Task(id),
    )

/** The date as the user would say it, with the date itself for anything further off. */
private fun LocalDate.relativeLabel(today: LocalDate): String =
    when (this) {
        today -> "today"
        today.plus(1, DateTimeUnit.DAY) -> "tomorrow"
        today.minus(1, DateTimeUnit.DAY) -> "yesterday"
        else -> toString()
    }

private fun taskKey(id: TaskId): String = "task:${id.value}"

private fun projectKey(id: ProjectId): String = "project:${id.value}"
