package com.singularity.todo.feature.search

import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeSearchTextIndex
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TEST_TZ
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import com.singularity.todo.core.search.SearchMode

/**
 * What a search reads.
 *
 * A query of filters only — the query every filter of the sheet produces —
 * matches no note, no project and no tag. The answer is known before the
 * question is asked, and asking it is a read of the whole table: on every
 * search, and again on every change to it, because the read is a live flow.
 *
 * The tasks are not part of this: a query of filters only is filtered by the
 * database, and the rows it returns are the rows the filter chose.
 *
 * See `docs/decisions/2026-10-22-the-broad-scan-share-is-measured.md` — the
 * measurement that found this, and the decision it led to.
 */
@Tag("fast")
class SearchReadsTest {

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

    @Test
    fun `a query of filters only does not read the notes`() = runTest {
        search("priority:high")

        assertEquals(
            0,
            notesRepo.observeAllCalls,
            "a query with no free text matches no note, and the answer is known before the question is asked",
        )
    }

    @Test
    fun `a query of filters only does not read the projects`() = runTest {
        search("priority:high")

        assertEquals(
            0,
            projectsRepo.observeAllCalls,
            "a query with no free text matches no project, and the answer is known before the question is asked",
        )
    }

    @Test
    fun `a query of filters only does not read the tags`() = runTest {
        search("priority:high")

        assertEquals(
            0,
            tagsRepo.observeAllCalls,
            "a query with no free text matches no tag, and the answer is known before the question is asked",
        )
    }

    @Test
    fun `a query with free text asks for the notes`() = runTest {
        search("milk")

        assertEquals(
            1,
            notesRepo.observeAllCalls,
            "the index reads the notes once, and the fake index reads them through the repository",
        )
    }

    private suspend fun search(query: String): SearchResults {
        val parsed = SingularityQueryParser(query).parse()
        return useCase(parsed, "user_1", SearchMode.Word).first()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")

        val NO_TAGS = object : TagLookup {
            override suspend fun findByName(userId: String, name: String): TagLookupResult? = null
        }

        val NO_PROJECTS = object : ProjectLookup {
            override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
        }
    }
}
