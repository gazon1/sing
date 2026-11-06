package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket

/**
 * One place that says what question each name answers.
 *
 * ## Why this exists
 *
 * The app names its sets of tasks in four vocabularies: `TaskFilter` (the code-level
 * filters), [TaskSelection] (the names the AI tool offers a reader), the agenda's
 * presets (`AgendaPresets`) and the agenda's section headers (the names a user reads
 * above a list of tasks). They answer the same questions, they use different words for
 * them, and nothing checked that they stayed in step.
 *
 * That is how `Inbox` came to mean two things at once — the agenda's tab, which holds
 * the tasks with no due date, and a code-level filter, which held the dated tasks too.
 * A caller who reached for `Inbox` got the wider answer; a user who saw an `Inbox` tab
 * got the narrower one. The rename that fixed it was a fix for one word; the thing that
 * let the word happen was that four vocabularies were answering the same questions
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
 * selection's wire name, the agenda preset's title, the section's header as the user
 * reads it. They are compared without regard to case, because `Today` and `today` are
 * the same word to the reader who meets them.
 *
 * ## What a question is here
 *
 * The question is what the name *answers* — the set of tasks it holds, in words. It is
 * not a definition of the type and not a description of the query: the same question
 * asked by two vocabularies is written once, so that the two can be compared.
 *
 * ## Where the question comes from
 *
 * For the names that are about dates, the question is not written here — it is looked
 * up from the date buckets the name holds, and the buckets are the facts the code is
 * held to. A preset's buckets are read out of its own selectors, a section's header
 * names the bucket its selector holds, and the drift test holds the registry to both.
 * So the answer a date-shaped name gives is the answer the code gives, and a preset
 * that starts holding different buckets cannot keep its old answer.
 *
 * The names that are not about dates — a project, a tag, a workflow state — keep a
 * hand-written question, and the drift test holds it to the question of the preset the
 * section belongs to.
 */
object TaskVocabulary {
    /** Which of the four vocabularies a name comes from. */
    enum class Source { FILTER, SELECTION, PRESET, SECTION }

    /**
     * A name, and the question it answers.
     *
     * @param source the vocabulary the name comes from
     * @param name the name as the world meets it — the type name, the wire name, the header
     * @param question what the name answers, in the same words in every vocabulary
     * @param buckets the date buckets the name holds, when it holds any
     */
    data class Entry(
        val source: Source,
        val name: String,
        val question: String,
        val buckets: Set<RelativeBucket> = emptySet(),
    )

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
    private const val DUE_YESTERDAY = "the tasks that were due yesterday"
    private const val DUE_TOMORROW = "the tasks that are due tomorrow"
    private const val DUE_THIS_WEEK = "the tasks that are due this week"
    private const val DUE_NEXT_WEEK = "the tasks that are due next week"
    private const val DUE_THIS_MONTH = "the tasks that are due this month"
    private const val DUE_NEXT_MONTH = "the tasks that are due next month"
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

    /** Every date bucket there is. */
    private val ALL_BUCKETS: Set<RelativeBucket> = RelativeBucket.entries.toSet()

    /**
     * The question each set of buckets answers.
     *
     * The key is the set itself, so the question cannot drift away from the buckets it
     * is written for: a name that holds different buckets looks up a different question,
     * and a set of buckets with no question here is a set nobody has said what it means.
     */
    private val BUCKET_QUESTIONS: Map<Set<RelativeBucket>, String> =
        mapOf(
            setOf(RelativeBucket.Overdue) to OVERDUE,
            setOf(RelativeBucket.Today) to DUE_TODAY,
            setOf(RelativeBucket.Yesterday) to DUE_YESTERDAY,
            setOf(RelativeBucket.Tomorrow) to DUE_TOMORROW,
            setOf(RelativeBucket.ThisWeek) to DUE_THIS_WEEK,
            setOf(RelativeBucket.NextWeek) to DUE_NEXT_WEEK,
            setOf(RelativeBucket.ThisMonth) to DUE_THIS_MONTH,
            setOf(RelativeBucket.NextMonth) to DUE_NEXT_MONTH,
            setOf(RelativeBucket.NoDate) to UNSCHEDULED,
            // The sets the agenda's date-based presets hold.
            setOf(RelativeBucket.Overdue, RelativeBucket.Today) to DUE_TODAY_WITH_OVERDUE,
            setOf(RelativeBucket.Tomorrow, RelativeBucket.ThisWeek, RelativeBucket.NextWeek) to COMING_SOON,
            ALL_BUCKETS to LIVE,
        )

