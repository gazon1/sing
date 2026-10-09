package com.singularity.todo.feature.notes.presentation.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.theme.NoteSwipeColors
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.ContentStateMapper
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder
import com.singularity.todo.feature.notes.NotesListState
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.notes.components.NoteCardContent
import com.singularity.todo.feature.notes.components.NotesActions
import com.singularity.todo.feature.notes.presentation.nav.LocalNotesNavigator
import com.singularity.todo.feature.notes.presentation.nav.NotesNavigator
import com.singularity.todo.feature.notes.presentation.nav.NotesPreviewWrapper
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import org.koin.compose.viewmodel.koinViewModel

// ─── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(route: NotesRoute.List, viewModel: NotesListViewModel = koinViewModel()) {
    val navigator = LocalNotesNavigator.current
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()

    val actions = remember(viewModel) {
        NotesActions(viewModel::onIntent)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show undo snackbar when a delete is pending.
    LaunchedEffect(pendingDelete) {
        val pd = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "\"${pd.title}\" deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            actions.onUndoDelete(pd.noteId)
        }
    }

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is NotesUiEvent.NavigateToEditor -> navigator.openEditor(event.noteId)
            // The affordance itself is driven by `pendingDelete`; this event carries
            // the same id and title for the same purpose and is not read here.
            is NotesUiEvent.UndoDelete -> { /* handled by LaunchedEffect above */ }
            is NotesUiEvent.Error -> {
                scope.launch { snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short) }
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        NotesScreenContent(
            state = state,
            currentFilter = (state as? NotesUiState.Content)?.list?.filter ?: NoteFilter.All,
            currentSortOrder = (state as? NotesUiState.Content)?.list?.sortOrder ?: NoteSortOrder.UpdatedDesc,
            navigator = navigator,
            onCreateNote = { title -> actions.onCreateNote(title) },
            actions = actions,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

// ─── Content ────────────────────────────────────────────────────────────────

private fun NotesUiState.toContentState() = ContentStateMapper.notes(this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreenContent(
    state: NotesUiState,
    currentFilter: NoteFilter,
    currentSortOrder: NoteSortOrder,
    navigator: NotesNavigator,
    onCreateNote: (title: String) -> Unit,
    modifier: Modifier = Modifier,
    actions: NotesActions = NotesActions.Empty,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val listState = (state as? NotesUiState.Content)?.list
    val isSelectionMode = listState?.isSelectionMode == true
    val selectedCount = listState?.selectedIds?.size ?: 0

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                if (isSelectionMode) {
                    // Selection mode: count + exit + delete
                    TopAppBar(
                        title = { Text("$selectedCount selected") },
                        navigationIcon = {
                            IconButton(onClick = { actions.onExitSelection() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit selection")
                            }
                        },
                        actions = {
                            IconButton(onClick = { actions.onDeleteSelected() }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete selected",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                } else {
                    TopAppBar(
                        title = { Text("Notes") },
                        actions = {
                            Box {
                                IconButton(onClick = { sortMenuExpanded = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                                }
                                SortDropdownMenu(
                                    expanded = sortMenuExpanded,
                                    currentOrder = currentSortOrder,
                                    onSelect = {
                                        actions.onSetSortOrder(it)
                                        sortMenuExpanded = false
                                    },
                                    onDismiss = { sortMenuExpanded = false },
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    FilterChipRow(
                        currentFilter = currentFilter,
                        onFilterChange = { actions.onSetFilter(it) },
                    )
                    NoteSearchField(
                        query = listState?.searchQuery ?: "",
                        onQueryChange = { actions.onSearchQueryChange(it) },
                    )
                    QuickAddRow(onSubmit = onCreateNote)
                }
                HorizontalDivider()
            }
        },
    ) { padding ->
        if (state is NotesUiState.Content && state.list.isEmpty) {
            EmptyState(
                title = "No notes yet",
                subtitle = "Create your first note to get started",
                modifier = Modifier.padding(padding),
                actions = {
                    FilledTonalButton(onClick = { onCreateNote("") }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Create your first note")
                    }
                },
            )
        } else {
            StatefulContent(
                state = state.toContentState(),
                emptyTitle = "No notes yet",
                modifier = Modifier.padding(padding),
            ) { allNotes, contentModifier ->
                NoteList(
                    allNotes = allNotes,
                    pinned = listState?.pinned ?: emptyList(),
                    unpinned = listState?.unpinned ?: emptyList(),
                    templates = listState?.templates ?: emptyList(),
                    dailyNotes = listState?.dailyNotes ?: emptyList(),
                    isSelectionMode = isSelectionMode,
                    selectedIds = listState?.selectedIds ?: emptySet(),
                    navigator = navigator,
                    actions = actions,
                    currentFilter = currentFilter,
                    modifier = contentModifier,
                )
            }
        }
    }
}

@Composable
private fun FilterChipRow(currentFilter: NoteFilter, onFilterChange: (NoteFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoteFilter.entries.forEach { filter ->
            FilterChip(
                selected = currentFilter == filter,
                onClick = { onFilterChange(filter) },
                label = { Text(filter.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

private val NoteFilter.label: String
    get() = when (this) {
        NoteFilter.All -> "All"
        NoteFilter.Pinned -> "Pinned"
        NoteFilter.Archived -> "Archived"
    }

@Composable
private fun SortDropdownMenu(
    expanded: Boolean,
    currentOrder: NoteSortOrder,
    onSelect: (NoteSortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        NoteSortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = { Text(order.label) },
                onClick = { onSelect(order) },
                leadingIcon = if (currentOrder == order) {
                    { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

// ─── Quick-add row ─────────────────────────────────────────────────────────---

// ─── Search field ───────────────────────────────────────────────────────────

/**
 * Title search box. The text field is screen-local echo; the query the list is
 * actually filtered by is debounced 200 ms in the ViewModel, so a fast typist
 * triggers one repository query rather than one per keystroke.
 */
@Composable
private fun NoteSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text("Search notes") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
    )
}

@Composable
private fun QuickAddRow(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Quick add note...") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.NOTES_QUICK_ADD_INPUT),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Add, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (text.isNotBlank()) {
                    onSubmit(text.trim())
                    text = ""
                    focusManager.clearFocus()
                }
            }),
        )
    }
}

private val NoteSortOrder.label: String
    get() = when (this) {
        NoteSortOrder.UpdatedDesc -> "Recently updated"
        NoteSortOrder.UpdatedAsc -> "Oldest updated"
        NoteSortOrder.TitleAsc -> "Title A–Z"
        NoteSortOrder.TitleDesc -> "Title Z–A"
    }

// ─── List ───────────────────────────────────────────────────────────────────

@Composable
private fun NoteList(
    allNotes: List<Note>,
    pinned: List<Note>,
    unpinned: List<Note>,
    templates: List<Note>,
    dailyNotes: List<Note>,
    isSelectionMode: Boolean,
    selectedIds: Set<NoteId>,
    navigator: NotesNavigator,
    actions: NotesActions,
    currentFilter: NoteFilter,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag(TestTags.NOTES_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Daily notes section (shown first, above all other notes)
        if (dailyNotes.isNotEmpty()) {
            stickyHeader(key = "daily_header") {
                Text(
                    text = "Daily Notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(dailyNotes, key = { "daily_${it.id.value}" }) { note ->
                SwipeableNoteCard(
                    note = note,
                    isSelected = note.id in selectedIds,
                    isSelectionMode = isSelectionMode,
                    navigator = navigator,
                    onLongClick = { actions.onEnterSelection(note.id) },
                    onDelete = { actions.onDelete(note.id) },
                    onTogglePin = { actions.onTogglePin(note.id) },
                    onToggleSelection = { actions.onToggleSelection(note.id) },
                    isArchived = false,
                )
            }
        }

        // Templates section
        if (templates.isNotEmpty()) {
            stickyHeader(key = "templates_header") {
                Text(
                    text = "Templates",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(templates, key = { "template_${it.id.value}" }) { note ->
                SwipeableNoteCard(
                    note = note,
                    isSelected = note.id in selectedIds,
                    isSelectionMode = isSelectionMode,
                    navigator = navigator,
                    onLongClick = { actions.onEnterSelection(note.id) },
                    onDelete = { actions.onDelete(note.id) },
                    onTogglePin = { actions.onTogglePin(note.id) },
                    onToggleSelection = { actions.onToggleSelection(note.id) },
                    isArchived = false,
                )
            }
        }

        // Pinned section header
        if (pinned.isNotEmpty()) {
            stickyHeader(key = "pinned_header") {
                Text(
                    text = "Pinned",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(pinned, key = { "pinned_${it.id.value}" }) { note ->
                SwipeableNoteCard(
                    note = note,
                    isSelected = note.id in selectedIds,
                    isSelectionMode = isSelectionMode,
                    navigator = navigator,
                    onLongClick = { actions.onEnterSelection(note.id) },
                    onDelete = { actions.onDelete(note.id) },
                    onTogglePin = { actions.onTogglePin(note.id) },
                    onToggleSelection = { actions.onToggleSelection(note.id) },
                    isArchived = currentFilter == NoteFilter.Archived,
                    onUnarchive = { actions.onUnarchive(note.id) },
                )
            }
        }

        // Unpinned section header
        if (unpinned.isNotEmpty()) {
            stickyHeader(key = "unpinned_header") {
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        items(unpinned, key = { it.id.value }) { note ->
            SwipeableNoteCard(
                note = note,
                isSelected = note.id in selectedIds,
                isSelectionMode = isSelectionMode,
                navigator = navigator,
                onLongClick = { actions.onEnterSelection(note.id) },
                onDelete = { actions.onDelete(note.id) },
                onTogglePin = { actions.onTogglePin(note.id) },
                onToggleSelection = { actions.onToggleSelection(note.id) },
                isArchived = currentFilter == NoteFilter.Archived,
                onUnarchive = { actions.onUnarchive(note.id) },
            )
        }
    }
}

// ─── Swipeable Card ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableNoteCard(
    note: Note,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    navigator: NotesNavigator,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleSelection: () -> Unit,
    isArchived: Boolean = false,
    onUnarchive: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (!isArchived) onTogglePin()
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    if (isArchived) onUnarchive() else onDelete()
                    false
                }

                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !isSelectionMode,
        enableDismissFromEndToStart = !isSelectionMode,
        backgroundContent = {
            SwipeBackground(dismissState.currentValue, isArchived)
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        NoteCardContent(
            note = note,
            isSelected = isSelected,
            modifier = Modifier.combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelection()
                    } else {
                        navigator.openPreview(note.id)
                    }
                },
                onLongClick = onLongClick,
            ),
        )
    }
}

@Composable
private fun SwipeBackground(dismissValue: SwipeToDismissBoxValue, isArchived: Boolean) {
    val color by animateColorAsState(
        targetValue = when (dismissValue) {
            SwipeToDismissBoxValue.StartToEnd -> NoteSwipeColors.Archive

            SwipeToDismissBoxValue.EndToStart ->
                if (isArchived) NoteSwipeColors.UnArchive else NoteSwipeColors.Delete

            SwipeToDismissBoxValue.Settled -> Color.Transparent
        },
        label = "swipe_bg",
    )
    val alignment = when (dismissValue) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
    }
    val icon = when (dismissValue) {
        SwipeToDismissBoxValue.StartToEnd -> if (!isArchived) Icons.Default.PushPin else null
        SwipeToDismissBoxValue.EndToStart -> if (isArchived) Icons.Filled.Autorenew else Icons.Default.Delete
        SwipeToDismissBoxValue.Settled -> null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ─── Preview ────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotesScreenContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesPreviewWrapper {
        NotesScreenContent(
            state = NotesUiState.Content(
                list = NotesListState(
                    pinned = listOf(
                        PreviewSamples.note("n1", "Pinned Note", "**Pinned** content"),
                    ),
                    unpinned = listOf(
                        PreviewSamples.note("n2", "Ideas", "Meeting notes and **brainstorming**"),
                        PreviewSamples.note("n3", "Shopping list"),
                    ),
                ),
            ),
            currentFilter = NoteFilter.All,
            currentSortOrder = NoteSortOrder.UpdatedDesc,
            navigator = LocalNotesNavigator.current,
            onCreateNote = { NoteId.fromString("preview-note-id") },
            actions = NotesActions.Empty,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotesScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesPreviewWrapper {
        NotesScreenContent(
            state = NotesUiState.Content(NotesListState()),
            currentFilter = NoteFilter.All,
            currentSortOrder = NoteSortOrder.UpdatedDesc,
            navigator = LocalNotesNavigator.current,
            onCreateNote = { NoteId.fromString("preview-note-id") },
            actions = NotesActions.Empty,
        )
    }
}
