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
 * is the Today section, the overdue list. A reader who has to learn the app's filter
 * syntax to ask for it will not; a reader who says "overdue" gets what the app calls
 * overdue. So the names here are the app's names, and each one resolves to the same
 * rule the screen uses. Where the app has no screen for a set, the name says what the
 * set is — [ACTIVE_NOT_DEFERRED] is every unfinished task that is not deferred.
 *
 * The names are also the wire format. The serial names are lower case, because they
 * travel to a model that reads English, and a value the model has to guess at is a value
 * it will get wrong.
 *
 * ## Why [ACTIVE_NOT_DEFERRED] carries the qualifier and [ACTIVE] does not
 *
 * [ACTIVE] is every unfinished task, the deferred (`someday`) ones included.
 * [ACTIVE_NOT_DEFERRED] is that set with the deferred tasks taken out — it is
 * narrower in one direction only: nothing [ACTIVE] holds is missing from it except
 * the deferred tasks. A name for the narrower set that sounded *broader* — a bare
 * "open", which is how the app already talks about a task that is not finished —
 * would make a model guess which of the two it wants; so the narrower set states the
 * exclusion in its name, and [ACTIVE] is the one that says nothing extra because
 * nothing is excluded from it. Read the pair as a whole and a qualifier:
 * [ACTIVE], every unfinished task; [ACTIVE_NOT_DEFERRED], the unfinished tasks that are
 * not deferred.
 */