    /**
     * The date buckets the agenda's date-based presets hold.
     *
     * These are the facts the code is checked against: the drift test reads the buckets
     * out of each preset's own selectors and holds them to what is written here. The
     * question the preset answers is looked up from them, so a preset that starts
     * holding different buckets cannot keep its old answer.
     */
    val presetBuckets: Map<String, Set<RelativeBucket>> =
        mapOf(
            "All" to ALL_BUCKETS,
            "Inbox" to setOf(RelativeBucket.NoDate),
            "Today" to setOf(RelativeBucket.Overdue, RelativeBucket.Today),
            "Upcoming" to setOf(RelativeBucket.Tomorrow, RelativeBucket.ThisWeek, RelativeBucket.NextWeek),
        )

    /**
     * The agenda's presets.
     *
     * The date-based ones are written here as the buckets they hold, and the question
     * is looked up from them — so the preset and its answer cannot come apart. The
     * ones that are not about dates keep a hand-written question.
     */
    private val presetEntries: List<Entry> =
        presetBuckets.map { (name, buckets) -> presetEntry(name, buckets) } +
            listOf(
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

    /** The code-level filters. */
    private val filterEntries: List<Entry> =
        listOf(
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
        )

    /** The names the AI tool offers, as they travel on the wire. */
    private val selectionEntries: List<Entry> =
        listOf(
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
        )

    /**
     * The name a user reads for each date bucket.
     *
     * These are the words a person meets, so they are the ones that have to mean one
     * thing. Each one names a date bucket, and the header of a section has to be the
     * name of the bucket its selector holds — a section called "This Week" that held
     * something else would be a name answering two questions.
     */
    val bucketTitles: Map<RelativeBucket, String> =
        mapOf(
            RelativeBucket.Overdue to "Overdue",
            RelativeBucket.Today to "Today",
            RelativeBucket.Yesterday to "Yesterday",
            RelativeBucket.Tomorrow to "Tomorrow",
            RelativeBucket.ThisWeek to "This Week",
            RelativeBucket.NextWeek to "Next Week",
            RelativeBucket.ThisMonth to "This Month",
            RelativeBucket.NextMonth to "Next Month",
            RelativeBucket.NoDate to "No Date",
        )

    /**
     * The names the user reads above a list of tasks.
     *
     * The date sections are named after the bucket they hold, so their question is
     * looked up from that bucket — the header and the set it holds cannot drift apart.
     * The sections that are not about dates take the question of the preset they belong
     * to, and the drift test holds them to it.
     */
    private val sectionEntries: List<Entry> =
        bucketTitles.map { (bucket, title) ->
            Entry(Source.SECTION, title, questionOf(setOf(bucket)), setOf(bucket))
        } +
            listOf(
                Entry(Source.SECTION, "Project Tasks", IN_PROJECT),
                Entry(Source.SECTION, "Tags", WITH_TAGS),
                Entry(Source.SECTION, "Range", IN_DATE_RANGE),
                Entry(Source.SECTION, "Blocked", BLOCKED),
                Entry(Source.SECTION, "In progress", IN_PROGRESS),
                Entry(Source.SECTION, "Follow-ups", FOLLOW_UP),
                Entry(Source.SECTION, "Not touched in a while", STAGNANT),
                Entry(Source.SECTION, "Work", A_WORKING_DAY),
                Entry(Source.SECTION, "Lunch", A_WORKING_DAY),
            )

    /**
     * Every name of every vocabulary, with the question it answers.
     *
     * The four groups are the four vocabularies. A name that appears in two of them
     * has to answer the same question in both — that is the whole rule, and
     * `TaskVocabularyTest` is what holds it.
     *
     * Declared after the four lists it joins, because an object initialises its
     * properties in the order they are written: read first, this one would have
     * seen the lists before they were built.
     */
    val entries: List<Entry> = filterEntries + selectionEntries + presetEntries + sectionEntries

    /** A preset that is about dates: the buckets it holds are the facts. */
    private fun presetEntry(
        name: String,
        buckets: Set<RelativeBucket>,
    ): Entry = Entry(Source.PRESET, name, questionOf(buckets), buckets)

    /** The question a set of buckets answers. */
    private fun questionOf(buckets: Set<RelativeBucket>): String =
        BUCKET_QUESTIONS[buckets]
            ?: error(
                "no question is written for the buckets $buckets: every set of buckets a name " +
                    "holds needs one, or the name has no answer",
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
                    "the agenda's Today tab holds the overdue and today's buckets, because a " +
                        "tab answers \"what is due now or late\"; the filter, the selection and " +
                        "the section header hold today's tasks alone. The tab is a screen, the " +
                        "other three are the sets",
            ),
            Divergence(
                name = "upcoming",
                source = Source.PRESET,
                reason =
                    "the agenda's Upcoming tab holds the tomorrow, this-week and next-week " +
                        "buckets — a horizon — while the filter and the selection hold every " +
                        "later date. The tab is what the bottom bar shows soon; the set is " +
                        "everything that is not due yet",
            ),
        )
}
