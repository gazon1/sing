package com.singularity.todo.feature.tasks.domain.logic

/**
 * One place that says what question each name answers.
 *
 * ## Why this exists
 *
 * The app names its sets of tasks in three vocabularies: `TaskFilter` (the code-level
 * filters), [TaskSelection] (the names the AI tool offers a reader) and the agenda's
 * presets (`AgendaPresets`). They answer the same questions, they use different words
 * for them, and nothing checked that they stayed in step.
 *
 * That is how `Inbox` came to mean two things at once — the agenda's tab, which holds
 * the tasks with no due date, and a code-level filter, which held the dated tasks too.
 * A caller who reached for `Inbox` got the wider answer; a user who saw an `Inbox` tab
 * got the narrower one. The rename that fixed it was a fix for one word; the thing that
 * let the word happen was that three vocabularies were answering the same questions
 * under different names with nothing to say so.
 *
 * So the names live here, each with the question it answers, and a name that appears in
 * two vocabularies has to answer the same question in both of them. The places where
 * that is deliberately not true are in [divergences], each with the reason. The check
 * that holds the line is `TaskVocabularyTest`.
 *
 * ## What a name is here
 *
 * The name of an entry is the name as the world meets it: the filter's type name, the
 * selection's wire name, the agenda preset's title. They are compared without regard
 * to case, because `Today` and `today` are the same word to the reader who meets them.
 *
 * ## What a question is here
 *
 * The question is what the name *answers* — the set of tasks it holds, in words. It is
 * not a definition of the type and not a description of the query: the same question
 * asked by two vocabularies is written once, so that the two can be compared.
 */
object TaskVocabulary {
    /** Which of the three vocabularies a name comes from. */
    enum class Source { FILTER, SELECTION, PRESET }

    /**
     * A name, and the question it answers.
     *
     * @param source the vocabulary the name comes from
     * @param name the name as the world meets it — the type name, the wire name, the title
     * @param question what the name answers, in the same words in every vocabulary
     */
    data class Entry(val source: Source, val name: String, val question: String)

    /**
     * A name one vocabulary uses for a question the others do not.
     *
     * @param name the name the vocabularies share
     * @param source the vocabulary whose question is the different one
     * @param reason why the difference is the point rather than a slip
     */
    data class Divergence(val name: String, val source: Source, val reason: String)

    // The questions, written once so that the vocabularies answering the same
    // question say so in the same words — which is what makes them comparable.
    private const val DUE_TODAY = "the tasks that are due today"
    private const val DUE_LATER = "the tasks that are due after today"
    private const val DEFERRED = "the tasks that are deferred to someday"
    private const val NOT_DEFERRED = "every live task that is not deferred"
    private const val UNFINISHED = "every unfinished task"
    private const val UNFINISHED_NOT_DEFERRED = "every unfinished task that is not deferred"
    private const val FINISHED = "the tasks that are finished"
    private const val PINNED = "the tasks the user pinned"
    private const val TRASHED = "the tasks that are in the trash"
    private const val LIVE = "every task that is not in the trash"
    private const val OVERDUE = "the tasks past their due date and still open"
    private const val BLOCKED = "the tasks waiting on something unfinished"
    private const val STAGNANT = "the unfinished, undated tasks untouched for a long time"
    private const val IN_PROGRESS = "the tasks the user marked as in progress"
    private const val FOLLOW_UP = "the tasks that follow up on another task"
    private const val IN_PROJECT = "the tasks in a project"
    private const val WITH_TAG = "the tasks carrying a tag"
    private const val WITH_TAGS = "the tasks carrying the given tags"
    private const val IN_DATE_RANGE = "the tasks due in a date range"
    private const val MATCHING_TEXT = "the tasks whose text matches"
    private const val MATCHING_PATTERN = "the tasks whose title matches a pattern"
    private const val WITH_PRIORITY = "the tasks at the given priorities"
    private const val WITH_STATUS = "the tasks in the given statuses"
    private const val IN_DATE_BUCKET = "the tasks in a relative date bucket"
    private const val UNSCHEDULED = "the tasks that are not scheduled yet"
    private const val DUE_TODAY_WITH_OVERDUE = "the tasks due today, with the overdue ones on top"
    private const val COMING_SOON = "the tasks coming soon, from tomorrow to next week"
    private const val THE_DAY = "the agenda of one day"
    private const val A_WORKING_DAY = "the agenda of a working day, in work blocks"

