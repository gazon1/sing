package com.singularity.todo.feature.search

import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.search.query.FreeTextRule
import com.singularity.todo.feature.search.query.Options
import com.singularity.todo.feature.search.query.Query
import com.singularity.todo.feature.search.query.ResolvedSearchQuery
import com.singularity.todo.feature.search.query.titleMatchesFirst
import com.singularity.todo.feature.search.query.SearchQueryResolver
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.SortOrder
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class SearchResults(
    val tasks: List<Task>,
    val notes: List<Note>,
    val projects: List<Project>,
    val tags: List<Tag>,
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
     */
    suspend operator fun invoke(query: Query, userId: String): Flow<SearchResults> {
        val resolved = queryResolver.resolve(query, userId)
        return buildResultsFlow(resolved)
    }

    /**
     * Backwards-compatible overload that accepts a raw query string.
     * Parses the string using [SingularityQueryParser] and delegates to [invoke].
     */
    suspend operator fun invoke(queryString: String, userId: String): Flow<SearchResults> {
        val parser = SingularityQueryParser(queryString)
        val query = parser.parse()
        return invoke(query, userId)
    }

    private fun buildResultsFlow(resolved: ResolvedSearchQuery): Flow<SearchResults> {
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

        val structuredTaskFlow: Flow<List<Task>> = when {
            resolved.taskFilter != null -> taskRepo.observeByFilter(resolved.taskFilter)
            else -> taskRepo.observeAll()
        }
        val baseTaskFlow: Flow<List<Task>> = when {
            plan !is TextMatchPlan.Narrowed -> structuredTaskFlow

            resolved.taskFilter == null -> textIndex.observeTasks(plan.query)

            else -> combine(structuredTaskFlow, textIndex.observeTasks(plan.query)) { structured, matched ->
                val matchedIds = matched.mapTo(mutableSetOf()) { task -> task.id }
                structured.filter { task -> task.id in matchedIds }
            }
        }

        val taskFlow = baseTaskFlow.map { tasks ->
            val seq = tasks.asSequence()
            val filtered = if (resolved.needsPostFilter) {
                resolved.postFilter(seq)
            } else {
                seq
            }
            var result = sortTasks(filtered.toList(), resolved.sortOrder, resolved.sortDescending)

            // A record that carries the word in its title is a better answer than
            // one that carries it in its body. FTS4 has no bm25 and cannot say
            // that; the rule can, and it is asked about the title alone.
            //
            // This asks the rule about the *title* only — it is a question about
            // one surface of the record, asked to order the records the index
            // already found. It is not a filter: a record the index found is in
            // the answer whatever this says.
            if (!rule.isEmpty) {
                result = titleMatchesFirst(result) { task ->
                    rule.satisfiesPositives { term -> TextMatch.matches(task.title, term) }
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
        val noteFlow = textMatchedNotes(plan, rule)
        val projectFlow = textMatchedProjects(plan, rule)
        val tagFlow = textMatchedTags(plan, rule)

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
    private fun textMatchedNotes(plan: TextMatchPlan, rule: FreeTextRule): Flow<List<Note>> {
        val source = when (plan) {
            is TextMatchPlan.Narrowed -> textIndex.observeNotes(plan.query)
            TextMatchPlan.BroadScan -> noteRepo.observeAll()
        }
        return source.map { notes ->
            if (rule.isEmpty) {
                emptyList()
            } else {
                notes.filter { note -> rule.accepts(plan) { term -> TextMatch.matches(note.title, term) } }
            }
        }
    }

    /** The projects that satisfy the free-text rule. See [textMatchedNotes]. */
    private fun textMatchedProjects(plan: TextMatchPlan, rule: FreeTextRule): Flow<List<Project>> {
        val source = when (plan) {
            is TextMatchPlan.Narrowed -> textIndex.observeProjects(plan.query)
            TextMatchPlan.BroadScan -> projectRepo.observeAll()
        }
        return source.map { projects ->
            if (rule.isEmpty) {
                emptyList()
            } else {
                projects.filter { project ->
                    rule.accepts(plan) { term -> TextMatch.matches(project.name, term) }
                }
            }
        }
    }

    /** The tags that satisfy the free-text rule. See [textMatchedNotes]. */
    private fun textMatchedTags(plan: TextMatchPlan, rule: FreeTextRule): Flow<List<Tag>> {
        val source = when (plan) {
            is TextMatchPlan.Narrowed -> textIndex.observeTags(plan.query)
            TextMatchPlan.BroadScan -> tagRepo.observeAll()
        }
        return source.map { tags ->
            if (rule.isEmpty) {
                emptyList()
            } else {
                tags.filter { tag -> rule.accepts(plan) { term -> TextMatch.matches(tag.name, term) } }
            }
        }
    }

    private fun sortTasks(tasks: List<Task>, sortOrder: SortOrder, descending: Boolean): List<Task> {
        val sorted = when (sortOrder) {
            SortOrder.DUE -> tasks.sortedBy { it.dueDate }
            SortOrder.TITLE -> tasks.sortedBy { it.title.lowercase() }
            SortOrder.CREATED -> tasks.sortedBy { it.createdAt }
            SortOrder.UPDATED -> tasks.sortedBy { it.updatedAt }
            SortOrder.PRIORITY -> tasks.sortedBy { it.priority }
        }
        return if (descending) sorted.reversed() else sorted
    }
}
