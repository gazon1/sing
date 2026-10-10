package com.singularity.todo.feature.search

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.domain.port.SavedSearchRepository
import com.singularity.todo.feature.search.query.Query
import com.singularity.todo.feature.search.query.ResolvedSearchQuery
import com.singularity.todo.feature.search.query.SearchQueryResolver
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.search.query.SimpleFilterMapper
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.jupiter.api.Tag

/**
 * Tests for [SearchViewModel]'s state machine and intent processing.
 *
 * What is tested here (the VM's own logic):
 * - Query string → `parsedQuery` / `activeFilter` mapping
 * - Saved search observation and CRUD intents
 * - Filter application and the SimpleFilter↔Query round-trip
 *
 * What is tested elsewhere:
 * - [SearchUseCase] and [SearchQueryResolver] are tested in their own test files
 * - [SavedSearchRepository] implementations are tested in repository integration tests
 */
@Tag("fast")
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val fakeTaskRepo = object : FakeTaskRepository() {
        val pinnedToggled = mutableListOf<String>()
        override suspend fun togglePinned(id: TaskId): Result<Unit> {
            pinnedToggled.add(id.value)
            return Result.success(Unit)
        }
    }
    private val fakeNotesRepo = FakeNotesRepository()
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val fakeTagsRepo = FakeTagsRepository()
    private val fakeSavedSearchRepo = FakeSavedSearchRepository()

    @AfterTest
    fun cleanup() {
        fakeTaskRepo.clear()
        fakeNotesRepo.clear()
        fakeProjectsRepo.clear()
        fakeTagsRepo.clear()
        fakeSavedSearchRepo.clear()
    }

    private fun TestScope.createVm(): SearchViewModel {
        return SearchViewModel(
            searchUseCase = SearchUseCase(
                taskRepo = fakeTaskRepo,
                noteRepo = fakeNotesRepo,
                projectRepo = fakeProjectsRepo,
                tagRepo = fakeTagsRepo,
                queryResolver = FakeSearchQueryResolver(),
            ),
            savedSearchRepo = fakeSavedSearchRepo,
            taskRepo = fakeTaskRepo,
            parseQuery = { input ->
                try {
                    com.singularity.todo.feature.search.query.SingularityQueryParser(input).parse()
                } catch (_: com.singularity.todo.feature.search.query.QueryParseException) {
                    Query.EMPTY
                }
            },
            clock = FakeClock(NOW),
            crashReporter = NoOpCrashReportingPort(),
            // backgroundScope owns the VM's lifecycle — see TagGroupsViewModelTest for rationale
            scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
        )
    }

    // ─── Saved search observation ─────────────────────────────────────────────

    @Test
    fun `empty saved search repository settles with no saved searches`() = runTest {
        val vm = createVm()
        runCurrent()

        assertTrue(vm.stateFlow.value.savedSearches.isEmpty())
    }

    @Test
    fun `populated saved search repository surfaces all saved searches`() = runTest {
        fakeSavedSearchRepo.seed(
            savedSearch(id = "s1", name = "My Work Tasks"),
            savedSearch(id = "s2", name = "Overdue"),
        )
        val vm = createVm()
        runCurrent()

        val state = vm.stateFlow.value
        assertEquals(listOf("s1", "s2"), state.savedSearches.map { it.id.raw })
    }

    // ─── Query change ──────────────────────────────────────────────────────────

    @Test
    fun `blank query clears parsedQuery and activeFilter`() = runTest {
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnQueryChange("something"))
        runCurrent()

        vm.onIntent(SearchIntent.OnQueryChange(""))
        runCurrent()

        val state = vm.stateFlow.value
        assertEquals("", state.query)
        assertNull(state.parsedQuery)
        assertNull(state.activeFilter)
    }

    @Test
    fun `non-blank query updates query string and signals searching`() = runTest {
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnQueryChange("tag:work"))
        runCurrent()

        val state = vm.stateFlow.value
        assertEquals("tag:work", state.query)
        assertTrue(state.isSearching)
    }

    // ─── Save current search ───────────────────────────────────────────────────

    @Test
    fun `OnSaveCurrentSearch calls upsert with current query string`() = runTest {
        fakeSavedSearchRepo.seed(savedSearch(id = "s1", name = "Old Name"))
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnQueryChange("priority:high"))
        runCurrent()
        vm.onIntent(SearchIntent.OnSaveCurrentSearch("High Priority"))
        runCurrent()

        val upserts = fakeSavedSearchRepo.upserted
        assertEquals(1, upserts.size, "expected exactly one upsert")
        val saved = upserts.last()
        assertEquals("High Priority", saved.name)
        assertEquals("priority:high", saved.queryString)
    }

    // ─── Load saved search ─────────────────────────────────────────────────────

    @Test
    fun `OnLoadSavedSearch populates query string from the saved search`() = runTest {
        fakeSavedSearchRepo.seed(savedSearch(id = "s1", name = "My Search", queryString = "tag:work"))
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnLoadSavedSearch(SavedSearchId("s1")))
        runCurrent()

        val state = vm.stateFlow.value
        assertEquals("tag:work", state.query)
        assertEquals(SavedSearchId("s1"), state.activeSavedSearchId)
    }

    // ─── Delete saved search ───────────────────────────────────────────────────

    @Test
    fun `OnDeleteSavedSearch calls delete on the repository`() = runTest {
        fakeSavedSearchRepo.seed(savedSearch(id = "s1", name = "To Delete"))
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnDeleteSavedSearch(SavedSearchId("s1")))
        runCurrent()

        assertEquals(listOf("s1"), fakeSavedSearchRepo.deleted)
    }

    // ─── Rename saved search ───────────────────────────────────────────────────

    @Test
    fun `OnRenameSavedSearch updates the name via upsert`() = runTest {
        fakeSavedSearchRepo.seed(savedSearch(id = "s1", name = "Old Name"))
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnRenameSavedSearch(SavedSearchId("s1"), "New Name"))
        runCurrent()

        val upserts = fakeSavedSearchRepo.upserted
        assertEquals(1, upserts.size)
        assertEquals("New Name", upserts.last().name)
    }

    // ─── Toggle pin ────────────────────────────────────────────────────────────

    @Test
    fun `OnTogglePin calls taskRepo togglePinned with the task id`() = runTest {
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnTogglePin(TaskId("task-x")))
        runCurrent()

        assertEquals(listOf("task-x"), fakeTaskRepo.pinnedToggled)
    }

    // ─── Apply filter ──────────────────────────────────────────────────────────

    @Test
    fun `OnApplyFilter null clears the active filter`() = runTest {
        val vm = createVm()
        runCurrent()

        vm.onIntent(SearchIntent.OnApplyFilter(null))
        advanceUntilIdle()

        assertNull(vm.stateFlow.value.activeFilter)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun savedSearch(
        id: String,
        name: String,
        queryString: String = "tag:work",
    ) = SavedSearch(
        id = SavedSearchId(id),
        userId = UserId("user-a"),
        name = name,
        queryString = queryString,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        /**
         * Minimal in-memory [SavedSearchRepository] for testing.
         * Only implements what [SearchViewModel] actually calls.
         */
        class FakeSavedSearchRepository : SavedSearchRepository {
            private val searches = MutableStateFlow<List<SavedSearch>>(emptyList())
            val upserted = mutableListOf<SavedSearch>()
            val deleted = mutableListOf<String>()

            fun seed(vararg items: SavedSearch) {
                searches.value = searches.value + items
            }

            fun clear() {
                searches.value = emptyList()
                upserted.clear()
                deleted.clear()
            }

            override fun observeAll(): Flow<List<SavedSearch>> = searches

            override fun observe(id: SavedSearchId): Flow<SavedSearch?> {
                val result = MutableStateFlow<SavedSearch?>(null)
                // Seed from current value
                result.value = searches.value.find { it.id == id }
                // Mirror future changes
                result.value = searches.value.find { it.id == id }
                return result
            }

            override suspend fun get(id: SavedSearchId): SavedSearch? =
                searches.value.find { it.id == id }

            override suspend fun create(item: SavedSearch): Result<SavedSearch> =
                upsert(item)

            override suspend fun update(item: SavedSearch): Result<SavedSearch> =
                upsert(item)

            override suspend fun upsert(search: SavedSearch): Result<SavedSearch> {
                upserted += search
                searches.value = searches.value.filterNot { it.id == search.id } + search
                return Result.success(search)
            }

            override suspend fun delete(id: SavedSearchId): Result<Unit> {
                deleted += id.raw
                searches.value = searches.value.filterNot { it.id == id }
                return Result.success(Unit)
            }

            override suspend fun currentUserId(): String = "user-a"

            override suspend fun findByNameForUser(userId: String, name: String): SavedSearch? =
                searches.value.find { it.userId.value == userId && it.name == name }
        }

        /**
         * Minimal [SearchQueryResolver] that returns a trivial resolved query,
         * allowing the VM's own state-machine logic to be tested without
         * needing a fully-wired search pipeline.
         */
        class FakeSearchQueryResolver : SearchQueryResolver {
            override suspend fun resolve(query: Query, userId: String): ResolvedSearchQuery =
                ResolvedSearchQuery(
                    taskFilter = null,
                    dateRange = null,
                    freeText = null,
                    needsPostFilter = false,
                )
        }
    }
}
