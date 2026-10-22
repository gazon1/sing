package com.singularity.todo.feature.search

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The search, over a real database and the real index.
 *
 * The fakes answer a query with the in-memory rule, and the in-memory rule is
 * the *other* half of the rule — the half the index cannot answer. A test that
 * runs against the fakes therefore says nothing about the half that decides the
 * answer: the index's.
 *
 * What is asserted here is what a user can see:
 *
 * 1. a search returns the record the index found, even where the in-memory rule
 *    would have rejected it — the index answers the positive terms, and the rule
 *    does not ask the question a second time;
 * 2. a negated term still rules a record out, in memory;
 * 3. a quoted phrase does not match the same words standing further apart.
 */
@Tag("slow")
class SearchUseCaseFtsTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    private val user = UserId("alice")
    private val currentUser = FakeProfileAwareCurrentUser(initialUserId = user)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-search-fts-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `the index answers the positive terms, and the search returns what it found`() = runTest {
        // The in-memory rule keeps the accent, so it does not match "cafe"
        // against "Café au lait". The index removes the diacritics, and the
        // index answers the positive terms — so the record is in the answer.
        seed(task(id = "t1", title = "Café au lait"))

        val found = search("cafe")

        assertEquals(
            listOf("t1"),
            found.tasks.map { it.id.value },
            "the index answers the positive terms, and the rule must not filter them again",
        )
    }

    @Test
    fun `a negated term rules the record out`() = runTest {
        seed(task(id = "t1", title = "Alpha report"), task(id = "t2", title = "Alpha and Beta"))

        val found = search("alpha NOT beta")

        assertEquals(
            listOf("t1"),
            found.tasks.map { it.id.value },
            "the negated term is the rule's business, and the rule still holds it",
        )
    }

    @Test
    fun `a quoted phrase does not match the same words standing apart`() = runTest {
        seed(task(id = "t1", title = "Write the quarterly report"), task(id = "t2", title = "quarterly annual report"))

        val found = search("\"quarterly report\"")

        assertEquals(
            listOf("t1"),
            found.tasks.map { it.id.value },
            "a phrase is the words next to each other — the index asks for the phrase, and so does the rule",
        )
    }

    @Test
    fun `a word that merely contains the term is not found`() = runTest {
        seed(task(id = "t1", title = "airport"))

        val found = search("port")

        assertEquals(
            emptyList(),
            found.tasks.map { it.id.value },
            "a term matches the beginning of a word, not its middle",
        )
    }

    // ── the machinery ────────────────────────────────────────────────────────

    private suspend fun search(query: String): SearchResults =
        searchUseCase(SingularityQueryParser(query).parse(), user.value).first()

    private val searchUseCase: SearchUseCase by lazy {
        SearchUseCase(
            taskRepo = TaskRepositoryImpl(
                taskDao = db.taskDao(),
                textIndex = db.textIndex(currentUser),
                clock = Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
                unitOfWork = FakeUnitOfWork(),
            ),
            noteRepo = FakeNotesRepository(),
            projectRepo = FakeProjectsRepository(),
            tagRepo = FakeTagsRepository(),
            textIndex = db.textIndex(currentUser),
            queryResolver = DefaultSearchQueryResolver(
                tagLookup = NO_TAGS,
                projectLookup = NO_PROJECTS,
                clock = FakeClock(NOW),
                timeZone = TEST_TZ,
            ),
        )
    }

    private suspend fun seed(vararg tasks: TaskEntity) {
        tasks.forEach { task -> db.taskDao().upsert(task) }
    }

    private fun task(id: String, title: String) = TaskEntity(
        id = id,
        title = title,
        description = null,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        archivedAt = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        userId = user.value,
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
