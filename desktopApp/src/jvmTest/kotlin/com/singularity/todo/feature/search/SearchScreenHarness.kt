@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.settings.ThemeMode
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

/*
 * The harness the search screen's tests share.
 *
 * It lives in its own file because the traceability scanner allows **one**
 * scenario claim per carrier file: a file that two scenarios claim makes the
 * result matrix ambiguous, and the normaliser rejects it. The scenario claims
 * therefore live in `SearchPendingStateScenarioTest`, `SearchClearScenarioTest`
 * and `SearchWordPrefixScenarioTest` — one claim each — and the machinery they
 * share lives here, where it carries no claim of its own.
 */

/**
 * The repositories and the use case the screen is driven by.
 *
 * The fakes answer the way the production ones do — the index still says
 * where the word is only when there is a real SQLite, which is why the
 * positions are asserted in `FtsMatchOffsetsAgreementTest` and not here.
 */
internal class SearchFakes {
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

    fun seedSavedSearch(
        id: String,
        name: String,
        query: String,
    ) {
        savedSearchRepo.seed(
            SavedSearch(
                id = SavedSearchId(id),
                userId = TestUsers.DEFAULT,
                name = name,
                queryString = query,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
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
            projectRepo = projectRepo,
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
internal class GatedResolver(private val gate: CompletableDeferred<Unit>, private val delegate: SearchQueryResolver) :
    SearchQueryResolver {
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

    fun seed(search: SavedSearch) {
        searches.value = searches.value + search
    }

    override fun observeAll(): Flow<List<SavedSearch>> = searches

    override fun observe(id: SavedSearchId): Flow<SavedSearch?> = flowOf(searches.value.find { it.id == id })

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

/**
 * Mounts the search screen over [viewModel], with the navigator a preview
 * would get: the screen reads `LocalSearchNavigator` even when nothing is
 * clicked, so a test that omitted it would fail on the read rather than on
 * the behaviour.
 */
internal fun DesktopComposeUiTest.showSearch(viewModel: SearchViewModel) {
    setContent {
        SingularityTheme(themeMode = ThemeMode.Light) {
            CompositionLocalProvider(LocalSearchNavigator provides PreviewSearchNavigator()) {
                SearchScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * The instant the fakes answer with.
 *
 * Top-level rather than a companion member: [SearchFakes] is a top-level class,
 * and a top-level class reads the file's declarations without reaching through
 * a class it is nested in.
 */
internal val NOW: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)

/** The resolver of these tests finds no tag by name — nothing here asks about one. */
internal val NO_TAGS =
    object : TagLookup {
        override suspend fun findByName(
            userId: String,
            name: String,
        ): TagLookupResult? = null
    }

/** The resolver of these tests finds no project by name — nothing here asks about one. */
internal val NO_PROJECTS =
    object : ProjectLookup {
        override suspend fun findByName(
            userId: String,
            name: String,
        ): ProjectLookupResult? = null
    }
