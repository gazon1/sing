package com.singularity.todo.feature.search

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.domain.port.SavedSearchRepository
import com.singularity.todo.feature.search.query.Query
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.search.query.SimpleFilterMapper
import com.singularity.todo.feature.search.query.UnsupportedSimpleFilterException
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// ─── UI contracts ─────────────────────────────────────────────────────────────

/**
 * Persistent UI state for the search screen.
 *
 * @param query Raw query string as entered by the user.
 * @param parsedQuery The last successfully parsed [Query] AST, used for execution.
 *                     Null when the current [query] has not yet parsed successfully.
 * @param activeFilter The [SimpleFilter] derived from [parsedQuery], or null if
 *                      the query cannot be expressed as a SimpleFilter.
 * @param activeSavedSearchId The [SavedSearchId] currently loaded, or null if the
 *                             user is editing a free-form query.
 * @param savedSearches All saved searches for the current user.
 * @param results The latest search results.
 * @param isSearching True while a search is in flight.
 */
data class SearchUiState(
    val query: String = "",
    val parsedQuery: Query? = null,
    val activeFilter: SimpleFilter? = null,
    val activeSavedSearchId: SavedSearchId? = null,
    val savedSearches: List<SavedSearch> = emptyList(),
    val results: SearchResults = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
    val isSearching: Boolean = false,
)

/**
 * One-shot events emitted by [SearchViewModel].
 */
sealed interface SearchUiEvent : MviEvent {
    /** The query string failed to parse. */
    data class QueryParseError(val message: String, val position: Int) : SearchUiEvent

    /** A saved search operation succeeded. */
    data object SavedSuccessfully : SearchUiEvent

    /** A generic error (e.g. repository failure). */
    data class Error(val message: String) : SearchUiEvent
}

/**
 * User intents for the search screen.
 */
sealed interface SearchIntent : MviIntent {
    data class OnQueryChange(val query: String) : SearchIntent
    data class OnApplyFilter(val filter: SimpleFilter?) : SearchIntent
    data class OnSaveCurrentSearch(val name: String) : SearchIntent
    data class OnLoadSavedSearch(val id: SavedSearchId) : SearchIntent
    data class OnDeleteSavedSearch(val id: SavedSearchId) : SearchIntent
    data class OnRenameSavedSearch(val id: SavedSearchId, val newName: String) : SearchIntent
    data class OnTogglePin(val taskId: TaskId) : SearchIntent
}

