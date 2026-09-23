package com.singularity.todo.feature.search

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: SearchResults = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
)

sealed interface SearchUiEvent {
    data class Error(val message: String) : SearchUiEvent
}

/**
 * Global search screen ViewModel.
 *
 * Owns: search query, debounced search execution, result categories (tasks, notes, projects, tags).
 * Triggers: query text changes (debounced 300ms).
 * One-shot events: [SearchUiEvent.Error].
 *
 * @see SearchUiState
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val searchUseCase: SearchUseCase,
    private val taskRepo: TaskRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init {
        addCloseable(scope)
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _events = MutableSharedFlow<SearchUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SearchUiEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /**
     * Pure results flow — no side effects on [_state].
     * [flatMapLatest] cancels in-flight search when query changes.
     * Uses [channelFlow] because [SearchUseCase.invoke] is suspend.
     */
    private val results: StateFlow<SearchResults> = _query
        .flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf(SearchResults(emptyList(), emptyList(), emptyList(), emptyList()))
            } else {
                channelFlow {
                    val userId = currentUser.scopedUserId.value.value
                    searchUseCase(q, userId).collect { send(it) }
                }
            }
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000),
            SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
        )

    init {
        // Combine query + results into state — no side effects inside the flow chain
        scope.launch {
            combine(
                _query.debounce(300.milliseconds),
                results,
            ) { q, results ->
                SearchUiState(
                    query = q,
                    isSearching = q.isNotBlank(),
                    results = results,
                )
            }.collect { newState ->
                _state.value = newState
            }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun togglePin(taskId: TaskId) {
        scope.fireAndForget(
            errorLabel = "Pin failed",
            onError = { e ->
                _events.tryEmit(SearchUiEvent.Error("Pin failed: ${e.message ?: "unknown"}"))
            },
        ) {
            taskRepo.togglePinned(taskId)
        }
    }
}
