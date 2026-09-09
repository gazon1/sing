package com.singularity.todo.feature.notes

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ContentStateMapper
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.notes.components.NoteCardContent
import com.singularity.todo.feature.notes.components.NotesActions
import com.singularity.todo.feature.tasks.UserId
import org.koin.compose.viewmodel.koinViewModel

// ─── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onNavigateToNote: (String) -> Unit,
    onNavigateToCreateNote: () -> Unit,
    viewModel: NotesListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val actions = NotesActions { action ->
        when (action) {
            is NotesActions.Action.NavigateToNote -> onNavigateToNote(action.id.value)
            is NotesActions.Action.CreateNote -> {
                val id = viewModel.createNoteWithTitle(action.title)
                onNavigateToNote(id)
            }
            is NotesActions.Action.Delete -> viewModel.delete(action.id)
            is NotesActions.Action.TogglePin -> viewModel.togglePin(action.id)
            is NotesActions.Action.SetFilter -> viewModel.setFilter(action.filter)
            is NotesActions.Action.SetSortOrder -> viewModel.setSortOrder(action.order)
            is NotesActions.Action.EnterSelection -> viewModel.enterSelectionMode(action.id)
            is NotesActions.Action.ToggleSelection -> viewModel.toggleSelection(action.id)
            is NotesActions.Action.ExitSelection -> viewModel.exitSelectionMode()
            is NotesActions.Action.DeleteSelected -> viewModel.deleteSelected()
        }
    }

    NotesScreenContent(
        state = state,
        currentFilter = viewModel.filter.collectAsStateWithLifecycle().value,
        currentSortOrder = viewModel.sortOrder.collectAsStateWithLifecycle().value,
        actions = actions,
    )
}

// ─── Content ────────────────────────────────────────────────────────────────

private fun NotesUiState.toContentState() =
    ContentStateMapper.notes(this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreenContent(
    state: NotesUiState,
    currentFilter: NoteFilter,
    currentSortOrder: NoteSortOrder,
    modifier: Modifier = Modifier,
    actions: NotesActions = NotesActions.Empty,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val content = state as? NotesUiState.Content
    val listState = content?.list
    val isSelectionMode = listState?.isSelectionMode == true
    val selectedCount = listState?.selectedIds?.size ?: 0

    Scaffold(
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
                            IconButton(onClick = {
                                actions.onDeleteSelected()
                            }) {
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
                    QuickAddRow(
                        onSubmit = { title -> actions.onCreateNote(title) },
                    )
                }
                HorizontalDivider()
            }
        },
    ) { padding ->
        if (state is NotesUiState.Empty) {
            EmptyState(
                title = "No notes yet",
                subtitle = "Create your first note to get started",
                modifier = Modifier.padding(padding),
                actions = {
                    FilledTonalButton(onClick = { actions.onCreateNote("") }) {
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
            ) { allNotes ->
                NoteList(
                    allNotes = allNotes,
                    pinned = listState?.pinned ?: emptyList(),
                    unpinned = listState?.unpinned ?: emptyList(),
                    isSelectionMode = isSelectionMode,
                    selectedIds = listState?.selectedIds ?: emptySet(),
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    currentFilter: NoteFilter,
    onFilterChange: (NoteFilter) -> Unit,
) {
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
                } else null,
            )
        }
    }
}

// ─── Quick-add row ───────────────────────────────────────────────────────────

@Composable
private fun QuickAddRow(
    onSubmit: (String) -> Unit,
) {
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
            modifier = Modifier.fillMaxWidth(),
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
    isSelectionMode: Boolean,
    selectedIds: Set<NoteId>,
    actions: NotesActions,
) {
    LazyColumn(
        modifier = Modifier.testTag(TestTags.NOTES_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                    onClick = {
                        if (isSelectionMode) actions.onToggleSelection(note.id)
                        else actions.onNavigateToNote(note.id)
                    },
                    onLongClick = { actions.onEnterSelection(note.id) },
                    onDelete = { actions.onDelete(note.id) },
                    onTogglePin = { actions.onTogglePin(note.id) },
                )
            }
        }

        // Unpinned section
        if (unpinned.isNotEmpty() && pinned.isNotEmpty()) {
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
                onClick = {
                    if (isSelectionMode) actions.onToggleSelection(note.id)
                    else actions.onNavigateToNote(note.id)
                },
                onLongClick = { actions.onEnterSelection(note.id) },
                onDelete = { actions.onDelete(note.id) },
                onTogglePin = { actions.onTogglePin(note.id) },
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onTogglePin(); false }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !isSelectionMode,
        enableDismissFromEndToStart = !isSelectionMode,
        backgroundContent = {
            SwipeBackground(dismissState.currentValue)
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        NoteCardContent(
            note = note,
            isSelected = isSelected,
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        )
    }
}

@Composable
private fun SwipeBackground(dismissValue: SwipeToDismissBoxValue) {
    val color by animateColorAsState(
        targetValue = when (dismissValue) {
            SwipeToDismissBoxValue.StartToEnd -> Color(0xFFFFB300)
            SwipeToDismissBoxValue.EndToStart -> Color(0xFFE53935)
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
        SwipeToDismissBoxValue.StartToEnd -> Icons.Default.PushPin
        SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
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
        actions = NotesActions.Empty,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotesScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesScreenContent(
        state = NotesUiState.Empty(userId = UserId.anonymous),
        currentFilter = NoteFilter.All,
        currentSortOrder = NoteSortOrder.UpdatedDesc,
        actions = NotesActions.Empty,
    )
}
