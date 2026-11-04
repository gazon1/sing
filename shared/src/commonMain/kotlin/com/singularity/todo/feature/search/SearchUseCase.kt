package com.singularity.todo.feature.search

import com.singularity.todo.core.search.FreeTextRule
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.SearchLimits
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.core.search.TextMatchRange
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.search.query.Options
import com.singularity.todo.feature.search.query.Query
import com.singularity.todo.feature.search.query.ResolvedSearchQuery
import com.singularity.todo.feature.search.query.SearchQueryResolver
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.SortOrder
import com.singularity.todo.feature.search.query.titleMatchesFirst
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The answer of a search: the records it found, and where the word was found.
 *
 * Every record comes with the ranges of the match, because the index is the
 * only one that knows where the word is: it holds the tokenizer and it knows
 * which column of the record matched. A record that was not read through the
 * index — one a broad scan read — comes with no ranges, and the screen draws
 * it as it draws any other record.
 */
data class SearchResults(
    val tasks: List<IndexedRecord<Task>>,
    val notes: List<IndexedRecord<Note>>,
    val projects: List<IndexedRecord<Project>>,
    val tags: List<IndexedRecord<Tag>>,
) {
    /**
     * True when the search matched nothing at all.
     *
     * This is the difference between "the screen has nothing to show" and "the
     * screen has results": an empty result set is the no-results state, not a
     * blank screen.
     */
    val isEmpty: Boolean
        get() = tasks.isEmpty() && notes.isEmpty() && projects.isEmpty() && tags.isEmpty()
}

