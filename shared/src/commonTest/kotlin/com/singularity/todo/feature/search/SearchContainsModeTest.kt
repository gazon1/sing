package com.singularity.todo.feature.search

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.SearchMode
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeSearchTextIndex
import com.singularity.todo.test.fakes.FakeTagsRepository
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

/**
 * The escape-hatch: the same query, asked a different question.
 *
 * The word mode finds a word by its beginning — "arch" finds "architecture",
 * "port" does not find "airport" (F2a). The contains mode finds the term
 * wherever it occurs, and it is stated by the surface, not by the query.
 *
 * The mode changes the rule for **every** term of the query at once, and the
 * rule is one rule for every kind of record: a task, a note, a project and a
 * tag are matched the same way, in both modes. A search that answered
 * differently depending on the kind of thing it looked for would be a search
 * with two answers.
 *
 * See `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`.
 */
@Tag("fast")
class SearchContainsModeTest {

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
    fun seed() {
        taskRepo.seed(
            task(id = "t-airport", title = AIRPORT),
            task(id = "t-milk", title = MILK),
            task(id = "t-trip", title = TRIP, description = AIRPORT),
        )
        notesRepo.seed(note(id = "n-airport", title = AIRPORT))
        projectsRepo.seed(project(id = "p-airport", name = AIRPORT))
        tagsRepo.seed(tag(id = "g-airport", name = AIRPORT))
    }

    // ─── The word mode — what the index answers ────────────────────────────────

    @Test
    fun `the word mode does not find a term in the middle of a word`() = runTest {
        val results = search("port", SearchMode.Word)

        assertTrue(
            results.isEmpty,
            "the word \"airport\" does not begin with \"port\", and the word mode does not look past the beginning",
        )
    }

    @Test
    fun `the word mode finds the word that begins with the term`() = runTest {
        val results = search("air", SearchMode.Word)

        assertEquals(listOf(AIRPORT, TRIP).sorted(), results.tasks.map { it.record.title }.sorted())
        assertEquals(listOf(AIRPORT), results.notes.map { it.record.title })
        assertEquals(listOf(AIRPORT), results.projects.map { it.record.name })
        assertEquals(listOf(AIRPORT), results.tags.map { it.record.name })
    }

    // ─── The contains mode — the escape-hatch ───────────────────────────────────

    @Test
    fun `the contains mode finds the term in the middle of a word`() = runTest {
        val results = search("port", SearchMode.Contains)

        assertEquals(listOf(AIRPORT, TRIP).sorted(), results.tasks.map { it.record.title }.sorted())
        assertEquals(listOf(AIRPORT), results.notes.map { it.record.title })
        assertEquals(listOf(AIRPORT), results.projects.map { it.record.name })
        assertEquals(listOf(AIRPORT), results.tags.map { it.record.name })
    }

    @Test
    fun `the contains mode finds the term in the description of a task`() = runTest {
        val results = search("port", SearchMode.Contains)

        assertTrue(
            results.tasks.any { it.record.title == TRIP },
            "a task is searched by its title and its description (F3), and the mode does not change that",
        )
    }

    @Test
    fun `the contains mode ignores the case`() = runTest {
        val results = search("PORT", SearchMode.Contains)

        assertEquals(listOf(AIRPORT, TRIP).sorted(), results.tasks.map { it.record.title }.sorted())
    }

    @Test
    fun `the contains mode finds nothing when the term is not there`() = runTest {
        val results = search("omega", SearchMode.Contains)

        assertTrue(results.isEmpty, "a query that matches nothing must produce an empty result set")
    }

    // ─── The negated term, in both modes ────────────────────────────────────────
    //
    // The negation is answered by the rule, and the rule is the mode's. The
    // word mode keeps a record whose word merely carries the term; the
    // contains mode rules it out.

    @Test
    fun `the contains mode rules out a record that carries the term`() = runTest {
        val results = search("NOT port", SearchMode.Contains)

        assertEquals(
            listOf(MILK),
            results.tasks.map { it.record.title },
            "a task that carries the term anywhere is ruled out by the negated term",
        )
    }

    @Test
    fun `the word mode keeps a record whose word merely carries the term`() = runTest {
        val results = search("NOT port", SearchMode.Word)

        assertEquals(
            listOf(AIRPORT, MILK, TRIP).sorted(),
            results.tasks.map { it.record.title }.sorted(),
            "the word \"airport\" does not begin with \"port\", so the negated term does not rule the record out",
        )
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun search(query: String, mode: SearchMode): SearchResults =
        useCase(SingularityQueryParser(query).parse(), TestUsers.DEFAULT.value, mode).first()

    private fun task(id: String, title: String, description: String? = null): Task = Task(
        id = TaskId(id),
        title = title,
        description = description,
        createdAt = NOW,
        updatedAt = NOW,
        userId = TestUsers.DEFAULT,
    )

    private fun note(id: String, title: String): Note = Note(
        id = NoteId(id),
        userId = TestUsers.DEFAULT,
        title = title,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun project(id: String, name: String): Project = Project(
        id = ProjectId(id),
        name = name,
        color = 0,
        createdAt = NOW,
        updatedAt = NOW,
        userId = TestUsers.DEFAULT,
    )

    private fun tag(id: String, name: String): com.singularity.todo.feature.tags.Tag =
        com.singularity.todo.feature.tags.Tag(
            id = TagId(id),
            name = name,
            color = 0,
            createdAt = NOW,
            updatedAt = NOW,
            userId = TestUsers.DEFAULT,
        )

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        const val AIRPORT = "Airport transfer"
        const val MILK = "Buy milk"
        const val TRIP = "Trip"

        /** No tag and no project exist under these names, so every lookup misses. */
        val NO_TAGS = object : TagLookup {
            override suspend fun findByName(userId: String, name: String): TagLookupResult? = null
        }

        val NO_PROJECTS = object : ProjectLookup {
            override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
        }
    }
}
