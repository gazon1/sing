package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.query.Options
import com.singularity.todo.feature.search.query.Query
import com.singularity.todo.feature.search.query.SingularityQueryParser
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.singularity.todo.core.search.SearchMode

@Serializable
data class SearchTasksInput(val query: String, val limit: Int = 20)

@Serializable
data class SearchTasksOutput(val tasks: List<TaskSummary>, val error: String? = null)

/**
 * Searches tasks with the application's own query language.
 *
 * ## Why this is not a title filter
 *
 * The tool used to read every task of the profile and keep the ones whose title
 * contained the word. That answered a question the application does not ask:
 * the search screen finds a task by its description, by its tag and by its
 * project, and it understands a negated term ("not in Plans"). A model that
 * asked this tool for "the task about the airport" got nothing, because the
 * word was in the description.
 *
 * So the tool asks the same question the screen asks — through
 * [SearchUseCase], which parses the query and answers it with the full-text
 * index. One query language, one answer, whether the person asking is the
 * user at the keyboard or the model in the chat.
 *
 * ## What it does not do
 *
 * It does not search notes, projects and tags, even though the search behind it
 * can: this tool is about tasks, and a tool that answers more than its name
 * says is a tool the model cannot reason about.
 */
class SearchTasksTool(private val search: SearchUseCase, private val currentUser: ProfileAwareCurrentUser) :
    SimpleTool<SearchTasksInput>(
        TypeToken.of(SearchTasksInput::class.java),
        NAME,
        DESCRIPTION,
    ) {

    override suspend fun execute(args: SearchTasksInput): String {
        val query = runCatchingCancellable { SingularityQueryParser(args.query).parse() }
            .getOrElse { problem ->
                return Json.encodeToString(
                    SearchTasksOutput.serializer(),
                    SearchTasksOutput(tasks = emptyList(), error = problem.message),
                )
            }

        val userId = currentUser.scopedUserId.value.value
        // The AI tool searches by the beginning of a word, and it says so — a caller
        // that does not name the mode does not get an answer from a rule it did not ask for.
        val results = search(
            query.withLimit(effectiveLimit(query, args.limit)),
            userId,
            SearchMode.Word,
        ).first()

        val tasks = results.tasks.map { found ->
            val task = found.record
            TaskSummary(
                id = task.id.value,
                title = task.title,
                projectId = task.projectId?.value,
                dueDate = task.dueDate?.toString(),
                priority = task.priority.name,
                tags = task.tags.map { tag -> tag.value },
                status = if (task.isCompleted) "completed" else "active",
                estimateMinutes = task.estimateMinutes,
                isPinned = task.isPinned,
            )
        }
        return Json.encodeToString(SearchTasksOutput.serializer(), SearchTasksOutput(tasks = tasks))
    }

    /**
     * The number of results to return.
     *
     * The `limit:` the query itself carries wins over the parameter: the query
     * is the more specific instruction, and silently overriding it would leave
     * the model with a number it cannot explain.
     */
    private fun effectiveLimit(query: Query, requested: Int): Int {
        val fromQuery = query.options.limit
        val limit = if (fromQuery != Options().limit) fromQuery else requested
        return limit.coerceIn(1, MAX_LIMIT)
    }

    /** The query with the limit the caller asked for. */
    private fun Query.withLimit(limit: Int): Query = copy(options = options.copy(limit = limit))

    companion object {
        const val NAME = "search_tasks"

        /**
         * The description the model reads.
         *
         * It names the terms the search understands, because a model that does
         * not know them will only ever send free text — and free text is the
         * part of the query language that finds the least.
         */
        const val DESCRIPTION =
            "Search tasks with the same query language the search screen uses. Free text matches " +
                "titles, descriptions and tag names. Structured terms narrow the result: " +
                "tag:NAME, tags:NAME1,NAME2, project:NAME, priority:NAME, due:WHEN, scheduled:WHEN, " +
                "has:description, pinned, archived. due: and scheduled: take today, tomorrow, " +
                "overdue, soon, or a date. A leading minus negates a term. " +
                "sort:due, sort:priority, sort:title, sort:created and sort:updated order the result. " +
                "Use this tool instead of guessing which task the user means."

        /** The most results one call returns, whatever the caller asks for. */
        const val MAX_LIMIT = 200
    }
}
