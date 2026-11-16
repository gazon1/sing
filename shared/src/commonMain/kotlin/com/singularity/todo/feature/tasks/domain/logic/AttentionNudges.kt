package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ui.monthAbbreviation
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
 *
 * The order is this list's own rule and not the health classifier's precedence: a
 * task that is due today *and* waiting is named as due soon, while the counts
 * beside it still count it as waiting. The two answers are about different
 * questions — "which reason is more useful now" and "what is wrong with this task"
 * — and the change's contract says so.
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
 * ## Why the state of a task is asked, not decided here
 *
 * Whether a task is late, and whether it is waiting, is answered by the health
 * classifier — the same one the Health tab's counts are made with. The list does not
 * decide either of them: a second copy of that decision is free to drift, and the
 * drift shows up as a tab calling one task late while the card beside it counts the
 * same task as fine. So the list asks [HealthRules] the question the counts ask.
 *
 * What the list owns is the *order* of the reasons, and that order is not the
 * classifier's precedence. The classifier has no "due soon" bucket, and the list
 * puts a task that is due today above one that is merely waiting — a task due today
 * that also waits is named as due soon, while the counts still call it waiting.
 * That difference is a decision, and it is written down in the change's contract
 * rather than left to the two rules to disagree about.
 *
 * The list names a subset of what the counts count: the counts cover deferred work
 * and work in a closed project, and the list names neither.
 *
 * ## What is left out, and why
 *
 * - **Finished, discarded and deferred work.** A nudge is about work still to do.
 *   Deferred work was parked on purpose; a nudge for it would be the app arguing
 *   with the user. A deferred task is still *unfinished*, though, so it counts
 *   towards a project's deadline — that one is about a body of work rather than
 *   about one row.
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
    // One owner of the state of a task. The counts beside this list are made with
    // the same classifier, so asking it here is what keeps the two from calling one
    // task two things. The default stagnation window is the right one to ask with:
    // the window only decides the "idle" bucket, and this list never names a task
    // for being idle.
    val rules = HealthRules()

    val taskNudges = taskNudges(tasks, closedProjectIds, context, rules, today)
    val projectNudges = projectNudges(projects, tasks, today)

    return (taskNudges + projectNudges)
        .sortedWith(
            // `compareBy` on a nullable date puts the nulls **first**, so the
            // sort says so itself: within one kind, an item without a date goes
            // last, not ahead of the items that have one. An item with no date
            // has no claim on being early. The kind still comes first — a
            // dateless item of a more relevant kind outranks a dated one of a
            // less relevant kind.
            compareBy<Ranked>(
                { it.nudge.signal.ordinal },
                { it.dueDate == null },
                { it.dueDate },
                { it.nudge.key },
            ),
        ).map { it.nudge }
}

/**
 * The attention items that are about one task.
 *
 * Each task contributes at most one item, under the first signal it matches — the
 * order of the `when` is the relevance order.
 *
 * The state of the task comes from the classifier; the order of the reasons is
 * this list's own rule. Where the two answer differently — a task that is due
 * today *and* waiting — the list's order decides which reason is named, and the
 * counts beside the list still count the task as waiting.
 */
private fun taskNudges(
    tasks: List<Task>,
    closedProjectIds: Set<ProjectId>,
    context: SelectorContext,
    rules: HealthRules,
    today: LocalDate,
): List<Ranked> {
    val dueSoon =
        Selector.AnyOf(
            listOf(
                Selector.DateBucket(RelativeBucket.Today),
                Selector.DateBucket(RelativeBucket.Tomorrow),
            ),
        )

    return tasks
        .filter { it.isAttentionCandidate(closedProjectIds) }
        .mapNotNull { task ->
            // The state of the task — is it late, is it waiting — is the
            // classifier's answer, not this list's.
            val state = rules.bucketOf(task, context)
            // "Due soon" is a window rather than a state, and the classifier has
            // no bucket for it: the app's own date buckets answer it.
            val dueWithinTwoDays = dueSoon.matches(task, context)
            val dueDate = task.dueDate
            when {
                state == TaskHealthBucket.OVERDUE -> {
                    dueDate?.let { date ->
                        Ranked(
                            task.nudge(AttentionSignal.OVERDUE, "Overdue since ${date.relativeLabel(today)}"),
                            date,
                        )
                    }
                }

                dueWithinTwoDays -> {
                    dueDate?.let { date ->
                        Ranked(
                            task.nudge(AttentionSignal.DUE_SOON, "Due ${date.relativeLabel(today)}"),
                            date,
                        )
                    }
                }

                state == TaskHealthBucket.BLOCKED -> {
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
}

/**
 * The attention items that are about a project.
 *
 * A project's deadline is a promise about a body of work, so it is worth
 * attention while the promise is unmet: the deadline has arrived, the project
 * is still open, and there is work left in it. A deferred task still counts as
 * work left — it is parked, not finished.
 */
private fun projectNudges(
    projects: List<Project>,
    tasks: List<Task>,
    today: LocalDate,
): List<Ranked> {
    val unfinishedByProject =
        tasks
            .filter { !it.isCompleted && !it.isTrashed }
            .groupingBy { it.projectId }
            .eachCount()

    return projects
        .filter { !it.isDeleted && it.closedAt == null }
        .mapNotNull { project ->
            val deadline = project.dueDate
            if (deadline == null || deadline > today) return@mapNotNull null
            val unfinished = unfinishedByProject[project.id] ?: 0
            if (unfinished == 0) return@mapNotNull null
            val noun = if (unfinished == 1) "task" else "tasks"
            // A deadline that is today has not passed yet, and saying it has
            // would be the app accusing the user of being late on the day the
            // date arrived.
            val reason =
                if (deadline < today) {
                    "Its deadline has passed with $unfinished unfinished $noun"
                } else {
                    "Its deadline is today with $unfinished unfinished $noun"
                }
            Ranked(
                AttentionNudge(
                    key = projectKey(project.id),
                    signal = AttentionSignal.PROJECT_DEADLINE,
                    title = project.name,
                    reason = reason,
                    destination = AttentionDestination.Project(project.id),
                ),
                deadline,
            )
        }
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

        // "Sep 8", the way the rest of the app writes a date — an ISO date in a
        // reason line reads like a log entry, not like something said to a person.
        else -> "${monthAbbreviation(month)} $dayOfMonth"
    }

private fun taskKey(id: TaskId): String = "task:${id.value}"

private fun projectKey(id: ProjectId): String = "project:${id.value}"
