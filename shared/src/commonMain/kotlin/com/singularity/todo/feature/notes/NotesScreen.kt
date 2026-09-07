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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ContentStateMapper
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.UserId
import org.koin.compose.viewmodel.koinViewModel

// ─── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onNavigateToNote: (String) -> Unit,
    viewModel: NotesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NotesScreenContent(
        state = state,
        onNavigateToNote = onNavigateToNote,
        onDelete = viewModel::delete,
        onTogglePin = viewModel::togglePin,
        onSetFilter = viewModel::setFilter,
        onSetSortOrder = viewModel::setSortOrder,
        onEnterSelection = viewModel::enterSelectionMode,
        onToggleSelection = viewModel::toggleSelection,
        onExitSelection = viewModel::exitSelectionMode,
        onDeleteSelected = viewModel::deleteSelected,
        currentFilter = viewModel.filter.collectAsStateWithLifecycle().value,
        currentSortOrder = viewModel.sortOrder.collectAsStateWithLifecycle().value,
    )
}

// ─── Content ────────────────────────────────────────────────────────────────

private fun NotesUiState.toContentState() =
    ContentStateMapper.notes(this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreenContent(
    state: NotesUiState,
    onNavigateToNote: (String) -> Unit,
    onDelete: (NoteId) -> Unit,
    onTogglePin: (NoteId) -> Unit,
    onSetFilter: (NoteFilter) -> Unit,
    onSetSortOrder: (NoteSortOrder) -> Unit,
    onEnterSelection: (NoteId) -> Unit,
    onToggleSelection: (NoteId) -> Unit,
    onExitSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    currentFilter: NoteFilter,
    currentSortOrder: NoteSortOrder,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Notes") },
                    actions = {
                        // Sort menu
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                            SortDropdownMenu(
                                expanded = sortMenuExpanded,
                                currentOrder = currentSortOrder,
                                onSelect = {
                                    onSetSortOrder(it)
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
                // Filter chips row
                FilterChipRow(
                    currentFilter = currentFilter,
                    onFilterChange = onSetFilter,
                )
                HorizontalDivider()
            }
        },
    ) { padding ->
        StatefulContent(
            state = state.toContentState(),
            emptyTitle = "No notes yet",
            modifier = Modifier.padding(padding),
        ) { allNotes ->
            val content = state as? NotesUiState.Content
            val listState = content?.list
            NoteList(
                allNotes = allNotes,
                pinned = listState?.pinned ?: emptyList(),
                unpinned = listState?.unpinned ?: emptyList(),
                isSelectionMode = listState?.isSelectionMode == true,
                selectedIds = listState?.selectedIds ?: emptySet(),
                onNavigateToNote = onNavigateToNote,
                onDelete = onDelete,
                onTogglePin = onTogglePin,
                onEnterSelection = onEnterSelection,
                onToggleSelection = onToggleSelection,
            )
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
                    { Icon(Icons.Default.Sort, contentDescription = null) }
                } else null,
            )
        }
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
    onNavigateToNote: (String) -> Unit,
    onDelete: (NoteId) -> Unit,
    onTogglePin: (NoteId) -> Unit,
    onEnterSelection: (NoteId) -> Unit,
    onToggleSelection: (NoteId) -> Unit,
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
                        if (isSelectionMode) onToggleSelection(note.id)
                        else onNavigateToNote(note.id.value)
                    },
                    onLongClick = { onEnterSelection(note.id) },
                    onDelete = { onDelete(note.id) },
                    onTogglePin = { onTogglePin(note.id) },
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
                    if (isSelectionMode) onToggleSelection(note.id)
                    else onNavigateToNote(note.id.value)
                },
                onLongClick = { onEnterSelection(note.id) },
                onDelete = { onDelete(note.id) },
                onTogglePin = { onTogglePin(note.id) },
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
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        NoteCardContent(
            note = note,
            isSelected = isSelected,
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

// ─── Card Content ─────────────────────────────────────────────────────────────

@Composable
private fun NoteCardContent(
    note: Note,
    isSelected: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.noteItem(note.id.value)),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                note.isFolder -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                note.color != null -> Color(note.color.value).copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (note.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(start = 4.dp),
                    )
                }
            }
            note.bodyMarkdown?.let { body ->
                Text(
                    text = body.take(100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Meta row: word count + updated time
            if (note.wordCount > 0) {
                Text(
                    text = "${note.wordCount} words",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
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
        onNavigateToNote = {},
        onDelete = {},
        onTogglePin = {},
        onSetFilter = {},
        onSetSortOrder = {},
        onEnterSelection = {},
        onToggleSelection = {},
        onExitSelection = {},
        onDeleteSelected = {},
        currentFilter = NoteFilter.All,
        currentSortOrder = NoteSortOrder.UpdatedDesc,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotesScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesScreenContent(
        state = NotesUiState.Empty(userId = UserId.anonymous),
        onNavigateToNote = {},
        onDelete = {},
        onTogglePin = {},
        onSetFilter = {},
        onSetSortOrder = {},
        onEnterSelection = {},
        onToggleSelection = {},
        onExitSelection = {},
        onDeleteSelected = {},
        currentFilter = NoteFilter.All,
        currentSortOrder = NoteSortOrder.UpdatedDesc,
    )
}
