package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.SectionPrefill
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.agenda
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Built-in agenda presets.
 *
 * Each preset is a pure data [AgendaDefinition] with no external dependencies —
 * it can be serialised, cached, and recreated from JSON.
 *
 * Note: SQL-optimised [Section] filtering (via `baseFilter` on sections)
 * is deferred to MR2 after [com.singularity.todo.feature.tasks.domain.model.TaskFilter]
 * subclasses are annotated with `@SerialName`. In MR1 all filtering happens in-process.
 */
object AgendaPresets {
    /**
     * All active tasks, grouped by relative date bucket — the full agenda.
     *
     * Sections are ordered narrowest-bucket-first and each one `discard`s what it
     * matches, so a task lands in exactly one section: the most specific bucket that
     * contains it. Without that, a task due today also matches "This Week" and
     * "This Month" and is rendered three times — which crashed the agenda's
     * `LazyColumn` on duplicate item keys, since `AgendaList` keys rows by task id
     * alone. "This Month" is the final catch-all and deliberately does *not* discard;
     * "No Date" sits after it but is disjoint (`dueDate == null`).
     *
     * This is the widest view there is — it holds the tasks of every other preset —
     * so it is *not* a bottom-bar tab. The three tabs are [Inbox], [Today] and
     * [Upcoming], and they are disjoint; this one is the seed a new saved view
     * starts from.
     */
    val All: AgendaDefinition =
        agenda("All") {
            section(
                "overdue",
                "Overdue",
                Selector.DateBucket(RelativeBucket.Overdue),
                order = -1,
                discard = true,
                prefill = SectionPrefill("overdue", "Overdue task"),
            )
            section(
                "today",
                "Today",
                Selector.DateBucket(RelativeBucket.Today),
                order = 0,
                discard = true,
                prefill =
                    SectionPrefill(
                        "today",
                        "New task",
                        relativeDueDate = RelativeBucket.Today,
                    ),
            )
            section(
                "yesterday",
                "Yesterday",
                Selector.DateBucket(RelativeBucket.Yesterday),
                order = 1,
                discard = true,
                prefill =
                    SectionPrefill(
                        "yesterday",
                        "Task for yesterday",
                        relativeDueDate = RelativeBucket.Yesterday,
                    ),
            )
            section(
                "tomorrow",
                "Tomorrow",
                Selector.DateBucket(RelativeBucket.Tomorrow),
                order = 2,
                discard = true,
                prefill =
                    SectionPrefill(
                        "tomorrow",
                        "Task for tomorrow",
                        relativeDueDate = RelativeBucket.Tomorrow,
                    ),
            )
            section(
                "this_week",
                "This Week",
                Selector.DateBucket(RelativeBucket.ThisWeek),
                order = 3,
                discard = true,
                prefill =
                    SectionPrefill(
                        "this_week",
                        "This week",
                        relativeDueDate = RelativeBucket.ThisWeek,
                    ),
            )
            section(
                "next_week",
                "Next Week",
                Selector.DateBucket(RelativeBucket.NextWeek),
                order = 4,
                discard = true,
                prefill =
                    SectionPrefill(
                        "next_week",
                        "Next week",
                        relativeDueDate = RelativeBucket.NextWeek,
                    ),
            )
            section(
                "this_month",
                "This Month",
                Selector.DateBucket(RelativeBucket.ThisMonth),
                order = 5,
                prefill =
                    SectionPrefill(
                        "this_month",
                        "This month",
                        relativeDueDate = RelativeBucket.ThisMonth,
                    ),
            )
            section(
                "no_date",
                "No Date",
                Selector.DateBucket(RelativeBucket.NoDate),
                order = 6,
                prefill = SectionPrefill("no_date", "No date"),
            )
        }

    /**
     * The tasks with no due date — the inbox.
     *
     * The tab used to hold every dated task as well, which made it a superset of
     * [Today] and [Upcoming]: with a single task in the database all three tabs
     * rendered the same screen. The tab now holds what its name promises — the
     * tasks that are not dated yet — and the dated ones live in [Today] (today
     * and the overdue ones) or [Upcoming] (what is coming).
     *
     * A task with no due date therefore appears in exactly one bottom-bar tab:
     * this one.
     */
    val Inbox: AgendaDefinition =
        agenda("Inbox") {
            section(
                "no_date",
                "No Date",
                Selector.DateBucket(RelativeBucket.NoDate),
                order = 0,
                prefill = SectionPrefill("no_date", "No date"),
            )
        }

    /**
     * Only today's tasks, with overdue shown at the top.
     *
     * The tab owns the two date buckets that are "now or late": [RelativeBucket.Overdue]
     * and [RelativeBucket.Today]. [Upcoming] leaves them alone, so a task lands in
     * exactly one of the two.
     */
    val Today: AgendaDefinition =
        agenda("Today") {
            section(
                "overdue",
                "Overdue",
                Selector.DateBucket(RelativeBucket.Overdue),
                order = 0,
                discard = true,
                prefill = SectionPrefill("overdue", "Overdue task"),
            )
            section(
                "today",
                "Today",
                Selector.DateBucket(RelativeBucket.Today),
                order = 1,
                prefill =
                    SectionPrefill(
                        "today",
                        "New task",
                        relativeDueDate = RelativeBucket.Today,
                    ),
            )
        }

