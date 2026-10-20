// Preview fixtures only: the timestamps are sample data for `@Preview`, not
// behaviour. The rule is right about production code and has nothing to say
// about a hard-coded `Instant` in a composable nobody ships.

package com.singularity.todo.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.StaticText
import com.singularity.todo.core.ui.components.rememberBridgedTextFieldState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.presentation.RenameSearchDialog
import com.singularity.todo.feature.search.presentation.SaveSearchDialog
import com.singularity.todo.feature.search.presentation.SavedSearchesRow
import com.singularity.todo.feature.search.presentation.SimpleFilterSheet
import com.singularity.todo.feature.search.presentation.nav.LocalSearchNavigator
import com.singularity.todo.feature.search.presentation.nav.SearchNavigator
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = koinViewModel()) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val navigator = LocalSearchNavigator.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var searchToRename by remember { mutableStateOf<Pair<SavedSearchId, String>?>(null) }

    SearchEventsHandler(viewModel = viewModel, snackbarHostState = snackbarHostState)

    SearchSheetHosts(
        showFilterSheet = showFilterSheet,
        showSaveDialog = showSaveDialog,
        searchToRename = searchToRename,
        activeFilter = state.activeFilter,
        query = state.query,
        onDismissFilterSheet = { showFilterSheet = false },
        onApplyFilter = { filter ->
            viewModel.onIntent(SearchIntent.OnApplyFilter(filter))
            showFilterSheet = false
        },
        onDismissSaveDialog = { showSaveDialog = false },
        onSaveSearch = { name ->
            viewModel.onIntent(SearchIntent.OnSaveCurrentSearch(name))
            showSaveDialog = false
        },
        onDismissRename = { searchToRename = null },
        onRename = { id, newName ->
            viewModel.onIntent(
                SearchIntent.OnRenameSavedSearch(id, newName),
            )
            searchToRename = null
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Filter")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SearchInputBar(
                querySource = state.querySource,
                onQueryChange = { viewModel.onIntent(SearchIntent.OnQueryChange(it)) },
            )

            if (state.savedSearches.isNotEmpty()) {
                SavedSearchesRow(
                    savedSearches = state.savedSearches,
                    activeSavedSearchId = state.activeSavedSearchId,
                    onLoadSearch = { id -> viewModel.onIntent(SearchIntent.OnLoadSavedSearch(id)) },
                    onSaveClick = { showSaveDialog = true },
                    onRename = { id, newName -> searchToRename = id to newName },
                    onDelete = { id -> viewModel.onIntent(SearchIntent.OnDeleteSavedSearch(id)) },
                )
            }

            when (val content = searchContent(state.query, state.results)) {
                SearchContent.IdlePrompt -> EmptyState(
                    title = "Enter a search query",
                    testTag = TestTags.SEARCH_EMPTY_PLACEHOLDER,
                )

                SearchContent.NoResults -> EmptyState(
                    title = "No results found",
                    testTag = TestTags.SEARCH_EMPTY_RESULTS,
                )

                SearchContent.Results -> SearchResultsList(
                    results = state.results,
                    navigator = navigator,
                    onPin = { viewModel.onIntent(SearchIntent.OnTogglePin(it)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInputBar(
    querySource: StaticText,
    onQueryChange: (String) -> Unit,
) {
    val queryState = rememberBridgedTextFieldState(
        source = querySource,
        onTextChanged = onQueryChange,
    )
    OutlinedTextField(
        state = queryState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(TestTags.SEARCH_INPUT),
        placeholder = { Text("Search tasks, notes, projects...") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        lineLimits = TextFieldLineLimits.SingleLine,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheetHosts(
    showFilterSheet: Boolean,
    showSaveDialog: Boolean,
    searchToRename: Pair<SavedSearchId, String>?,
    activeFilter: SimpleFilter?,
    query: String,
    onDismissFilterSheet: () -> Unit,
    onApplyFilter: (SimpleFilter?) -> Unit,
    onDismissSaveDialog: () -> Unit,
    onSaveSearch: (String) -> Unit,
    onDismissRename: () -> Unit,
    onRename: (SavedSearchId, String) -> Unit,
) {
    if (showFilterSheet) {
        SimpleFilterSheet(
            initialFilter = activeFilter,
            onApply = onApplyFilter,
            onDismiss = onDismissFilterSheet,
        )
    }

    if (showSaveDialog) {
        SaveSearchDialog(
            initialName = query.take(30),
            onDismiss = onDismissSaveDialog,
            onSave = onSaveSearch,
        )
    }

    searchToRename?.let { (id, currentName) ->
        RenameSearchDialog(
            currentName = currentName,
            onDismiss = onDismissRename,
            onRename = { newName -> onRename(id, newName) },
        )
    }
}

@Composable
private fun SearchResultsList(
    results: SearchResults,
    navigator: SearchNavigator,
    onPin: (com.singularity.todo.feature.tasks.domain.model.TaskId) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (results.tasks.isNotEmpty()) {
            item { SectionHeader("Tasks") }
            items(results.tasks.take(5), key = { it.id.value }) { task ->
                TaskCard(
                    task = task,
                    onClick = { navigator.openTask(task.id) },
                    actions = TaskCardActions(
                        onPin = { onPin(task.id) },
                        onAiClick = { navigator.openTask(task.id) },
                    ),
                )
            }
        }
        if (results.notes.isNotEmpty()) {
            item { SectionHeader("Notes") }
            items(results.notes.take(5), key = { it.id.value }) { note ->
                SimpleResultCard(
                    title = note.title.ifBlank { "Untitled" },
                    onClick = { navigator.openNote(note.id) },
                )
            }
        }
        if (results.projects.isNotEmpty()) {
            item { SectionHeader("Projects") }
            items(results.projects.take(5), key = { it.id.value }) { project ->
                SimpleResultCard(
                    title = project.name,
                    onClick = { navigator.openProject(project.id) },
                )
            }
        }
        if (results.tags.isNotEmpty()) {
            item { SectionHeader("Tags") }
            items(results.tags.take(5), key = { it.id.value }) { tag ->
                SimpleResultCard(title = tag.name, onClick = { navigator.openTag(tag.id) })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SimpleResultCard(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Text(text = title, modifier = Modifier.padding(12.dp))
    }
}

// ===== Previews =====

@Preview
@Composable
private fun SearchScreenEmptyPreview() = PreviewThemed(darkTheme = false) {
    SearchScreen()
}

@Preview
@Composable
private fun SavedSearchesRowPreview() = PreviewThemed(darkTheme = false) {
    SavedSearchesRow(
        savedSearches = listOf(
            SavedSearch(
                id = SavedSearchId.generate(),
                userId = UserId("u1"),
                name = "High Priority",
                queryString = "priority:high",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
            SavedSearch(
                id = SavedSearchId.generate(),
                userId = UserId("u1"),
                name = "Work Tasks",
                queryString = "tag:work",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        ),
        activeSavedSearchId = null,
        onLoadSearch = {},
        onSaveClick = {},
        onRename = { _, _ -> },
        onDelete = {},
    )
}

// ─── Helpers ────────────────────────────────────────────────────────────────────

@Composable
private fun SearchEventsHandler(
    viewModel: SearchViewModel,
    snackbarHostState: SnackbarHostState,
) {
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SearchUiEvent.Error -> event.message
                is SearchUiEvent.QueryParseError -> "Parse error: ${event.message}"
                is SearchUiEvent.SavedSuccessfully -> "Search saved"
            }
            snackbarHostState.showSnackbar(message)
        }
    }
}