// ─── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Canonical 5-argument constructor for [SearchViewModel].
 *
 * @param searchUseCase Executes a parsed [Query] against the data layer.
 * @param savedSearchRepo Persists and observes saved searches.
 * @param parseQuery Parses a raw query string into a [Query] AST.
 * @param clock Used to stamp createdAt/updatedAt on saved searches.
 * @param scope Coroutine scope for all ViewModel coroutine work.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val searchUseCase: SearchUseCase,
    private val savedSearchRepo: SavedSearchRepository,
    private val taskRepo: TaskRepository,
    private val parseQuery: (String) -> Query,
    private val clock: Clock,
    private val scope: AutoCloseableCoroutineScope,
) : MviViewModel<SearchUiState, SearchIntent, SearchUiEvent>(
        initialState = SearchUiState(),
        scope = scope,
    ) {

    /** Secondary constructor used by Koin — creates its own [AutoCloseableCoroutineScope]. */
    constructor(
        searchUseCase: SearchUseCase,
        savedSearchRepo: SavedSearchRepository,
        taskRepo: TaskRepository,
        clock: Clock,
    ) : this(
        searchUseCase = searchUseCase,
        savedSearchRepo = savedSearchRepo,
        taskRepo = taskRepo,
        parseQuery = { input -> com.singularity.todo.feature.search.query.SingularityQueryParser(input).parse() },
        clock = clock,
        scope = AutoCloseableCoroutineScope(),
    )

    // ─── Internal state ────────────────────────────────────────────────────────

    private val _queryString = MutableStateFlow("")
    private val _parsedQuery = MutableStateFlow<Query?>(null)
    private val _activeSavedSearchId = MutableStateFlow<SavedSearchId?>(null)
    private val _activeFilter = MutableStateFlow<SimpleFilter?>(null)

    // ─── Observations ──────────────────────────────────────────────────────────

    init {
        // Observe saved searches
        vmScope.launch {
            savedSearchRepo.observeAll().collect { saved ->
                updateState { it.copy(savedSearches = saved) }
            }
        }

        // Execute search whenever parsedQuery or queryString changes
        vmScope.launch {
            combine(_queryString, _parsedQuery) { qs, pq -> qs to pq }
                .debounce(300.milliseconds)
                .flatMapLatest { (qs, pq) ->
                    if (qs.isBlank()) {
                        flowOf(SearchResults(emptyList(), emptyList(), emptyList(), emptyList()))
                    } else {
                        channelFlow {
                            val userId = savedSearchRepo.currentUserId()
                            val q = pq ?: return@channelFlow
                            searchUseCase(q, userId).collect { send(it) }
                        }
                    }
                }
                .collect { results ->
                    updateState {
                        it.copy(results = results, isSearching = false)
                    }
                }
        }

        // Keep query and parsedQuery in sync
        vmScope.launch {
            combine(_queryString, _parsedQuery, _activeFilter, _activeSavedSearchId) { qs, pq, af, asid ->
                listOf(qs, pq, af, asid)
            }.collect { parts ->
                @Suppress("UNCHECKED_CAST")
                updateState {
                    it.copy(
                        query = parts[0] as String,
                        parsedQuery = parts[1] as Query?,
                        activeFilter = parts[2] as SimpleFilter?,
                        activeSavedSearchId = parts[3] as SavedSearchId?,
                    )
                }
            }
        }
    }

    // ─── Intent processing ──────────────────────────────────────────────────────

    override fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.OnQueryChange -> onQueryChange(intent.query)
            is SearchIntent.OnApplyFilter -> onApplyFilter(intent.filter)
            is SearchIntent.OnSaveCurrentSearch -> onSaveCurrentSearch(intent.name)
            is SearchIntent.OnLoadSavedSearch -> onLoadSavedSearch(intent.id)
            is SearchIntent.OnDeleteSavedSearch -> onDeleteSavedSearch(intent.id)
            is SearchIntent.OnRenameSavedSearch -> onRenameSavedSearch(intent.id, intent.newName)
            is SearchIntent.OnTogglePin -> onTogglePin(intent.taskId)
        }
    }

    private fun onQueryChange(query: String) {
        _activeSavedSearchId.value = null
        _queryString.value = query

        if (query.isBlank()) {
            _parsedQuery.value = null
            _activeFilter.value = null
            updateState {
                it.copy(
                    isSearching = false,
                    results = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
                )
            }
            return
        }

        try {
            val parsed = parseQuery(query)
            _parsedQuery.value = parsed
            // Try to derive a SimpleFilter; unsupported conditions → null
            _activeFilter.value = SimpleFilterMapper().fromQuery(parsed).getOrNull()
        } catch (e: com.singularity.todo.feature.search.query.QueryParseException) {
            _parsedQuery.value = null
            _activeFilter.value = null
            vmScope.launch { emit(SearchUiEvent.QueryParseError(e.message ?: "Parse error", e.position)) }
        } catch (e: com.singularity.todo.feature.search.query.UnsupportedSimpleFilterException) {
            // Expected: query cannot be expressed as SimpleFilter — activeFilter = null signals this
            _parsedQuery.value = null
            _activeFilter.value = null
        } catch (e: Exception) {
            _parsedQuery.value = null
            _activeFilter.value = null
            vmScope.launch { emit(SearchUiEvent.Error("Search error: ${e.message ?: "unknown"}")) }
        }
        updateState { it.copy(isSearching = true) }
    }

    private fun onApplyFilter(filter: SimpleFilter?) {
        _activeSavedSearchId.value = null
        _activeFilter.value = filter

        if (filter == null) {
            // Keep _queryString — it may hold a loaded saved search's raw query.
            // Keep _parsedQuery — it remains valid for the raw query.
            updateState {
                it.copy(
                    isSearching = false,
                    results = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
                )
            }
            return
        }

        // Convert SimpleFilter back to Query for execution
        val queryResult = SimpleFilterMapper().toQuery(filter)
        queryResult.fold(
            onSuccess = { query ->
                // Keep _queryString as-is; raw text + filter coexist in state
                _parsedQuery.value = query
                updateState { it.copy(isSearching = true) }
            },
            onFailure = { e ->
                vmScope.launch { emit(SearchUiEvent.Error("Cannot apply filter: ${e.message}")) }
            },
        )
    }

    private fun onLoadSavedSearch(id: SavedSearchId) {
        vmScope.launch {
            val saved = savedSearchRepo.get(id)
            if (saved == null) {
                emit(SearchUiEvent.Error("Saved search not found"))
                return@launch
            }
            _activeSavedSearchId.value = id
            _queryString.value = saved.queryString

            try {
                val parsed = parseQuery(saved.queryString)
                _parsedQuery.value = parsed
                _activeFilter.value = SimpleFilterMapper().fromQuery(parsed).getOrNull()
            } catch (e: com.singularity.todo.feature.search.query.QueryParseException) {
                _parsedQuery.value = null
                _activeFilter.value = null
                emit(SearchUiEvent.QueryParseError(e.message ?: "Parse error", e.position))
            }
            updateState { it.copy(isSearching = true) }
        }
    }

    private fun onSaveCurrentSearch(name: String) {
        vmScope.launch {
            val queryString = _queryString.value.ifBlank { "" }
            val existingId = _activeSavedSearchId.value
            val now = clock.now()

            // Fetch once — avoid duplicate get() calls
            val existingEntity = existingId?.let { savedSearchRepo.get(it) }

            val savedSearch = SavedSearch(
                id = existingEntity?.id ?: SavedSearchId.generate(),
                userId = UserId.anonymous,
                name = name,
                queryString = queryString,
                createdAt = existingEntity?.createdAt ?: now,
                updatedAt = now,
            )

            savedSearchRepo.upsert(savedSearch).fold(
                onSuccess = { updated ->
                    _activeSavedSearchId.value = updated.id
                    emit(SearchUiEvent.SavedSuccessfully)
                },
                onFailure = { e ->
                    emit(SearchUiEvent.Error("Save failed: ${e.message ?: "unknown"}"))
                },
            )
        }
    }

    private fun onDeleteSavedSearch(id: SavedSearchId) {
        vmScope.launch {
            savedSearchRepo.delete(id).fold(
                onSuccess = {
                    if (_activeSavedSearchId.value == id) {
                        _activeSavedSearchId.value = null
                    }
                },
                onFailure = { e ->
                    emit(SearchUiEvent.Error("Delete failed: ${e.message ?: "unknown"}"))
                },
            )
        }
    }

    private fun onRenameSavedSearch(id: SavedSearchId, newName: String) {
        vmScope.launch {
            val existing = savedSearchRepo.get(id) ?: run {
                emit(SearchUiEvent.Error("Saved search not found"))
                return@launch
            }
            val updated = existing.copy(name = newName, updatedAt = clock.now())
            savedSearchRepo.upsert(updated).fold(
                onSuccess = {},
                onFailure = { e ->
                    emit(SearchUiEvent.Error("Rename failed: ${e.message ?: "unknown"}"))
                },
            )
        }
    }

    private fun onTogglePin(taskId: TaskId) {
        vmScope.fireAndForget(
            errorLabel = "Pin failed",
            onError = { e ->
                vmScope.launch { emit(SearchUiEvent.Error("Pin failed: ${e.message ?: "unknown"}")) }
            },
        ) {
            taskRepo.togglePinned(taskId)
        }
    }
}
