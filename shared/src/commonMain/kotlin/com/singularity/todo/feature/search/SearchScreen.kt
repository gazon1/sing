// Preview fixtures only: the timestamps are sample data for `@Preview`, not
// behaviour. The rule is right about production code and has nothing to say
// about a hard-coded `Instant` in a composable nobody ships.

package com.singularity.todo.feature.search

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatchRange
import com.singularity.todo.core.search.inColumn
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.ImeAwareBackHandler
import com.singularity.todo.core.ui.components.OverlayState
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.components.rememberOverlayState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.core.ui.text.highlightedText
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.presentation.RenameSearchDialog
import com.singularity.todo.feature.search.presentation.SaveSearchDialog
import com.singularity.todo.feature.search.presentation.SavedSearchesRow
import com.singularity.todo.feature.search.presentation.SearchModeSwitch
import com.singularity.todo.feature.search.presentation.SimpleFilterSheet
import com.singularity.todo.feature.search.presentation.nav.LocalSearchNavigator
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.feature.search.presentation.nav.SearchNavigator
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = koinViewModel()) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SearchEventsHandler(viewModel = viewModel, snackbarHostState = snackbarHostState)

    SearchScreenContent(
        state = state,
        onIntent = viewModel::onIntent,
        navigator = LocalSearchNavigator.current,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * The search screen without its ViewModel: state in, intents out.
 *
 * The overlay state is owned here, as it was owned by [SearchScreen] before the
 * seam existed. That is what lets a UI test open and dismiss the filter sheet,
 * the save dialog and the rename dialog by mounting this composable — no Koin,
 * no database, no ViewModel. [SearchScreen] stays the wrapper that supplies the
 * state, forwards the intents and shows the events' snackbars.
 *
 * @param state what the screen draws.
 * @param onIntent what the user did, in the ViewModel's own vocabulary.
 * @param navigator where a tapped result leads.
 * @param modifier the modifier applied to the screen's `Scaffold`.
 * @param snackbarHostState the host the wrapper's events are shown in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreenContent(
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
    navigator: SearchNavigator,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val overlay = rememberOverlayState<SearchDialog>()

    // The field's own state, held here and not inside the input bar: the
    // no-results state offers a clear-query action, and that action is the
    // field's clear — the same call the field's trailing button makes. One way
    // to empty the field, two ways to ask for it.
    val queryState =
        rememberBridgedTextFieldState(
            source = state.querySource,
            onTextChanged = { onIntent(SearchIntent.OnQueryChange(it)) },
        )
    val focusManager = LocalFocusManager.current

    // The suggestions' one piece of state. Every trigger either hides the row — a
    // selection, a mode change, the filter sheet, an outside tap — or brings it back:
    // the field was emptied, or it was focused again.
    //
    // Focus *loss* is deliberately not a trigger. On desktop the field loses the
    // focus on the way down of any press outside it, so hiding the row there would
    // take the chip out from under the finger before its tap — and its long press —
    // could land; the tap did not run, because the control it was meant for was
    // gone. And on Android a press outside a field does not take its focus at all,
    // so the trigger would be a no-op there. The tap is the event that says the
    // person went elsewhere, and the tap is what the row listens for.
    var suggestionsDismissed by remember { mutableStateOf(false) }

    fun dismissSuggestions() {
        suggestionsDismissed = true
        focusManager.clearFocus()
    }

    fun dismissThen(action: () -> Unit = {}) {
        dismissSuggestions()
        action()
    }

    // The field emptying brings the suggestions back — the field's own text, which
    // is what the person sees; the ViewModel's copy arrives a frame later carrying
    // the same text. A loaded saved search is not a clear even when its query is
    // empty: the load writes the field from outside, and the row would come back the
    // moment the selection dismissed it. The loaded search is what tells the two
    // apart — a clear has none, because editing drops it.
    val fieldIsEmpty by remember(queryState) { derivedStateOf { queryState.text.isEmpty() } }
    LaunchedEffect(fieldIsEmpty, state.activeSavedSearchId) {
        if (fieldIsEmpty && state.activeSavedSearchId == null) suggestionsDismissed = false
    }

    // Back with the keyboard up closes the keyboard and nothing else: leaving the
    // screen on the same press would discard the field the person is typing into.
    ImeAwareBackHandler { navigator.back() }

    SearchSheetsHost(overlay = overlay, state = state, onIntent = onIntent)

    Scaffold(
        modifier = modifier,
        topBar = { SearchTopBar(onFilter = { dismissThen { overlay.show(SearchDialog.Filter) } }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        RefreshableContent {
            SearchScreenBody(
                modifier = Modifier.padding(padding),
                state = state,
                onIntent = onIntent,
                navigator = navigator,
                queryState = queryState,
                suggestionsDismissed = suggestionsDismissed,
                onInputFocusChanged = { focused -> if (focused) suggestionsDismissed = false },
                onDismissSuggestions = { dismissSuggestions() },
                onShowSaveDialog = { dismissThen { overlay.show(SearchDialog.Save) } },
                onShowRenameDialog = { id, newName ->
                    dismissThen { overlay.show(SearchDialog.Rename(id to newName)) }
                },
            )
        }
    }
}

@Composable
private fun SearchScreenBody(
    modifier: Modifier,
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
    navigator: SearchNavigator,
    queryState: TextFieldState,
    suggestionsDismissed: Boolean,
    onInputFocusChanged: (Boolean) -> Unit,
    onDismissSuggestions: () -> Unit,
    onShowSaveDialog: () -> Unit,
    onShowRenameDialog: (SavedSearchId, String) -> Unit,
) {
    val queryText = state.query.text
    val hasSavedSearches = state.savedSearches.isNotEmpty()
    val showSuggestions = hasSavedSearches && !suggestionsDismissed
    Column(modifier = modifier.fillMaxSize()) {
        SearchInputBar(
            queryState = queryState,
            onFocusChanged = onInputFocusChanged,
        )

        SearchModeSwitch(
            mode = state.mode,
            onModeChange = {
                onDismissSuggestions()
                onIntent(SearchIntent.OnModeChange(it))
            },
        )

        if (hasSavedSearches) {
            SavedSearchesRow(
                savedSearches = state.savedSearches,
                activeSavedSearchId = state.activeSavedSearchId,
                onLoadSearch = { id ->
                    onDismissSuggestions()
                    onIntent(SearchIntent.OnLoadSavedSearch(id))
                },
                onSaveClick = onShowSaveDialog,
                onRename = onShowRenameDialog,
                onDelete = { id -> onIntent(SearchIntent.OnDeleteSavedSearch(id)) },
                query = queryText,
                showSuggestions = showSuggestions,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .dismissSuggestionsOnTap(
                        enabled = showSuggestions,
                        onDismiss = onDismissSuggestions,
                    ),
        ) {
            SearchOutcome(
                content = searchContent(queryText, state.results, state.isSearching),
                results = state.results,
                activeFilter = state.activeFilter,
                projectsById = state.projectsById,
                navigator = navigator,
                onPin = { onIntent(SearchIntent.OnTogglePin(it)) },
                onResultOpened = onDismissSuggestions,
                onClearQuery = { queryState.clearText() },
                onClearFilters = {
                    // The structured constraints go, the free text and the mode
                    // stay — and the filter that goes through the intent is the
                    // one the search runs.
                    state.activeFilter?.let { filter ->
                        onIntent(SearchIntent.OnApplyFilter(filter.withoutStructuredConstraints()))
                    }
                },
            )
        }
    }
}

/**
 * Dismisses the suggestions when the results are tapped, using the standard tap
 * detector.
 *
 * `detectTapGestures` is the framework's own "this was a tap" answer: it cancels on
 * a drag, so scrolling the results is not a tap, and it lets every control under the
 * finger take the press first — a tap on a result card never reaches this detector,
 * so the card's own click is what runs. The detector is here, on the results, and not
 * on the whole screen: a press on the field is not an outside tap, it is the person
 * coming back to type.
 *
 * The hand-written pointer loop this replaced existed only because a dismissal used
 * to be triggered by focus loss, which fires on the way *down* of the press and took
 * the tapped control out of the tree before its click could run. The tap is the
 * trigger now, and a tap has a standard detector.
 */
@Composable
private fun Modifier.dismissSuggestionsOnTap(
    enabled: Boolean,
    onDismiss: () -> Unit,
): Modifier {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    return if (!enabled) {
        this
    } else {
        pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInputBar(
    queryState: TextFieldState,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    // The screen is opened to be typed into: the field asks for the focus once,
    // when it appears. A person who came here to type should not have to tap
    // the field first.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
    OutlinedTextField(
        state = queryState,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .testTag(TestTags.SEARCH_INPUT),
        placeholder = { Text("Search tasks, notes, projects...") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (queryState.text.isNotEmpty()) {
                IconButton(
                    onClick = { queryState.clearText() },
                    modifier = Modifier.testTag(TestTags.SEARCH_CLEAR_BUTTON),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        // The search bar's shape and tone: a tonal pill when the field is at rest,
        // and the primary outline the moment it holds the focus. The field stays a
        // plain input — the expanded SearchBar container belongs to the suggestion
        // surface, not here.
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(onFilter: () -> Unit) {
    TopAppBar(
        title = { Text("Search") },
        actions = {
            IconButton(onClick = onFilter) {
                Icon(Icons.Filled.Tune, contentDescription = "Filter")
            }
        },
    )
}

/**
 * The screen's overlay state, wired to the presentational sheet hosts.
 *
 * The hosts are a list, not a state: one place says which sheet the overlay holds
 * and hands each host the callbacks that close it and the intent it sends.
 */
@Composable
private fun SearchSheetsHost(
    overlay: OverlayState<SearchDialog>,
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
) {
    SearchSheetHosts(
        showFilterSheet = overlay.sheet is SearchDialog.Filter,
        showSaveDialog = overlay.sheet is SearchDialog.Save,
        searchToRename = (overlay.sheet as? SearchDialog.Rename)?.pair,
        activeFilter = state.activeFilter,
        query = state.query.text,
        onDismissFilterSheet = { overlay.dismissSheet() },
        onApplyFilter = { filter ->
            onIntent(SearchIntent.OnApplyFilter(filter))
            overlay.dismissSheet()
        },
        onDismissSaveDialog = { overlay.dismissSheet() },
        onSaveSearch = { name ->
            onIntent(SearchIntent.OnSaveCurrentSearch(name))
            overlay.dismissSheet()
        },
        onDismissRename = { overlay.dismissSheet() },
        onRename = { id, newName ->
            onIntent(SearchIntent.OnRenameSavedSearch(id, newName))
            overlay.dismissSheet()
        },
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
    onApplyFilter: (SimpleFilter) -> Unit,
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

/**
 * What the search screen shows under the field and the switch.
 *
 * Three states, not two — see [SearchContent]. The idle prompt, the no-results
 * state and the results are different products, and the screen says which one
 * it is in.
 */
@Composable
private fun SearchOutcome(
    content: SearchContent,
    results: SearchResults,
    activeFilter: SimpleFilter?,
    projectsById: ImmutableMap<ProjectId, Project>,
    navigator: SearchNavigator,
    onPin: (com.singularity.todo.feature.tasks.domain.model.TaskId) -> Unit,
    onResultOpened: () -> Unit,
    onClearQuery: () -> Unit,
    onClearFilters: () -> Unit,
) {
    when (content) {
        SearchContent.IdlePrompt -> {
            EmptyState(
                title = "Enter a search query",
                testTag = TestTags.SEARCH_EMPTY_PLACEHOLDER,
            )
        }

        SearchContent.Searching -> {
            // The query has been stated and the answer has not come yet. Saying
            // "no results" here would report an empty answer to a search that
            // has not run.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag(TestTags.SEARCH_LOADING))
            }
        }

        SearchContent.NoResults -> {
            // The reason decides the way out. A query that matched nothing
            // because of its words has no filter to take away; one that
            // matched nothing because of its constraints has.
            val hasStructuredFilters = activeFilter?.hasStructuredConstraints == true
            EmptyState(
                title = "No results found",
                subtitle =
                    if (hasStructuredFilters) {
                        "Nothing matched with these filters."
                    } else {
                        "Nothing matched this search."
                    },
                testTag = TestTags.SEARCH_EMPTY_RESULTS,
                actions = {
                    if (hasStructuredFilters) {
                        TextButton(
                            onClick = onClearFilters,
                            modifier = Modifier.testTag(TestTags.SEARCH_CLEAR_FILTERS_BUTTON),
                        ) {
                            Text("Clear filters")
                        }
                    }
                    TextButton(
                        onClick = onClearQuery,
                        modifier = Modifier.testTag(TestTags.SEARCH_CLEAR_QUERY_BUTTON),
                    ) {
                        Text("Clear query")
                    }
                },
            )
        }

        SearchContent.Results -> {
            SearchResultsList(
                results = results,
                projectsById = projectsById,
                navigator = navigator,
                onPin = onPin,
                onResultOpened = onResultOpened,
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: SearchResults,
    projectsById: ImmutableMap<ProjectId, Project>,
    navigator: SearchNavigator,
    onPin: (com.singularity.todo.feature.tasks.domain.model.TaskId) -> Unit,
    onResultOpened: () -> Unit,
) {
    val blockedTaskIds =
        remember(results) {
            Blocking.blockedTaskIds(results.tasks.map { it.record }).toImmutableSet()
        }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (results.tasks.isNotEmpty()) {
            item { SectionHeader("Tasks") }
            items(results.tasks, key = { it.record.id.value }) { hit ->
                // The project of the task, resolved from the map the screen was
                // given — not a lookup per row.
                val project = hit.record.projectId?.let { projectsById[it] }
                TaskCard(
                    task = hit.record,
                    onClick = {
                        onResultOpened()
                        navigator.openTask(hit.record.id)
                    },
                    today = systemToday(),
                    blockedTaskIds = blockedTaskIds,
                    matchRanges = hit.ranges,
                    projectName = project?.name,
                    projectColor = project?.color,
                    actions =
                        TaskCardActions(
                            onPin = { onPin(hit.record.id) },
                        ),
                )
            }
        }
        if (results.notes.isNotEmpty()) {
            item { SectionHeader("Notes") }
            items(results.notes, key = { it.record.id.value }) { hit ->
                SimpleResultCard(
                    title = hit.record.title.ifBlank { "Untitled" },
                    onClick = {
                        onResultOpened()
                        navigator.openNote(hit.record.id)
                    },
                    matchRanges = hit.ranges,
                    column = SearchColumn.TITLE,
                )
            }
        }
        if (results.projects.isNotEmpty()) {
            item { SectionHeader("Projects") }
            items(results.projects, key = { it.record.id.value }) { hit ->
                SimpleResultCard(
                    title = hit.record.name,
                    onClick = {
                        onResultOpened()
                        navigator.openProject(hit.record.id)
                    },
                    matchRanges = hit.ranges,
                    column = SearchColumn.NAME,
                )
            }
        }
        if (results.tags.isNotEmpty()) {
            item { SectionHeader("Tags") }
            items(results.tags, key = { it.record.id.value }) { hit ->
                SimpleResultCard(
                    title = hit.record.name,
                    onClick = {
                        onResultOpened()
                        navigator.openTag(hit.record.id)
                    },
                    matchRanges = hit.ranges,
                    column = SearchColumn.NAME,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
}

/**
 * A result that is a title and nothing else — a note, a project, a tag.
 *
 * @param title the title to draw.
 * @param onClick what to do when the card is clicked.
 * @param matchRanges where the search matched this record. The card draws the
 *   ranges that were measured in the text it draws — a range belongs to the
 *   column it was measured in, and the offsets of one column say nothing about
 *   the characters of another.
 * @param column the column the title is, and the column the ranges of a match
 *   in it are measured in.
 */
@Composable
private fun SimpleResultCard(
    title: String,
    onClick: () -> Unit,
    matchRanges: ImmutableList<TextMatchRange> = persistentListOf(),
    column: SearchColumn = SearchColumn.TITLE,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.medium,
                ).clickable(interactionSource = interactionSource, onClick = onClick)
                .focusable(interactionSource = interactionSource),
    ) {
        SelectableText(
            text = highlightedText(title, matchRanges.inColumn(column)),
            modifier = Modifier.padding(12.dp),
        )
    }
}

// ===== Previews =====

@Preview
@Composable
private fun SearchScreenEmptyPreview() =
    PreviewThemed(darkTheme = false) {
        SearchScreenContent(
            state = SearchUiState(),
            onIntent = {},
            navigator = PreviewSearchNavigator(),
        )
    }

@Preview
@Composable
private fun SavedSearchesRowPreview() =
    PreviewThemed(darkTheme = false) {
        SavedSearchesRow(
            savedSearches =
                listOf(
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
                ).toImmutableList(),
            activeSavedSearchId = null,
            onLoadSearch = {},
            onSaveClick = {},
            onRename = { _, _ -> },
            onDelete = {},
        )
    }

// ─── Helpers ────────────────────────────────────────────────────────────────────

private sealed interface SearchDialog {
    data object Filter : SearchDialog

    data object Save : SearchDialog

    data class Rename(val pair: Pair<SavedSearchId, String>) : SearchDialog
}

@Composable
private fun SearchEventsHandler(
    viewModel: SearchViewModel,
    snackbarHostState: SnackbarHostState,
) {
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message =
                when (event) {
                    is SearchUiEvent.Error -> event.message
                    is SearchUiEvent.QueryParseError -> "Parse error: ${event.message}"
                    is SearchUiEvent.SavedSuccessfully -> "Search saved"
                }
            snackbarHostState.showSnackbar(message)
        }
    }
}
