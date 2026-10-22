package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * The named sets of tasks a reader may ask for.
 *
 * ## Why a vocabulary and not a filter
 *
 * "The tasks I am in the middle of" is a thing the app already knows how to show — it
 * is the Inbox tab, the Today section, the overdue list. A reader who has to learn the
 * app's filter syntax to ask for it will not; a reader who says "overdue" gets what the
 * app calls overdue. So the names here are the app's names, and each one resolves to the
 * same rule the screen uses.
 *
 * The names are also the wire format. The serial names are lower case, because they
 * travel to a model that reads English, and a value the model has to guess at is a value
 * it will get wrong.
 */
@Serializable
enum class TaskSelection {
    /** What is open and not deferred: the Inbox. */
    @SerialName("inbox")
    INBOX,

    /** Due today. */
    @SerialName("today")
    TODAY,

    /** Due later. */
    @SerialName("upcoming")
    UPCOMING,

    /** Past its due date and still open. */
    @SerialName("overdue")
    OVERDUE,

    /** Waiting on something that is not finished. */
    @SerialName("blocked")
    BLOCKED,

    /** Open, undated, and untouched for a long time. */
    @SerialName("stagnant")
    STAGNANT,

    /** Pinned by the user. */
    @SerialName("pinned")
    PINNED,

    /** Deferred to someday. */
    @SerialName("someday")
    SOMEDAY,

    /** Open, whatever else is true of it. */
    @SerialName("active")
    ACTIVE,

    /** Finished. */
    @SerialName("completed")
    COMPLETED,

    /** Everything that is not in the trash. */
    @SerialName("all")
    ALL,
}

/**
 * A selection, narrowed.
 *
 * The narrowing is always *and*: a request for the inbox within a project, tagged
 * "home", with high priority, is the intersection of the three. That is the direction
 * people read narrowing in — each word removes something — and the other direction
 * ("any of these tags") is a different question that the search tool answers.
 */
@Serializable
data class TaskSelectionRequest(
    /** Which named set to read. */
    val selection: TaskSelection = TaskSelection.INBOX,
    /** Keep only the tasks in this project. */
    val projectId: String? = null,
    /** Keep only the tasks carrying **every** one of these tags. */
    val tagIds: List<String> = emptyList(),
    /** Keep only the tasks at one of these priorities. */
    val priorities: List<String> = emptyList(),
)

/**
 * Turns a [TaskSelectionRequest] into a rule about a task.
 *
 * ## Why the rule is computed here and not in the tool
 *
 * A selection is a question about the user's tasks, and the app already answers it — in
 * the Inbox tab, in the agenda's overdue section, in the statistics screen. If the tool
 * worked out the answer its own way, the tool and the app would answer the same question
 * differently, and the reader would be left choosing whom to believe.
 *
 * So the rule is built out of the app's own predicates: [TaskDomain.matchesFilter] for
 * the filters the app has, and [Selector.matches] for the ones it does not (blocked,
 * stagnant). One source of truth, reached from two directions.
 *
 * ## Why the base is always "everything", and not a narrower query
 *
 * The repository's own filters and the domain's own filters do not always agree. The
 * repository answers `Inbox` with every live task, while the domain's answer to `Inbox`
 * leaves out the deferred ones; the repository answers `ByStatuses` with nothing at
 * all. Reading through the repository's narrower filters would therefore have the tool
 * report a different set from the screen.
 *
 * So the tool reads the live tasks and applies the domain's rule to them. The list is
 * the same size either way — the read is a single query over the user's own rows — and
 * the answer is the app's answer.
 */
object TaskSelectionResolver {

    /**
     * The filter the read starts from.
     *
     * Always the live tasks, for the reason in the class comment: the narrower
     * repository filters do not agree with the domain's rules, and a reader is better
     * served by the app's answer than by a fast wrong one.
     */
    fun baseFilter(): TaskFilter = TaskFilter.All