class SearchUseCase(
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository,
    private val textIndex: SearchTextIndex,
    private val queryResolver: SearchQueryResolver,
) {
    /**
     * Executes a structured [Query] for the given [userId] and returns matching tasks, notes, projects, and tags.
     *
     * Task results are filtered through [SearchQueryResolver.resolve] which:
     * - Converts tag/project names to IDs via DAO lookups
     * - Builds [TaskFilter] for DAO dispatch
     * - Accumulates post-filter predicates for conditions the DAO can't express
     *   (negated, OR, pinned, archived, has:description, complex date relations)
     *
     * The free text narrows the rows *in the database*, through the full-text
     * index — the search reads the records that carry the word instead of every
     * record of the profile. The index answers the positive terms; the rule
     * answers the negated ones, in memory. A rule the index cannot answer (one
     * that only says "not") is answered by reading the rows, the way it was
     * before.
     *
     * Note, project, and tag results are matched by the same free-text rule the tasks
     * are matched by — [ResolvedSearchQuery.freeTextRule] — so one query means one
     * thing for every kind of record. Structured terms are filters over tasks; they
     * do not select notes, projects or tags, and a query with no free text selects
     * none of them.
     *
     * Sort order and pagination ([Options.limit]/[Options.offset]) apply to the task results only.
     *
     * @param mode The mode the free text is matched in. The word mode is asked of
     *   the index; the contains mode reads the rows, because the index stores
     *   words and cannot say whether a term occurs in the middle of one.
     */
    suspend operator fun invoke(
        query: Query,
        userId: String,
        mode: SearchMode,
    ): Flow<SearchResults> {
        val resolved = queryResolver.resolve(query, userId, mode)
        return buildResultsFlow(resolved, mode)
    }

    /**
     * Backwards-compatible overload that accepts a raw query string.
     * Parses the string using [SingularityQueryParser] and delegates to [invoke].
     */
    suspend operator fun invoke(
        queryString: String,
        userId: String,
        mode: SearchMode,
    ): Flow<SearchResults> {
        val parser = SingularityQueryParser(queryString)
        val query = parser.parse()
        return invoke(query, userId, mode)
    }

    private fun buildResultsFlow(
        resolved: ResolvedSearchQuery,
        mode: SearchMode,
    ): Flow<SearchResults> {
        val plan = resolved.textMatchPlan

        // ── Task flow ──────────────────────────────────────────────────────────
        //
        // The free text narrows the rows *in the database*, through the
        // full-text index: the search reads the tasks that carry the term
        // instead of reading every task and looking at each one. The structured
        // conditions narrow them further.
        //
        // A rule the index cannot answer reads the rows instead, and the plan
        // says so: [TextMatchPlan.BroadScan] is the old path, named.
        // The rule the free text was parsed into. It answers the negated terms
        // of a rule the index narrowed, and the whole rule of a rule the index
        // could not answer at all.
        val rule = resolved.freeTextRule

        val structuredTaskFlow: Flow<List<Task>> =
            when {
                resolved.taskFilter != null -> taskRepo.observeByFilter(resolved.taskFilter)
                else -> taskRepo.observeAll()
            }
        val baseTaskFlow: Flow<List<IndexedRecord<Task>>> =
            when {
                plan !is TextMatchPlan.Narrowed -> {
                    structuredTaskFlow.map { tasks ->
                        tasks.map { task -> IndexedRecord(task, taskRanges(task, rule, mode)) }
                    }
                }

                resolved.taskFilter == null -> {
                    textIndex.observeTasks(plan.query)
                }

                else -> {
                    combine(structuredTaskFlow, textIndex.observeTasks(plan.query)) { structured, matched ->
                        val matchedById = matched.associateBy { record -> record.record.id }
                        structured.mapNotNull { task ->
                            matchedById[task.id]?.let { found -> IndexedRecord(task, found.ranges.toImmutableList()) }
                        }
                    }
                }
            }

        val taskFlow =
            baseTaskFlow.map { records ->
                val tasks = records.map { it.record }
                val filtered =
                    if (resolved.needsPostFilter) {
                        resolved.postFilter(tasks.asSequence()).toList()
                    } else {
                        tasks
                    }
                val rangesById = records.associate { record -> record.record.id to record.ranges }
                var result =
                    filtered.map { task ->
                        IndexedRecord(task, rangesById[task.id].orEmpty().toImmutableList())
                    }

                result = sortTasks(result, resolved.sortOrder, resolved.sortDescending)

                // A record that carries the word in its title is a better answer than
                // one that carries it in its body. FTS4 has no bm25 and cannot say
                // that; the rule can, and it is asked about the title alone.
                //
                // This asks the rule about the *title* only — it is a question about
                // one surface of the record, asked to order the records the index
                // already found. It is not a filter: a record the index found is in
                // the answer whatever this says. And it is not the record's own
                // surfaces either: those answer "does this record match", and every
                // record here matches — the ranking asks the other question.
                //
                // The partition happens after the sort, so the order the user asked
                // for is the order inside each group. See `titleMatchesFirst`.
                if (!rule.isEmpty) {
                    result =
                        titleMatchesFirst(result) { record ->
                            rule.satisfiesPositives { term -> mode.matches(record.record.title, term) }
                        }
                }

                val offset = resolved.options.offset
                val limit = resolved.options.limit
                if (offset > 0) result = result.drop(offset)
                if (limit > 0) result = result.take(limit)
                result
            }

        // ── Notes, projects, tags ───────────────────────────────────────────────
        //
        // The same rule the tasks were matched by.
        //
        // F3 — the searchable text of a record is the kind's own. A note is searched
        // by its title (the notes repository searches titles only,
        // `watchSearchByTitle`), a project or a tag by its name — and the rule decides
        // which records the terms select.
        // F5 — a query with no free text selects no note, project or tag. The
        // structured terms of a query are filters over tasks, and there is no
        // free-text part to match anything else with.
        val noteFlow = textMatchedNotes(plan, rule, mode)
        val projectFlow = textMatchedProjects(plan, rule, mode)
        val tagFlow = textMatchedTags(plan, rule, mode)

        return combine(taskFlow, noteFlow, projectFlow, tagFlow) { tasks, notes, projects, tags ->
            SearchResults(
                tasks = tasks,
                notes = notes,
                projects = projects,
                tags = tags,
            )
        }
    }

    /**
     * The notes that satisfy the free-text rule.
     *
     * The candidates come from the full-text index when the query has a
     * narrowing the index can answer, and from every note otherwise; the rule
     * then decides, in both cases — but only about the half the index did not
     * answer. See [FreeTextRule.accepts].
     */
    private fun textMatchedNotes(
        plan: TextMatchPlan,
        rule: FreeTextRule,
        mode: SearchMode,
    ): Flow<List<IndexedRecord<Note>>> {
        // A rule with no terms selects no note, and the answer is known before
        // the question is asked. Reading the table to learn that is a read of
        // every note of the profile — and a live one: it runs again on every
        // change to the notes.
        if (rule.isEmpty) return flowOf(emptyList())

        return when (plan) {
            is TextMatchPlan.Narrowed -> {
                textIndex.observeNotes(plan.query).map { notes ->
                    notes.filter { note -> rule.accepts(plan) { term -> mode.matches(note.record, term) } }
                }
            }

            TextMatchPlan.BroadScan -> {
                noteRepo.observeAll().map { notes ->
                    // The index answers the word mode: the newest first, and
                    // twenty of them. The contains mode reads the rows, and it
                    // answers the same way — the same order, the same ceiling —
                    // so that the mode says how the rows are matched and not what
                    // the answer looks like. The order of `observeAll` is the
                    // list's own order (pinned first, then the manual order),
                    // and that order belongs to the notes list, not to a search.
                    notes
                        .filter { note -> rule.accepts(plan) { term -> mode.matches(note, term) } }
                        .sortedByDescending { note -> note.updatedAt }
                        .take(SearchLimits.NOTES)
                        .map { note ->
                            // Every surface the note is searched by, not its title
                            // alone: the rule matched the record, and a body match
                            // that reported no range would draw the note exactly as
                            // an untouched one.
                            IndexedRecord(
                                note,
                                matchRanges(note.searchableSurfaces, SearchColumn.TITLE, rule, mode),
                            )
                        }
                }
            }
        }
    }

    /** The projects that satisfy the free-text rule. See [textMatchedNotes]. */
    private fun textMatchedProjects(
        plan: TextMatchPlan,
        rule: FreeTextRule,
        mode: SearchMode,
    ): Flow<List<IndexedRecord<Project>>> {
        if (rule.isEmpty) return flowOf(emptyList())

        val source: Flow<List<IndexedRecord<Project>>> =
            when (plan) {
                is TextMatchPlan.Narrowed -> {
                    textIndex.observeProjects(plan.query)
                }

                TextMatchPlan.BroadScan -> {
                    projectRepo.observeAll().map { projects ->
                        projects.map { project ->
                            IndexedRecord(
                                project,
                                matchRanges(project.searchableSurfaces, SearchColumn.NAME, rule, mode),
                            )
                        }
                    }
                }
            }
        return source.map { projects ->
            projects.filter { project ->
                rule.accepts(plan) { term -> mode.matches(project.record, term) }
            }
        }
    }

    /** The tags that satisfy the free-text rule. See [textMatchedNotes]. */
    private fun textMatchedTags(
        plan: TextMatchPlan,
        rule: FreeTextRule,
        mode: SearchMode,
    ): Flow<List<IndexedRecord<Tag>>> {
        if (rule.isEmpty) return flowOf(emptyList())

        val source: Flow<List<IndexedRecord<Tag>>> =
            when (plan) {
                is TextMatchPlan.Narrowed -> {
                    textIndex.observeTags(plan.query)
                }

                TextMatchPlan.BroadScan -> {
                    tagRepo.observeAll().map { tags ->
                        tags.map { tag ->
                            IndexedRecord(
                                tag,
                                matchRanges(tag.searchableSurfaces, SearchColumn.NAME, rule, mode),
                            )
                        }
                    }
                }
            }
        return source.map { tags ->
            tags.filter { tag -> rule.accepts(plan) { term -> mode.matches(tag.record, term) } }
        }
    }

    /**
     * Where the matched terms are in the surfaces of a record the rule matched.
     *
     * The index reports the position of a match — but only in the word mode,
     * and only for the words it stores. The contains mode has no index to ask:
     * the rows are read and matched in memory, so the position has to be found
     * in the text, with the same folding the rule folded with.
     *
     * ## Why the word mode reports nothing here
     *
     * In the word mode the positions are the index's, and only the index's
     * (see [com.singularity.todo.core.search.IndexedRecord]): a record read
     * through the index arrives with them, and a record a broad scan read
     * arrives *without* them — the scan read the rows and never tokenized them,
     * so it has no beginning of a word to report. Measuring them again in
     * Kotlin would be a second answer to a question the index already answered,
     * and a term of several words can land in a different place the second
     * time. So this reports positions for the mode that has no index, and the
     * word mode keeps the index's answer for the records the index read.
     *
     * ## Why every surface, not the first one
     *
     * A note whose *body* carries the term matches the rule — [SearchMode.matches]
     * asks the record, and the record says which surfaces it is searched by
     * (F3). Measuring the title alone answered a different question: the record
     * matched, the ranges held nothing, and the card drew the note as it draws a
     * note the search never touched. So every surface is measured and every
     * range says which surface it was measured in, and the card — which knows
     * which surface it is drawing — picks the ones that belong to it.
     *
     * @param surfaces the record's searchable surfaces, in its own order; the
     *   first is the one the card draws as the title.
     * @param primaryColumn the column the first surface is — [SearchColumn.TITLE]
     *   for a note, [SearchColumn.NAME] for a project or a tag.
     * @param rule the rule the record was matched by.
     * @param mode the mode the free text is matched in.
     */
    private fun matchRanges(
        surfaces: List<String?>,
        primaryColumn: SearchColumn,
        rule: FreeTextRule,
        mode: SearchMode,
    ): ImmutableList<TextMatchRange> {
        if (mode != SearchMode.Contains) return persistentListOf()
        val terms = rule.positives.map { it.text }
        if (terms.isEmpty()) return persistentListOf()
        val ranges =
            surfaces.flatMapIndexed { index, surface ->
                if (surface.isNullOrEmpty()) return@flatMapIndexed emptyList()
                // The record's own order is the only order there is: its first
                // surface is the one the card draws as the title, and everything
                // after it is a body. A project and a tag have one surface, so
                // this is their name and nothing else.
                val column = if (index == 0) primaryColumn else SearchColumn.DESCRIPTION
                TextMatch.containsSpans(surface, terms).mapNotNull { span ->
                    span?.let { TextMatchRange(column, it.first, it.last + 1) }
                }
            }
        return ranges.toImmutableList()
    }

    /**
     * Where the matched term is in the title of a task.
     *
     * A task is searched by its title and its description (F3), but the card
     * draws the title — so the title is the surface whose positions are worth
     * reporting. A match that is only in the description leaves the card as it
     * is, exactly as it does in the word mode.
     */
    private fun taskRanges(
        task: Task,
        rule: FreeTextRule,
        mode: SearchMode,
    ): ImmutableList<TextMatchRange> =
        matchRanges(listOf(task.title), SearchColumn.TITLE, rule, mode)

    private fun sortTasks(
        tasks: List<IndexedRecord<Task>>,
        sortOrder: SortOrder,
        descending: Boolean,
    ): List<IndexedRecord<Task>> {
        val sorted =
            when (sortOrder) {
                SortOrder.DUE -> tasks.sortedBy { it.record.dueDate }
                SortOrder.TITLE -> tasks.sortedBy { it.record.title.lowercase() }
                SortOrder.CREATED -> tasks.sortedBy { it.record.createdAt }
                SortOrder.UPDATED -> tasks.sortedBy { it.record.updatedAt }
                SortOrder.PRIORITY -> tasks.sortedBy { it.record.priority }
            }
        return if (descending) sorted.reversed() else sorted
    }
}
