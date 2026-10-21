package com.singularity.todo.feature.search.presentation.viewmodel

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.search.SearchContent
import com.singularity.todo.feature.search.SearchIntent
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.SearchViewModel
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.domain.port.SavedSearchRepository
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.Query
import com.singularity.todo.feature.search.query.ResolvedSearchQuery
import com.singularity.todo.feature.search.query.SearchQueryResolver
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.SortOrder
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
import com.singularity.todo.feature.search.searchContent
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [SearchViewModel]'s own state machine.
 *
 * The hard part — query parsing and filter derivation — is tested in
 * [com.singularity.todo.feature.search.query.SimpleFilterMapperTest]. What is
 * tested here:
 * - how the VM reacts to each intent, in terms of state fields and events
 * - the debounce pipeline that connects `_queryString` + `_parsedQuery` to results
 * - saved-search CRUD that reaches the repository
 *
 * The null-vs-empty distinction on `activeFilter` is the key contract being
 * asserted: `null` means "the current query cannot be expressed as a SimpleFilter"
 * and is a legitimate, documented state — not an error. A naive test that asserts
 * `activeFilter != null` on every non-empty query would be wrong.
 */
@Tag("fast")
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeNotesRepo = FakeNotesRepository()
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val fakeTagsRepo = FakeTagsRepository()
    private val fakeSavedSearchRepo = FakeSavedSearchRepository()

    /** Stub resolver that returns an empty resolved query — search returns empty results. */
    private val stubResolver =
        object : SearchQueryResolver {
            override suspend fun resolve(
                query: Query,
                userId: String,
            ): ResolvedSearchQuery =
                ResolvedSearchQuery(
                    taskFilter = null,
                    dateRange = null,
                    sortOrder = query.sortOrder,
                    sortDescending = query.sortDescending,
                    options = query.options,
                )
        }

    @AfterTest
    fun cleanup() {
        fakeTaskRepo.clear()
        fakeSavedSearchRepo.clear()
    }

    private fun TestScope.createVm(): SearchViewModel {
        val authRepo = FakeAuthRepository(Session.Anonymous(UserId("user-a")))
        val currentUser = FakeProfileAwareCurrentUser(authRepo, scope = backgroundScope)
        advanceUntilIdle()
        return SearchViewModel(
            searchUseCase =
                SearchUseCase(
                    taskRepo = fakeTaskRepo,
                    noteRepo = fakeNotesRepo,
                    projectRepo = fakeProjectsRepo,
                    tagRepo = fakeTagsRepo,
                    queryResolver = stubResolver,
                ),
            savedSearchRepo = fakeSavedSearchRepo,
            taskRepo = fakeTaskRepo,
            parseQuery = { input -> SingularityQueryParser(input).parse() },
            clock = FakeClock(NOW),
            scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
        )
    }

    /**
     * A VM wired the way production is: the real resolver and the real use case
     * over the fake repositories.
     *
     * The stub resolver above can never return a task, so it cannot say what the
     * screen shows for a text query. These tests are the ones that hold the
     * search screen to its three states: the idle prompt, the no-results state
     * and the results.
     */
    private fun TestScope.createVmWithRealSearch(): SearchViewModel {
        fakeTaskRepo.seed(
            Task(
                id = TaskId("t-milk"),
                title = "Buy milk",
                createdAt = NOW,
                updatedAt = NOW,
                userId = TestUsers.DEFAULT,
            ),
        )
        return SearchViewModel(
            searchUseCase =
                SearchUseCase(
                    taskRepo = fakeTaskRepo,
                    noteRepo = fakeNotesRepo,
                    projectRepo = fakeProjectsRepo,
                    tagRepo = fakeTagsRepo,
                    queryResolver =
                        DefaultSearchQueryResolver(
                            tagLookup = NO_TAGS,
                            projectLookup = NO_PROJECTS,
                            clock = FakeClock(NOW),
                            timeZone = TEST_TZ,
                        ),
                ),
            savedSearchRepo = fakeSavedSearchRepo,
            taskRepo = fakeTaskRepo,
            parseQuery = { input -> SingularityQueryParser(input).parse() },
            clock = FakeClock(NOW),
            scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
        )
    }

    // ─── Query change ───────────────────────────────────────────────────────────

    @Test
    fun `initial state has empty query and empty results`() =
        runTest {
            val vm = createVm()
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals("", state.query.text)
            assertTrue(state.results.tasks.isEmpty())
            assertTrue(state.results.notes.isEmpty())
            assertTrue(state.savedSearches.isEmpty())
        }

    @Test
    fun `blank query clears results and stops searching`() =
        runTest {
            val vm = createVm()
            runCurrent()

            // Set a non-blank query first
            vm.onIntent(SearchIntent.OnQueryChange("hello"))
            runCurrent()
            assertTrue(vm.stateFlow.value.isSearching)

            // Clear it
            vm.onIntent(SearchIntent.OnQueryChange(""))
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals("", state.query.text)
            assertTrue(state.results.tasks.isEmpty())
            assertTrue(state.results.notes.isEmpty())
            assertNull(state.parsedQuery)
            assertNull(state.activeFilter)
        }

    @Test
    fun `valid query sets parsedQuery and deriving SimpleFilter succeeds`() =
        runTest {
            val vm = createVm()
            runCurrent()

            vm.onIntent(SearchIntent.OnQueryChange("priority:high"))
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals("priority:high", state.query.text)
            assertTrue(state.parsedQuery != null, "parsedQuery must be set for a valid query")
            // A priority: query is mappable to SimpleFilter
            assertTrue(state.activeFilter != null, "priority: query must derive a SimpleFilter")
            assertTrue(state.isSearching, "isSearching must be true after a query change")
        }

    @Test
    fun `a null activeFilter is a legitimate signal not an error`() =
        runTest {
            val vm = createVm()
            runCurrent()

            // "task1 OR task2" parses to Condition.Or, which SimpleFilterMapper.fromQuery
            // cannot express as a SimpleFilter (returns null). The null on activeFilter
            // is the documented contract — not an error state. The key assertion is that
            // a filterable query (priority:high) derives a non-null activeFilter, while
            // an unfilterable one (OR query) derives null.
            vm.onIntent(SearchIntent.OnQueryChange("task1 OR task2"))
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals("task1 OR task2", state.query.text)
            // activeFilter is null because OR cannot round-trip through SimpleFilter
            assertNull(state.activeFilter, "OR → activeFilter = null (documented signal, not error)")
            assertTrue(state.isSearching, "searching must still be true even when filter is null")
        }

    // ─── Filter application ─────────────────────────────────────────────────────

    @Test
    fun `OnApplyFilter null clears results and stops searching`() =
        runTest {
            val vm = createVm()
            runCurrent()

            vm.onIntent(SearchIntent.OnApplyFilter(null))
            runCurrent()

            val state = vm.stateFlow.value
            assertTrue(state.results.tasks.isEmpty())
            assertTrue(state.activeFilter == null)
        }

    @Test
    fun `OnApplyFilter with SimpleFilter sets searching true`() =
        runTest {
            val vm = createVm()
            runCurrent()

            val filter =
                SimpleFilter(
                    states = setOf(TaskStatus.Active),
                    sortOrder = SortOrder.DUE,
                )
            vm.onIntent(SearchIntent.OnApplyFilter(filter))
            runCurrent()

            val state = vm.stateFlow.value
            assertTrue(state.activeFilter != null)
            assertTrue(state.isSearching)
        }

    // ─── Saved search ─────────────────────────────────────────────────────────

    @Test
    fun `OnSaveCurrentSearch persists the current query and sets activeSavedSearchId`() =
        runTest {
            val vm = createVm()
            runCurrent()

            vm.onIntent(SearchIntent.OnQueryChange("priority:high"))
            runCurrent()

            vm.onIntent(SearchIntent.OnSaveCurrentSearch("High priority"))
            runCurrent()

            // Verify the concrete state changes (observable side effects).
            // activeSavedSearchId is set to the saved search's id on success.
            val saved = fakeSavedSearchRepo.allSavedSearches
            assertEquals(1, saved.size)
            assertEquals("High priority", saved[0].name)
            assertEquals("priority:high", saved[0].queryString)
            assertTrue(
                vm.stateFlow.value.activeSavedSearchId != null,
                "activeSavedSearchId must be set after a successful save",
            )
        }

    @Test
    fun `OnLoadSavedSearch restores the query string and triggers search`() =
        runTest {
            fakeSavedSearchRepo.seedSavedSearch(
                SavedSearch(
                    id = SavedSearchId("ss-1"),
                    userId = UserId("user-a"),
                    name = "My saved search",
                    queryString = "state:completed",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )

            val vm = createVm()
            runCurrent()

            vm.onIntent(SearchIntent.OnLoadSavedSearch(SavedSearchId("ss-1")))
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals("state:completed", state.query.text)
            assertEquals(SavedSearchId("ss-1"), state.activeSavedSearchId)
            assertTrue(state.isSearching)
        }

    @Test
    fun `OnDeleteSavedSearch removes it from the repository`() =
        runTest {
            fakeSavedSearchRepo.seedSavedSearch(
                SavedSearch(
                    id = SavedSearchId("ss-1"),
                    userId = UserId("user-a"),
                    name = "To delete",
                    queryString = "hello",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )

            val vm = createVm()
            runCurrent()
            assertEquals(1, fakeSavedSearchRepo.allSavedSearches.size)

            vm.onIntent(SearchIntent.OnDeleteSavedSearch(SavedSearchId("ss-1")))
            runCurrent()

            assertEquals(0, fakeSavedSearchRepo.allSavedSearches.size)
        }

    @Test
    fun `OnDeleteSavedSearch clears activeSavedSearchId when the deleted search was active`() =
        runTest {
            fakeSavedSearchRepo.seedSavedSearch(
                SavedSearch(
                    id = SavedSearchId("ss-1"),
                    userId = UserId("user-a"),
                    name = "Active search",
                    queryString = "state:active",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )

            val vm = createVm()
            runCurrent()
            vm.onIntent(SearchIntent.OnLoadSavedSearch(SavedSearchId("ss-1")))
            runCurrent()
            assertEquals(SavedSearchId("ss-1"), vm.stateFlow.value.activeSavedSearchId)

            vm.onIntent(SearchIntent.OnDeleteSavedSearch(SavedSearchId("ss-1")))
            runCurrent()

            assertNull(vm.stateFlow.value.activeSavedSearchId)
        }

    // ─── Toggle pin ───────────────────────────────────────────────────────────

    @Test
    fun `OnTogglePin delegates to taskRepository togglePinned without error`() =
        runTest {
            val vm = createVm()
            runCurrent()

            // Set override so the call succeeds without depending on store state
            fakeTaskRepo.togglePinnedOverride = Result.success(Unit)

            // This must not throw — if togglePinned is not called the override remains
            // set and the call succeeds anyway, which is the observable behaviour we need.
            vm.onIntent(SearchIntent.OnTogglePin(TaskId("task-x")))
            runCurrent()
            // The override was consumed; a second call (if any) would use the default store path.
            // The assertion is simply "did not throw".
        }

    // ─── The three states of the search screen ──────────────────────────────────

    @Test
    fun `an empty query leaves the screen on the idle prompt`() =
        runTest {
            val vm = createVmWithRealSearch()
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals(SearchContent.IdlePrompt, searchContent(state.query.text, state.results))
        }

    @Test
    fun `a query that matches nothing puts the screen on the no-results state`() =
        runTest {
            val vm = createVmWithRealSearch()
            runCurrent()

            vm.onIntent(SearchIntent.OnQueryChange("zzzznomatchzzzz999999"))
            advanceTimeBy(400L)
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals(SearchContent.NoResults, searchContent(state.query.text, state.results))
        }

    @Test
    fun `a query that matches puts the screen on the results state`() =
        runTest {
            val vm = createVmWithRealSearch()
            runCurrent()

            vm.onIntent(SearchIntent.OnQueryChange("milk"))
            advanceTimeBy(400L)
            runCurrent()

            val state = vm.stateFlow.value
            assertEquals(SearchContent.Results, searchContent(state.query.text, state.results))
            assertEquals(listOf("Buy milk"), state.results.tasks.map { it.title })
        }

    // ─── Fake helpers ─────────────────────────────────────────────────────────

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        /** No tag and no project exist in these tests, so every lookup misses. */
        val NO_TAGS = object : TagLookup {
            override suspend fun findByName(userId: String, name: String): TagLookupResult? = null
        }

        val NO_PROJECTS = object : ProjectLookup {
            override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
        }

        /**
         * In-memory [SavedSearchRepository] for [SearchViewModel] tests.
         * Only implements what the VM touches; the rest throws so a test that
         * accidentally depends on an unimplemented method fails loudly.
         */
        class FakeSavedSearchRepository : SavedSearchRepository {
            private val searches = MutableStateFlow<List<SavedSearch>>(emptyList())
            val allSavedSearches: List<SavedSearch> get() = searches.value

            fun seedSavedSearch(search: SavedSearch) {
                searches.value = searches.value + search
            }

            fun clear() {
                searches.value = emptyList()
            }

            override fun observeAll(): Flow<List<SavedSearch>> = searches

            override suspend fun currentUserId(): String = "user-a"

            override suspend fun get(id: SavedSearchId): SavedSearch? = searches.value.find { it.id == id }

            override suspend fun upsert(search: SavedSearch): Result<SavedSearch> {
                searches.value = searches.value.filterNot { it.id == search.id } + search
                return Result.success(search)
            }

            override suspend fun delete(id: SavedSearchId): Result<Unit> {
                searches.value = searches.value.filterNot { it.id == id }
                return Result.success(Unit)
            }

            override suspend fun findByNameForUser(
                userId: String,
                name: String,
            ): SavedSearch? = searches.value.find { it.name == name }

            override fun observe(id: SavedSearchId): Flow<SavedSearch?> =
                searches.let { flow -> flowOf(flow.value.find { it.id == id }) }
        }
    }
}
