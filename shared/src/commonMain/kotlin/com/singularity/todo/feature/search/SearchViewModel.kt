package com.singularity.todo.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: SearchResults = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
)

sealed interface SearchUiEvent {
    data class Error(val message: String) : SearchUiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val searchUseCase: SearchUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _events = MutableSharedFlow<SearchUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SearchUiEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val userId: UserId get() = currentUser.scopedUserId.value

    private val results: StateFlow<SearchResults> = _query
        .flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf(SearchResults(emptyList(), emptyList(), emptyList(), emptyList()))
            } else {
                _state.value = _state.value.copy(isSearching = true)
                searchUseCase(q, userId.value)
            }
        }
        .map { results ->
            _state.value = _state.value.copy(isSearching = false, results = results)
            results
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000),
            SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
        )

    init {
        scope.launch {
            results.collect { results ->
                _state.value = _state.value.copy(results = results)
            }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
        _state.value = _state.value.copy(query = query)
    }
}