    /**
     * Every name of every vocabulary, with the question it answers.
     *
     * The three groups are the three vocabularies. A name that appears in two of them
     * has to answer the same question in both — that is the whole rule, and
     * `TaskVocabularyTest` is what holds it.
     */
    val entries: List<Entry> =
        listOf(
            // The code-level filters.
            Entry(Source.FILTER, "Today", DUE_TODAY),
            Entry(Source.FILTER, "Upcoming", DUE_LATER),
            Entry(Source.FILTER, "Someday", DEFERRED),
            Entry(Source.FILTER, "NotDeferred", NOT_DEFERRED),
            Entry(Source.FILTER, "Pinned", PINNED),
            Entry(Source.FILTER, "Trash", TRASHED),
            Entry(Source.FILTER, "All", LIVE),
            Entry(Source.FILTER, "ByProject", IN_PROJECT),
            Entry(Source.FILTER, "ByTag", WITH_TAG),
            Entry(Source.FILTER, "Search", MATCHING_TEXT),
            Entry(Source.FILTER, "ByDateRange", IN_DATE_RANGE),
            Entry(Source.FILTER, "ByStatuses", WITH_STATUS),
            Entry(Source.FILTER, "ByTags", WITH_TAGS),
            Entry(Source.FILTER, "ByPriorities", WITH_PRIORITY),
            Entry(Source.FILTER, "ByRegexp", MATCHING_PATTERN),
            Entry(Source.FILTER, "ByDateBucket", IN_DATE_BUCKET),
            // The names the AI tool offers, as they travel on the wire.
            Entry(Source.SELECTION, "active_not_deferred", UNFINISHED_NOT_DEFERRED),
            Entry(Source.SELECTION, "today", DUE_TODAY),
            Entry(Source.SELECTION, "upcoming", DUE_LATER),
            Entry(Source.SELECTION, "overdue", OVERDUE),
            Entry(Source.SELECTION, "blocked", BLOCKED),
            Entry(Source.SELECTION, "stagnant", STAGNANT),
            Entry(Source.SELECTION, "pinned", PINNED),
            Entry(Source.SELECTION, "someday", DEFERRED),
            Entry(Source.SELECTION, "active", UNFINISHED),
            Entry(Source.SELECTION, "completed", FINISHED),
            Entry(Source.SELECTION, "all", LIVE),
            // The agenda's presets.
            Entry(Source.PRESET, "All", LIVE),
            Entry(Source.PRESET, "Inbox", UNSCHEDULED),
            Entry(Source.PRESET, "Today", DUE_TODAY_WITH_OVERDUE),
            Entry(Source.PRESET, "Upcoming", COMING_SOON),
            Entry(Source.PRESET, "byProject", IN_PROJECT),
            Entry(Source.PRESET, "byTag", WITH_TAG),
            Entry(Source.PRESET, "byTags", WITH_TAGS),
            Entry(Source.PRESET, "byDateRange", IN_DATE_RANGE),
            Entry(Source.PRESET, "byBlocked", BLOCKED),
            Entry(Source.PRESET, "byInProgress", IN_PROGRESS),
            Entry(Source.PRESET, "byFollowUp", FOLLOW_UP),
            Entry(Source.PRESET, "byStagnant", STAGNANT),
            Entry(Source.PRESET, "byOverdue", OVERDUE),
            Entry(Source.PRESET, "forToday", THE_DAY),
            Entry(Source.PRESET, "workDayPreset", A_WORKING_DAY),
        )

    /**
     * The names two vocabularies answer differently, and why.
     *
     * A divergence is a decision, not an excuse: it says that these two names mean
     * different things and that the difference is wanted. A name that two vocabularies
     * answer differently *without* one of them being declared here is the `Inbox` bug
     * waiting to happen, and the check fails on it.
     */
    val divergences: List<Divergence> =
        listOf(
            Divergence(
                name = "today",
                source = Source.PRESET,
                reason =
                    "the agenda's Today tab shows the overdue tasks above today's, because a " +
                        "tab answers \"what is due now or late\"; the filter and the selection hold " +
                        "today's tasks alone. The tab is a screen, the other two are the sets",
            ),
            Divergence(
                name = "upcoming",
                source = Source.PRESET,
                reason =
                    "the agenda's Upcoming tab is a horizon — tomorrow to next week — while " +
                        "the filter and the selection hold every later date. The tab is what the " +
                        "bottom bar shows soon; the set is everything that is not due yet",
            ),
        )
}