@Serializable
enum class TaskSelection {
    /**
     * Every unfinished task that is not deferred — dated or undated.
     *
     * This is not the agenda's Inbox tab, which holds the undated tasks only. It is
     * [ACTIVE] without the deferred tasks: [ACTIVE] keeps them, this one does not, and
     * that is the whole difference between the two. The name carries both clauses so
     * that the two are not a guess.
     *
     * ## The old name, `inbox`, is gone
     *
     * The selection used to be `inbox`, which named it after the agenda tab it is *not*.
     * The alias that kept decoding it has been removed: `inbox` is not a name the wire
     * accepts any more, and a caller that sends it gets an error rather than a wider
     * answer than the one it asked for. The word belongs to the agenda's tab, and that
     * tab holds the undated tasks only — so nothing else may answer to it.
     *
     * A test pins both directions (`the old inbox name no longer decodes, and the
     * selection encodes under its new name` in `TaskReadSurfaceTest`), because a name
     * that quietly stops decoding is a break nobody notices until a caller is refused.
     *
     * ## What a rename does not break: page cursors
     *
     * A page cursor carries [TaskSelectionResolver.fingerprint], and the fingerprint
     * names the selection by its **wire name** — `active_not_deferred`, the name the
     * enum encodes under — not by the name of the Kotlin constant. So renaming the
     * constant leaves the cursors alone: the wire name did not change, so neither does
     * the fingerprint.
     *
     * A cursor minted by an older build — one that keyed the fingerprint on the
     * constant's name — does not match, and the tool refuses it as belonging to a
     * different selection rather than continuing a list it cannot vouch for. That is
     * fail-closed on purpose — the caller restarts the page and loses nothing but the
     * offset.
     */
    @SerialName("active_not_deferred")
    ACTIVE_NOT_DEFERRED,

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
 * The name this selection travels under.
 *
 * The wire name, not the name of the Kotlin constant. The constant is the code's word
 * for the thing and is free to change; the wire name is a contract — the models read
 * it, the saved prompts hold it, the cursors are keyed on it. So the things that must
 * survive a rename are keyed on this name and not on the constant.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val TaskSelection.wireName: String
    get() = TaskSelection.serializer().descriptor.getElementName(ordinal)

/**
 * A selection, narrowed.
 *
 * The narrowing is always *and*: a request for the unfinished tasks within a project,
 * tagged "home", with high priority, is the intersection of the three. That is the
 * direction people read narrowing in — each word removes something — and the other
 * direction ("any of these tags") is a different question that the search tool answers.
 */
@Serializable
data class TaskSelectionRequest(
    /** Which named set to read. */
    val selection: TaskSelection = TaskSelection.ACTIVE_NOT_DEFERRED,
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
 * the agenda's Today tab, in its overdue section, in the statistics screen. If the tool
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
 * repository answers `ByStatuses` with nothing at all, and
 * [TaskSelection.ACTIVE_NOT_DEFERRED] is a combination of two filters —
 * `NotDeferred` and `ByStatuses(Active)` — so the repository cannot answer it at all.
 * The two filters that do answer the same question twice — `NotDeferred` and `All` —
 * are kept in step by a test that pins what each read selects
 * (`TaskRepositoryContractTest`), and the selection's own rule is pinned by a test
 * that pins what it selects (`TaskSelectionResolverTest`). Reading through the
 * repository's narrower filters would therefore have the tool report a different set
 * from the screen.
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
    ): Boolean =
        when (selection) {
            // Every unfinished task that is not deferred, dated or undated. The
            // domain's `NotDeferred` filter leaves the finished tasks in, because a
            // filter and a screen are not the same question — so the active-status
            // rule is applied on top, and the reader gets what is still to do.
            //
            // The two rules are the repository's `NotDeferred` read
            // (`archived_at IS NULL AND someday = 0`, which is what the domain's
            // `NotDeferred` predicate says) plus completion. That read is pinned by
            // `TaskRepositoryContractTest`, and this combination is pinned by
            // `TaskSelectionResolverTest`; neither can drift from the other.
            //
            // This is *not* the agenda's Inbox tab, which holds the undated tasks
            // only. The two answer different questions — "what is not scheduled yet"
            // and "what is still to do" — and the tool's description says which one
            // this selection answers.
            TaskSelection.ACTIVE_NOT_DEFERRED -> {
                TaskDomain.matchesFilter(task, TaskFilter.NotDeferred, context.today) &&
                    TaskDomain.matchesFilter(
                        task,
                        TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                        context.today,
                    )
            }

            TaskSelection.TODAY -> {
                TaskDomain.matchesFilter(task, TaskFilter.Today, context.today)
            }

            TaskSelection.UPCOMING -> {
                TaskDomain.matchesFilter(task, TaskFilter.Upcoming, context.today)
            }

            TaskSelection.SOMEDAY -> {
                TaskDomain.matchesFilter(task, TaskFilter.Someday, context.today)
            }

            TaskSelection.PINNED -> {
                TaskDomain.matchesFilter(task, TaskFilter.Pinned, context.today)
            }

            TaskSelection.ALL -> {
                TaskDomain.matchesFilter(task, TaskFilter.All, context.today)
            }

            TaskSelection.OVERDUE -> {
                TaskDomain.matchesFilter(
                    task,
                    TaskFilter.ByDateBucket(RelativeBucket.Overdue, context.today),
                    context.today,
                )
            }

            TaskSelection.ACTIVE -> {
                TaskDomain.matchesFilter(
                    task,
                    TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                    context.today,
                )
            }

            TaskSelection.COMPLETED -> {
                TaskDomain.matchesFilter(
                    task,
                    TaskFilter.ByStatuses(setOf(TaskStatus.Completed)),
                    context.today,
                )
            }

            TaskSelection.BLOCKED -> {
                TaskDomain.matchesFilter(
                    task,
                    TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                    context.today,
                ) && Selector.Blocked.matches(task, context)
            }

            TaskSelection.STAGNANT -> {
                TaskDomain.matchesFilter(
                    task,
                    TaskFilter.ByStatuses(setOf(TaskStatus.Active)),
                    context.today,
                ) && Selector.Stagnant(stagnationWindow).matches(task, context)
            }
        }

    /**
     * Whether [task] survives the narrowing in [request].
     *
     * Priorities are compared without regard to case, because the names travel to a
     * model that will write "high" where the app writes "High". A priority the app
     * does not know is not a match — it does not become "no priority" either, because
     * a reader that asked for "urgent" and got "none" has been answered wrongly.
     */
    private fun matchesNarrowing(
        request: TaskSelectionRequest,
        task: Task,
    ): Boolean {
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
     *
     * The selection is named by its wire name, not by the name of the Kotlin constant.
     * The constant may be renamed without the wire changing, and the cursors already
     * handed out have to survive that rename — they are keyed on the name the wire
     * knows, which is the name that did not change.
     */
    fun fingerprint(request: TaskSelectionRequest): String =
        listOf(
            request.selection.wireName,
            request.projectId.orEmpty(),
            request.tagIds.sorted().joinToString(","),
            request.priorities.sorted().joinToString(","),
        ).joinToString("|")
}
