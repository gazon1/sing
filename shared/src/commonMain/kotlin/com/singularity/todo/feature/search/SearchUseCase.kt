package com.singularity.todo.feature.search

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
)

class SearchUseCase(
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository,
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
     * Note, project, and tag results are matched against [ResolvedSearchQuery.freeText] when available,
     * or the original query string otherwise.
     *
     * Sort order and pagination ([Options.limit]/[Options.offset]) apply to the task results only.
     */
    suspend operator fun invoke(query: Query, userId: String): Flow<SearchResults> {
        val resolved = queryResolver.resolve(query, userId)
        return buildResultsFlow(resolved, query)
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

    private fun buildResultsFlow(resolved: ResolvedSearchQuery, query: Query): Flow<SearchResults> {
        // ── Task flow ──────────────────────────────────────────────────────────
        val baseTaskFlow: Flow<List<Task>> = when {
            resolved.taskFilter != null -> taskRepo.observeByFilter(resolved.taskFilter)
            else -> taskRepo.observeAll()
        }

        val taskFlow = baseTaskFlow.map { tasks ->
            val seq = tasks.asSequence()
            val filtered = if (resolved.needsPostFilter) {
                resolved.postFilter(seq)
            } else {
                seq
            }
            var result = sortTasks(filtered.toList(), resolved.sortOrder, resolved.sortDescending)
            val offset = resolved.options.offset
            val limit = resolved.options.limit
            if (offset > 0) result = result.drop(offset)
            if (limit > 0) result = result.take(limit)
            result
        }

        // ── Notes, projects, tags ───────────────────────────────────────────────
        val freeText = resolved.freeText ?: query.toString()

        val noteFlow = noteRepo.search(freeText)

        val projectFlow = projectRepo.observeAll().map { projects ->
            if (freeText.isNotBlank()) {
                projects.filter { it.name.contains(freeText, ignoreCase = true) }
            } else {
                projects
            }
        }

        val tagFlow = tagRepo.observeAll().map { tags ->
            if (freeText.isNotBlank()) {
                tags.filter { it.name.contains(freeText, ignoreCase = true) }
            } else {
                tags
            }
        }

        return combine(taskFlow, noteFlow, projectFlow, tagFlow) { tasks, notes, projects, tags ->
            SearchResults(
                tasks = tasks,
                notes = notes,
                projects = projects,
                tags = tags,
            )
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
