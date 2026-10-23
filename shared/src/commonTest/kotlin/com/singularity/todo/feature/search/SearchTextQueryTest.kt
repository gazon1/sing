package com.singularity.todo.feature.search

import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeSearchTextIndex
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.singularity.todo.core.search.SearchMode

/**
 * A free-text query filters the tasks.
 *
 * A word that matches no condition rule is a `HasText` condition. The resolver
 * translated it for notes, projects and tags and produced nothing for tasks, so
 * the task list came back unfiltered: the search screen listed every task for a
 * query that matched nothing, which is what the `search-no-results` Maestro flow
 * forbids and what the nightly screenshot showed.
 *
 * These assertions are about the *result derivation*, which is where the defect
 * was. The screen's own decision between the three states is asserted in
 * [SearchContentTest].
 */
@Tag("fast")
class SearchTextQueryTest {

    private val taskRepo = FakeTaskRepository()
    private val notesRepo = FakeNotesRepository()
    private val projectsRepo = FakeProjectsRepository()
    private val tagsRepo = FakeTagsRepository()

    private val useCase = SearchUseCase(
        taskRepo = taskRepo,
        noteRepo = notesRepo,
        projectRepo = projectsRepo,
        tagRepo = tagsRepo,
        textIndex = FakeSearchTextIndex(
            taskRepo = taskRepo,
            noteRepo = notesRepo,
            projectRepo = projectsRepo,
            tagRepo = tagsRepo,
        ),
        queryResolver = DefaultSearchQueryResolver(
            tagLookup = NO_TAGS,
            projectLookup = NO_PROJECTS,
            clock = FakeClock(NOW),
            timeZone = TEST_TZ,
        ),
    )

    @BeforeTest
    fun seedTasks() {
        taskRepo.seed(
            task(id = "t-milk", title = "Buy milk"),
            task(id = "t-report", title = "Write report", description = "Quarterly numbers"),
            task(id = "t-release", title = "Plan release", priority = TaskPriority.High),
        )
    }

    @Test
    fun `a query that matches a task title returns that task`() = runTest {
        val results = search("milk")

        assertEquals(listOf("Buy milk"), results.tasks.map { it.record.title })
    }

    @Test
    fun `a query that matches a task description returns that task`() = runTest {
        val results = search("quarterly")

        assertEquals(listOf("Write report"), results.tasks.map { it.record.title })
    }

    @Test
    fun `a query that matches nothing returns no tasks`() = runTest {
        val results = search("zzzznomatchzzzz999999")

        assertTrue(results.tasks.isEmpty(), "a query that matches nothing must not return tasks")
        assertTrue(results.isEmpty, "a query that matches nothing must produce an empty result set")
    }

    @Test
    fun `a multi word query matches a task that carries every word`() = runTest {
        // The description holds both words, so the task matches the rule.
        val results = search("quarterly numbers")

        assertEquals(listOf("Write report"), results.tasks.map { it.record.title })
    }

    @Test
    fun `a multi word query does not match a task that carries only one word`() = runTest {
        // "Buy milk" carries the first word and "Write report" the second. Neither
        // carries both, so neither is a match.
        val results = search("milk report")

        assertTrue(results.tasks.isEmpty(), "a task with only one of the two words must not match")
    }

    @Test
    fun `a structured query is not filtered by text`() = runTest {
        // A query with no free text keeps its old meaning: the condition alone
        // decides, and no task is dropped for failing to contain the query string.
        val results = search("priority:high")

        assertEquals(listOf("Plan release"), results.tasks.map { it.record.title })
    }

    @Test
    fun `a query that matches nothing puts the screen on the no-results state`() = runTest {
        val query = "zzzznomatchzzzz999999"

        assertEquals(SearchContent.NoResults, searchContent(query, search(query)))
    }

    @Test
    fun `a query that matches puts the screen on the results state`() = runTest {
        val query = "milk"

        assertEquals(SearchContent.Results, searchContent(query, search(query)))
    }

    private suspend fun search(query: String): SearchResults =
        useCase(SingularityQueryParser(query).parse(), TestUsers.DEFAULT.value, SearchMode.Word).first()

    private fun task(
        id: String,
        title: String,
        description: String? = null,
        priority: TaskPriority = TaskPriority.None,
    ): Task = Task(
        id = TaskId(id),
        title = title,
        description = description,
        priority = priority,
        createdAt = NOW,
        updatedAt = NOW,
        userId = TestUsers.DEFAULT,
    )

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        /** No tag and no project exist in these tests, so every lookup misses. */
        val NO_TAGS = object : TagLookup {
            override suspend fun findByName(userId: String, name: String): TagLookupResult? = null
        }

        val NO_PROJECTS = object : ProjectLookup {
            override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
        }
    }
}
