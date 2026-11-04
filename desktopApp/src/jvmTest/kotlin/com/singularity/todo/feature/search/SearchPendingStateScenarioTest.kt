@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.domain.port.SavedSearchRepository
import com.singularity.todo.feature.search.presentation.nav.LocalSearchNavigator
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.Query
import com.singularity.todo.feature.search.query.ResolvedSearchQuery
import com.singularity.todo.feature.search.query.SearchQueryResolver
import com.singularity.todo.feature.search.query.SingularityQueryParser
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
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
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagFocused
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop automation for scenario `SEARCH-11` — see
 * `scripts/src/traceability/scenarios/search/SEARCH-11.yaml` — and for the two
 * affordances the search field carries beside it.
 *
 * The `@DisplayName` below is the linkage. The id is the **first token** of the
 * string, and that strictness is the contract: the traceability scanner rejects
 * an id anywhere else rather than guessing.
 *
 * ## What these tests mount, and why not the whole app
 *
 * They mount the search screen with a real `SearchViewModel` over the test
 * fakes, not the `App()` the desktop flow tests mount. The pending state is the
 * reason: the search is debounced and then run, and a flow test's clock advances
 * past both before an assertion can look — so "the answer has not come yet" is a
 * state a flow test cannot hold still. A resolver that waits on a gate holds it
 * still, and lets the same test then release the gate and assert the answer
 * arriving.
 *
 * The screen is otherwise the screen the app draws: the field, its clear button,
 * the mode switch and the outcome all come from `SearchScreen`.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchPendingStateScenarioTest {
    /**
     * The query is stated and the answer has not come: the screen says so, and
     * does not claim the search found nothing.
     *
     * This is the defect the scenario exists for. Before it, a non-blank query
     * with no results was reported as "No results found" — confident, wrong, and
     * replaced by the real results a moment later.
     */
    @Test
    @DisplayName("SEARCH-11 the screen says the search is running before it answers")
    fun the_screen_says_the_search_is_running_before_it_answers() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                fakes.seedTask("Buy milk")
                val gate = CompletableDeferred<Unit>()
                val viewModel = fakes.viewModel(GatedResolver(gate, fakes.resolver()))

                showSearch(viewModel)

                assertTagDisplayed(TestTags.SEARCH_EMPTY_PLACEHOLDER)

                typeIntoTag(TestTags.SEARCH_INPUT, "milk")

                // The question is being asked: the indicator is on screen and the
                // empty answer is not stated.
                assertTagDisplayed(TestTags.SEARCH_LOADING)
                awaitTagGone(TestTags.SEARCH_EMPTY_RESULTS)

                // The answer arrives. The loading tag leaving is the wait; the
                // result is asserted after it, because an immediate assertion
                // would be racing the search rather than observing it.
                gate.complete(Unit)

                awaitTagGone(TestTags.SEARCH_LOADING)
                assertTagDisplayed(TestTags.taskItem("Buy milk"))
            } finally {
                fakes.close()
            }
        }

    /**
     * The clear button empties the query, and the screen returns to the state it
     * was in before anything was typed.
     *
     * The button is the field's own: clearing a query used to mean long-press,
     * "Select all", delete.
     */
    @Test
    fun the_clear_button_empties_the_query_and_the_idle_prompt_returns() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                fakes.seedTask("Buy milk")
                showSearch(fakes.viewModel())

                typeIntoTag(TestTags.SEARCH_INPUT, "milk")
                // The answer is asynchronous: wait for it before asserting it.
                awaitTagGone(TestTags.SEARCH_LOADING)
                assertTagDisplayed(TestTags.taskItem("Buy milk"))

                clickTag(TestTags.SEARCH_CLEAR_BUTTON)

                assertTagDisplayed(TestTags.SEARCH_EMPTY_PLACEHOLDER)
                awaitTagGone(TestTags.taskItem("Buy milk"))
                // There is nothing left to clear, so the field no longer offers the
                // control: a button that clears nothing is a button that does nothing.
                awaitTagGone(TestTags.SEARCH_CLEAR_BUTTON)
            } finally {
                fakes.close()
            }
        }

    /**
     * The field asks for the focus when the screen appears.
     *
     * A screen opened in order to be typed into should put the caret in its
     * field; without the request the user has to find the field and tap it.
     */
    @Test
    fun the_query_field_holds_the_focus_when_the_screen_appears() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                showSearch(fakes.viewModel())

                assertTagFocused(TestTags.SEARCH_INPUT)
            } finally {
                fakes.close()
            }
        }

    /**
     * Mounts the search screen over [viewModel], with the navigator a preview
     * would get: the screen reads `LocalSearchNavigator` even when nothing is
     * clicked, so a test that omitted it would fail on the read rather than on
     * the behaviour.
     */
    private fun DesktopComposeUiTest.showSearch(viewModel: SearchViewModel) {
        setContent {
            SingularityTheme(themeMode = ThemeMode.Light) {
                CompositionLocalProvider(LocalSearchNavigator provides PreviewSearchNavigator()) {
                    SearchScreen(viewModel = viewModel)
                }
            }
        }
    }

    /**
     * The repositories and the use case the screen is driven by.
     *
     * The fakes answer the way the production ones do — the index still says
     * where the word is only when there is a real SQLite, which is why the
     * positions are asserted in `FtsMatchOffsetsAgreementTest` and not here.
     */
    private class SearchFakes {
        private val taskRepo = FakeTaskRepository()
        private val noteRepo = FakeNotesRepository()
        private val projectRepo = FakeProjectsRepository()
        private val tagRepo = FakeTagsRepository()
        private val savedSearchRepo = InMemorySavedSearchRepository()

        /**
         * The screen's own dispatcher: the debounce has to run in real time,
         * because the tests assert the state the debounce holds.
         *
         * The test that owns the fakes closes it in a `finally`. A scope that is
         * never closed leaves the screen's collectors subscribed to these fakes
         * after the scene has been torn down — a leak, and a race with whatever
         * runs next.
         */
        private val scope = AutoCloseableCoroutineScope(Dispatchers.Default + SupervisorJob())

        /** Cancels the screen's collectors. Every test closes its fakes. */
        fun close() {
            scope.close()
        }

        fun seedTask(title: String) {
            taskRepo.seed(
                Task(
                    id = TaskId("t-${title.lowercase().replace(' ', '-')}"),
                    title = title,
                    createdAt = NOW,
                    updatedAt = NOW,
                    userId = TestUsers.DEFAULT,
                ),
            )
        }

        /** The resolver production uses, over lookups that find no tag or project. */
        fun resolver(): SearchQueryResolver =
            DefaultSearchQueryResolver(
                tagLookup = NO_TAGS,
                projectLookup = NO_PROJECTS,
                clock = FakeClock(NOW),
                timeZone = TEST_TZ,
            )

        fun viewModel(resolver: SearchQueryResolver = resolver()): SearchViewModel =
            SearchViewModel(
                searchUseCase =
                    SearchUseCase(
                        taskRepo = taskRepo,
                        noteRepo = noteRepo,
                        projectRepo = projectRepo,
                        tagRepo = tagRepo,
                        textIndex =
                            FakeSearchTextIndex(
                                taskRepo = taskRepo,
                                noteRepo = noteRepo,
                                projectRepo = projectRepo,
                                tagRepo = tagRepo,
                            ),
                        queryResolver = resolver,
                    ),
                savedSearchRepo = savedSearchRepo,
                taskRepo = taskRepo,
                parseQuery = { input -> SingularityQueryParser(input).parse() },
                clock = FakeClock(NOW),
                // The screen's own dispatcher, from the fakes: the debounce has to
                // run in real time, because the test asserts the state the debounce
                // holds. The fakes own it so that the test can close it.
                scope = scope,
            )
    }

    /**
     * A resolver that does not answer until [gate] is completed.
     *
     * It stands in for the time between stating a query and the search answering
     * it. Everything else is the production resolver, so the answer, when it
     * comes, is the answer the app would give.
     */
    private class GatedResolver(
        private val gate: CompletableDeferred<Unit>,
        private val delegate: SearchQueryResolver,
    ) : SearchQueryResolver {
        override suspend fun resolve(
            query: Query,
            userId: String,
            mode: SearchMode,
        ): ResolvedSearchQuery {
            gate.await()
            return delegate.resolve(query, userId, mode)
        }
    }

    /**
     * In-memory [SavedSearchRepository] for a screen that has no saved searches.
     *
     * The screen reads the current user and the saved searches on appearance;
     * nothing in these tests saves one.
     */
    private class InMemorySavedSearchRepository : SavedSearchRepository {
        private val searches = MutableStateFlow<List<SavedSearch>>(emptyList())

        override fun observeAll(): Flow<List<SavedSearch>> = searches

        override fun observe(id: SavedSearchId): Flow<SavedSearch?> =
            flowOf(searches.value.find { it.id == id })

        override suspend fun currentUserId(): String = TestUsers.DEFAULT.value

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
    }

}

/**
 * The instant the fakes answer with.
 *
 * Top-level rather than a companion member: [SearchPendingStateScenarioTest.SearchFakes]
 * is a nested class, and a nested class reads the file's declarations without
 * reaching through the class it is nested in.
 */
private val NOW: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)

/** The resolver of these tests finds no tag by name — nothing here asks about one. */
private val NO_TAGS =
    object : TagLookup {
        override suspend fun findByName(
            userId: String,
            name: String,
        ): TagLookupResult? = null
    }

/** The resolver of these tests finds no project by name — nothing here asks about one. */
private val NO_PROJECTS =
    object : ProjectLookup {
        override suspend fun findByName(
            userId: String,
            name: String,
        ): ProjectLookupResult? = null
    }