    /**
     * What is coming up: tomorrow, the rest of this week, and next week.
     *
     * The tab used to open with "Overdue" and "Today" as well, which made it a
     * superset of the [Today] tab — the two showed the same rows under the same
     * headers. It no longer repeats them: the overdue and today's tasks belong to
     * [Today], and the undated ones to [Inbox].
     *
     * "This Week" is the week bucket minus the days [Today] owns. The bucket
     * itself spans Monday to Sunday, so it contains today and the days already
     * gone — the `Not` is what keeps the two tabs disjoint. "This Week" and "Next
     * Week" are disjoint ranges, so the last of them needs no `discard`; the
     * narrower buckets above them do, for the same reason as [All].
     */
    val Upcoming: AgendaDefinition =
        agenda("Upcoming") {
            section(
                "tomorrow",
                "Tomorrow",
                Selector.DateBucket(RelativeBucket.Tomorrow),
                order = 0,
                discard = true,
                prefill =
                    SectionPrefill(
                        "tomorrow",
                        "Task for tomorrow",
                        relativeDueDate = RelativeBucket.Tomorrow,
                    ),
            )
            section(
                "this_week",
                "This Week",
                Selector.AllOf(
                    listOf(
                        Selector.DateBucket(RelativeBucket.ThisWeek),
                        // The week bucket spans Monday to Sunday, so it also holds
                        // today and the days already gone — the days the Today tab
                        // owns. Excluding them is what makes the two tabs disjoint.
                        Selector.Not(
                            Selector.AnyOf(
                                listOf(
                                    Selector.DateBucket(RelativeBucket.Today),
                                    Selector.DateBucket(RelativeBucket.Overdue),
                                ),
                            ),
                        ),
                    ),
                ),
                order = 1,
                prefill =
                    SectionPrefill(
                        "this_week",
                        "This week",
                        relativeDueDate = RelativeBucket.ThisWeek,
                    ),
            )
            section(
                "next_week",
                "Next Week",
                Selector.DateBucket(RelativeBucket.NextWeek),
                order = 2,
                prefill =
                    SectionPrefill(
                        "next_week",
                        "Next week",
                        relativeDueDate = RelativeBucket.NextWeek,
                    ),
            )
        }

    /**
     * All tasks for a specific project.
     * @param id The [ProjectId] to filter by.
     */
    fun byProject(id: ProjectId): AgendaDefinition =
        agenda(id.value) {
            section("project_tasks", "Project Tasks", Selector.Projects(setOf(id)), order = 0)
        }

    /**
     * All tasks with a specific tag.
     * @param id The [TagId] to filter by.
     */
    fun byTag(id: TagId): AgendaDefinition = byTags(setOf(id))

    /**
     * All tasks tagged with any of the given [TagId]s.
     *
     * @param ids The set of tag IDs to filter by. A task matching any one of them is included.
     */
    fun byTags(ids: Set<TagId>): AgendaDefinition =
        agenda("Tagged") {
            section("tags", "Tags", Selector.Tags(ids), order = 0)
        }

    /**
     * All tasks within a date range (inclusive).
     * Used by the Calendar screen.
     *
     * @param from Start of the range (inclusive).
     * @param to End of the range (inclusive).
     */
    fun byDateRange(
        from: LocalDate,
        to: LocalDate,
    ): AgendaDefinition =
        agenda("Date Range") {
            section("date_range", "Range", Selector.DateRange(from, to), order = 0)
        }

    /**
     * All tasks blocked by an unfinished dependency.
     *
     * Used by the Statistics health drill-down.
     * The selector requires [SelectorContext.blockedIds] at evaluation time.
     */
    fun byBlocked(): AgendaDefinition =
        agenda("Blocked") {
            section("blocked", "Blocked", Selector.Blocked, order = 0)
        }

    /**
     * The tasks the user marked as being worked on now.
     *
     * Used as a built-in view, next to [byBlocked]. Completed tasks are excluded by
     * the selector: the state stays on the task after completion, but the view is a
     * list of work still to do.
     */
    fun byInProgress(): AgendaDefinition =
        agenda("In progress") {
            section(
                "in_progress",
                "In progress",
                Selector.WorkflowState(TaskWorkflowState.InProgress),
                order = 0,
            )
        }

    /**
     * The tasks that follow up on another task.
     *
     * The other projection of the dependency table: an ordering relation, not a
     * reason to wait.
     */
    fun byFollowUp(): AgendaDefinition =
        agenda("Follow-ups") {
            section("follow_up", "Follow-ups", Selector.FollowUp, order = 0)
        }

    /**
     * All stagnant tasks — undated, incomplete, and untouched for at least [threshold].
     *
     * Used by the Statistics health drill-down.
     *
     * @param threshold Duration of inactivity before a task is considered stagnant.
     *                  Defaults to 21 days, matching [Selector.Stagnant.DEFAULT_STAGNATION_THRESHOLD].
     */
    fun byStagnant(threshold: kotlin.time.Duration = Selector.Stagnant.DEFAULT_STAGNATION_THRESHOLD): AgendaDefinition =
        agenda("Stagnant") {
            section("stagnant", "Not touched in a while", Selector.Stagnant(threshold), order = 0)
        }

