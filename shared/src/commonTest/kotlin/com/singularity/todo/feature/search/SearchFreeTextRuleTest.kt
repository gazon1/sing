package com.singularity.todo.feature.search

import com.singularity.todo.core.search.FreeTextRule
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
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

/**
 * One free-text rule for every kind of record.
 *
 * A query used to mean two things at once. Tasks were matched per term — every
 * `HasText` leaf was a predicate of its own — while notes, projects and tags were
 * matched against the *joined* free text, the whole query as one phrase. So the
 * same two words found a task and missed a note carrying the same two words, and
 * the answer depended on which kind of thing the user was looking for.
 *
 * The rule is now one rule: **every free-text term has to match**. The resolver
 * owns it ([FreeTextRule]), and the use case applies it to tasks, notes, projects
 * and tags alike.
 *
 * These assertions are about the *result derivation* — what the use case returns
 * for a query — because that is the level `commonTest` can run. The screen's own
 * decision between the three states is asserted in [SearchContentTest].
 */
@Tag("fast")
class SearchFreeTextRuleTest {

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
            task(id = "t-first", title = FIRST_ONLY),
            task(id = "t-second", title = SECOND_ONLY),
            task(id = "t-both", title = BOTH),
            task(id = "t-neither", title = NEITHER),
        )
        notesRepo.seed(note(id = "n-first", title = FIRST_ONLY))
        notesRepo.seed(note(id = "n-second", title = SECOND_ONLY))
        notesRepo.seed(note(id = "n-both", title = BOTH))
        notesRepo.seed(note(id = "n-neither", title = NEITHER))
        projectsRepo.seed(
            project(id = "p-first", name = FIRST_ONLY),
            project(id = "p-second", name = SECOND_ONLY),
            project(id = "p-both", name = BOTH),
            project(id = "p-neither", name = NEITHER),
        )
        tagsRepo.seed(
            tag(id = "g-first", name = FIRST_ONLY),
            tag(id = "g-second", name = SECOND_ONLY),
            tag(id = "g-both", name = BOTH),
            tag(id = "g-neither", name = NEITHER),
        )
    }

    // ─── The rule, per kind ─────────────────────────────────────────────────────

    @Test
    fun `a task matches only when every term matches`() = runTest {
        val results = search("alpha beta")

        assertEquals(listOf(BOTH), results.tasks.map { it.record.title })
    }

    @Test
    fun `a note matches only when every term matches`() = runTest {
        val results = search("alpha beta")

        assertEquals(listOf(BOTH), results.notes.map { it.record.title })
    }

    @Test
    fun `a project matches only when every term matches`() = runTest {
        val results = search("alpha beta")

        assertEquals(listOf(BOTH), results.projects.map { it.record.name })
    }

    @Test
    fun `a tag matches only when every term matches`() = runTest {
        val results = search("alpha beta")

        assertEquals(listOf(BOTH), results.tags.map { it.record.name })
    }

    // ─── The second word only ───────────────────────────────────────────────────
    //
    // The case the rule exists for: a record that carries the *second* word of the
    // query and not the first is not a match. Before the rule was unified, the
    // task side already behaved this way and the other three kinds did not.

    @Test
    fun `a task that carries only the second word is not a match`() = runTest {
        val results = search("alpha beta")

        assertTrue(
            results.tasks.none { it.record.title == SECOND_ONLY },
            "a task with the second word only must not match a two-word query",
        )
    }

    @Test
    fun `a note that carries only the second word is not a match`() = runTest {
        val results = search("alpha beta")

        assertTrue(
            results.notes.none { it.record.title == SECOND_ONLY },
            "a note with the second word only must not match a two-word query",
        )
    }

    @Test
    fun `a project that carries only the second word is not a match`() = runTest {
        val results = search("alpha beta")

        assertTrue(
            results.projects.none { it.record.name == SECOND_ONLY },
            "a project with the second word only must not match a two-word query",
        )
    }

    @Test
    fun `a tag that carries only the second word is not a match`() = runTest {
        val results = search("alpha beta")

        assertTrue(
            results.tags.none { it.record.name == SECOND_ONLY },
            "a tag with the second word only must not match a two-word query",
        )
    }

    // ─── The first word only, and the single word query ─────────────────────────

    @Test
    fun `a task that carries only the first word is not a match`() = runTest {
        val results = search("alpha beta")

        assertTrue(
            results.tasks.none { it.record.title == FIRST_ONLY },
            "a task with the first word only must not match a two-word query",
        )
    }

    @Test
    fun `a single term matches every kind the same way`() = runTest {
        val results = search("alpha")

        assertEquals(listOf(BOTH, FIRST_ONLY).sorted(), results.tasks.map { it.record.title }.sorted())
        assertEquals(listOf(BOTH, FIRST_ONLY).sorted(), results.notes.map { it.record.title }.sorted())
        assertEquals(listOf(BOTH, FIRST_ONLY).sorted(), results.projects.map { it.record.name }.sorted())
        assertEquals(listOf(BOTH, FIRST_ONLY).sorted(), results.tags.map { it.record.name }.sorted())
    }

    @Test
    fun `the second term on its own matches every kind the same way`() = runTest {
        val results = search("beta")

        assertEquals(listOf(BOTH, SECOND_ONLY).sorted(), results.tasks.map { it.record.title }.sorted())
        assertEquals(listOf(BOTH, SECOND_ONLY).sorted(), results.notes.map { it.record.title }.sorted())
        assertEquals(listOf(BOTH, SECOND_ONLY).sorted(), results.projects.map { it.record.name }.sorted())
        assertEquals(listOf(BOTH, SECOND_ONLY).sorted(), results.tags.map { it.record.name }.sorted())
    }

    @Test
    fun `a term that matches nothing selects nothing for any kind`() = runTest {
        // "omega" is in no record of any kind — not even the one that carries
        // neither of the two words.
        val results = search("omega")

        assertTrue(results.isEmpty, "a query that matches nothing must produce an empty result set")
    }

    // ─── The negated term ────────────────────────────────────────────────────
    //
    // The index answers the positive terms and the rule answers the negated
    // ones. A query that says both asks for the records that carry the first
    // and do not carry the second — for every kind of record alike.

    @Test
    fun `a negated term rules the record out for every kind`() = runTest {
        val results = search("alpha NOT beta")

        assertEquals(listOf(FIRST_ONLY), results.tasks.map { it.record.title })
        assertEquals(listOf(FIRST_ONLY), results.notes.map { it.record.title })
        assertEquals(listOf(FIRST_ONLY), results.projects.map { it.record.name })
        assertEquals(listOf(FIRST_ONLY), results.tags.map { it.record.name })
    }

    // ─── A query with no free text ──────────────────────────────────────────────
    //
    // Structured terms are filters over tasks. They do not select notes, projects or
    // tags, and a query with no free text has no free-text part — so it selects
    // none of them. This is the behaviour the search screen had before the rule was
    // unified, and it is kept deliberately.

    @Test
    fun `a query with no free text selects no note, project or tag`() = runTest {
        val results = search("priority:high")

        assertTrue(results.notes.isEmpty(), "a structured query selects no notes")
        assertTrue(results.projects.isEmpty(), "a structured query selects no projects")
        assertTrue(results.tags.isEmpty(), "a structured query selects no tags")
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun search(query: String): SearchResults =
        useCase(SingularityQueryParser(query).parse(), TestUsers.DEFAULT.value).first()

    private fun task(id: String, title: String): Task = Task(
        id = TaskId(id),
        title = title,
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

        const val FIRST_ONLY = "Alpha only"
        const val SECOND_ONLY = "Beta only"
        const val BOTH = "Alpha and Beta"
        const val NEITHER = "Gamma"

        /** No tag and no project exist in these tests, so every lookup misses. */
        val NO_TAGS = object : TagLookup {
            override suspend fun findByName(userId: String, name: String): TagLookupResult? = null
        }

        val NO_PROJECTS = object : ProjectLookup {
            override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
        }
    }
}