    /**
     * Whether [task] belongs to the set [request] asks for.
     *
     * @param context the reference date and the blocked set, computed once by the caller
     *   so that classifying a whole list stays linear.
     */
    fun matches(
        request: TaskSelectionRequest,
        task: Task,
        context: SelectorContext,
        stagnationWindow: Duration = TaskHealth.DEFAULT_STAGNATION_WINDOW,
    ): Boolean {
        if (!matchesSelection(request.selection, task, context, stagnationWindow)) return false
        return matchesNarrowing(request, task)
    }

    /**
     * Whether [task] belongs to the named set [selection].
     *
     * The named sets that the domain has a filter for are answered by
     * [TaskDomain.matchesFilter]; the two that it does not — blocked and stagnant — are
     * answered by the same [Selector] the agenda uses.
     */
    private fun matchesSelection(
        selection: TaskSelection,
        task: Task,
        context: SelectorContext,
        stagnationWindow: Duration,
    ): Boolean = when (selection) {
        // The inbox is what is *open* and not deferred. The domain's `Inbox` filter
        // leaves the finished tasks in, because a filter and a screen are not the same
        // question — and the screen the reader is thinking of is the one that shows
        // what is still to do.
        TaskSelection.INBOX ->
            TaskDomain.matchesFilter(task, TaskFilter.Inbox, context.today) &&
                TaskDomain.matchesFilter(
                    task,
                    TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                    context.today,
                )

        TaskSelection.TODAY ->
            TaskDomain.matchesFilter(task, TaskFilter.Today, context.today)

        TaskSelection.UPCOMING ->
            TaskDomain.matchesFilter(task, TaskFilter.Upcoming, context.today)

        TaskSelection.SOMEDAY ->
            TaskDomain.matchesFilter(task, TaskFilter.Someday, context.today)

        TaskSelection.PINNED ->
            TaskDomain.matchesFilter(task, TaskFilter.Pinned, context.today)

        TaskSelection.ALL ->
            TaskDomain.matchesFilter(task, TaskFilter.All, context.today)

        TaskSelection.OVERDUE ->
            TaskDomain.matchesFilter(
                task,
                TaskFilter.ByDateBucket(RelativeBucket.Overdue, context.today),
                context.today,
            )

        TaskSelection.ACTIVE ->
            TaskDomain.matchesFilter(
                task,
                TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                context.today,
            )

        TaskSelection.COMPLETED ->
            TaskDomain.matchesFilter(
                task,
                TaskFilter.ByStatuses(setOf(TaskStatus.Completed)),
                context.today,
            )

        TaskSelection.BLOCKED ->
            TaskDomain.matchesFilter(
                task,
                TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                context.today,
            ) && Selector.Blocked.matches(task, context)

        TaskSelection.STAGNANT ->
            TaskDomain.matchesFilter(
                task,
                TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                context.today,
            ) && Selector.Stagnant(stagnationWindow).matches(task, context)
    }

    /**
     * Whether [task] survives the narrowing in [request].
     *
     * Priorities are compared without regard to case, because the names travel to a
     * model that will write "high" where the app writes "High". A priority the app
     * does not know is not a match — it does not become "no priority" either, because
     * a reader that asked for "urgent" and got "none" has been answered wrongly.
     */
    private fun matchesNarrowing(request: TaskSelectionRequest, task: Task): Boolean {
        val projectId = request.projectId
        if (projectId != null && task.projectId?.value != projectId) return false

        if (request.tagIds.isNotEmpty()) {
            val taskTags = task.tags.map { it.value }.toSet()
            if (!request.tagIds.all { it in taskTags }) return false
        }

        if (request.priorities.isNotEmpty()) {
            val priority = task.priority.name
            if (request.priorities.none { it.equals(priority, ignoreCase = true) }) return false
        }

        return true
    }

    /**
     * A short, stable description of the request, for the cursor.
     *
     * The cursor says where in a list the reader was. That only means something if the
     * list is the same list, so the cursor carries what it was reading: a cursor from
     * one selection must not silently be used for another.
     */
    fun fingerprint(request: TaskSelectionRequest): String = listOf(
        request.selection.name,
        request.projectId.orEmpty(),
        request.tagIds.sorted().joinToString(","),
        request.priorities.sorted().joinToString(","),
    ).joinToString("|")
}