    /**
     * All overdue tasks — past their due date and still open.
     *
     * Used by the Statistics health drill-down when the user taps the OVERDUE card.
     * Unlike [Today], which shows today's tasks with overdue at the top, this preset
     * shows only the overdue tasks with no date prefill — matching the bucket exactly.
     */
    fun byOverdue(): AgendaDefinition =
        agenda("Overdue") {
            section(
                "overdue",
                "Overdue",
                Selector.DateBucket(RelativeBucket.Overdue),
                order = 0,
            )
        }

    /**
     * The agenda the Today tab shows on [dayOfWeek].
     *
     * [workingDays] is the set of days the user works on, as the "Working Days"
     * section in Settings defines it. On a working day the [workDay] preset
     * applies; on a day off the [offDay] preset does. Both keep the tab's own
     * date buckets — the work-day one adds the user's work blocks inside them
     * rather than replacing them — so the bottom-bar tabs stay a partition of
     * the tasks on every configuration.
     *
     * The set is the whole question: a user whose working week is not
     * "Monday to Friday, plus maybe the weekend" can say so, and the agenda
     * asks the same question the screen asks.
     *
     * This is the "caller's responsibility" that [workDayPreset] documents:
     * deciding whether today is a working day belongs to whoever knows the user's
     * working-day choices — here, the Today route, which reads them from settings.
     */
    fun forToday(
        dayOfWeek: DayOfWeek,
        workingDays: Set<DayOfWeek>,
        workDay: AgendaDefinition,
        offDay: AgendaDefinition = Today,
    ): AgendaDefinition = if (dayOfWeek in workingDays) workDay else offDay

    /**
     * The Today tab's agenda on a working day: the tab's own date buckets with
     * the user's work hours layered inside them.
     *
     * Configuring work hours does not take the tab's buckets away. The tab owns
     * [RelativeBucket.Overdue] and [RelativeBucket.Today] on every day, and the
     * morning / lunch / afternoon blocks are an *extra* section between them —
     * what is late, then today's hours, then whatever today still holds outside
     * those hours. That is what keeps the three bottom-bar tabs a partition of
     * the tasks on a working day too (ADR
     * `2026-11-05-the-work-day-plan-sits-inside-the-today-tab`).
     *
     * Each block is a [Selector.TimeOfDay] narrowed to today. A timed task due
     * on another day belongs to the tab that owns that day, not to today's plan.
     * Every block discards what it matches, so a task due today at 10:00 is
     * listed in the morning block and not again under "Today"; a task due today
     * outside the work hours falls through to "Today". Nothing is dropped.
     *
     * The definition is [Today] with the blocks added, title included, so the
     * header names the tab the user is looking at whether today is a working day
     * or not.
     *
     * Whether today is one of the user's working days is [forToday]'s question,
     * not this function's.
     *
     * @param dayStartMinutes Minutes from midnight for the work-day start (e.g. 540 = 09:00).
     * @param dayEndMinutes Minutes from midnight for the work-day end (e.g. 1020 = 17:00).
     * @param lunchStartMinutes Minutes from midnight for the lunch window start.
     * @param lunchEndMinutes Minutes from midnight for the lunch window end.
     */
    fun workDayPreset(
        dayStartMinutes: Int,
        dayEndMinutes: Int,
        lunchStartMinutes: Int,
        lunchEndMinutes: Int,
    ): AgendaDefinition {
        val workBlocks =
            listOf(
                workBlock("work_morning", "Work", dayStartMinutes, lunchStartMinutes - 1),
                workBlock("lunch", "Lunch", lunchStartMinutes, lunchEndMinutes),
                workBlock("work_afternoon", "Work", lunchEndMinutes + 1, dayEndMinutes),
            )
        // Between "Overdue" and the rest of today: the blocks take today's timed
        // tasks first, and "Today" collects what the work hours left over. A block
        // declared after "Today" would render nothing, because that section does
        // not discard.
        val (overdue, restOfToday) = Today.sections.partition { it.id == "overdue" }
        val sections =
            (overdue + workBlocks + restOfToday)
                .mapIndexed { index, section -> section.copy(order = index) }
        return Today.copy(sections = sections)
    }

    /**
     * One work block of the day plan: today's tasks whose `dueTime` falls inside
     * the inclusive window [fromMinutes]..[toMinutes].
     *
     * The date half of the selector is what keeps the block to today. Without it
     * a 10:00 task due next month would be listed in today's morning.
     */
    private fun workBlock(
        id: String,
        name: String,
        fromMinutes: Int,
        toMinutes: Int,
    ): Section =
        Section(
            id = id,
            name = name,
            selector =
                Selector.AllOf(
                    listOf(
                        Selector.DateBucket(RelativeBucket.Today),
                        Selector.TimeOfDay(fromMinutes, toMinutes),
                    ),
                ),
            discard = true,
        )
}
