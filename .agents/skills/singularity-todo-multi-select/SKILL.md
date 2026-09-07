---
name: singularity-todo-multi-select
description: Long-press multi-selection pattern for Compose Multiplatform list screens. Use when implementing selection mode on NotesScreen or TasksScreen — long-press enters mode, checkboxes appear, bottom action bar shows bulk operations (pin/delete/tag/move). Covers VM state (selectedIds Set<Id>, isSelectionMode Boolean), bottom action bar with badge counts, exit on back press, and checkbox overlay on cards.
---

# Multi-Select Pattern — Long-Press Selection Mode

## When to Use This Skill

Use when implementing **multi-select** on `NotesScreen`, `TasksScreen`, or any list screen where users need to perform bulk operations (pin, delete, tag, move) on multiple items simultaneously.

**Reference patterns:**
- Apple Notes: long-press → "Select" mode → bottom toolbar
- TickTick: long-press → contextual action bar replaces FAB
- iOS Mail: swipe hints + "Edit" button

## UX Flow

```
Normal mode                          Selection mode
─────────────────                    ─────────────────────────────────
[Note card]  tap → open             [☑ Note card]  tap → toggle select
[Note card]  long-press → enter    [☑ Note card]  tap → toggle select
[Note card]  ...                   [☐ Note card]  tap → toggle select
                                     ─────────────────────────────────
FAB visible                           Bottom bar: [Pin] [Tag] [Delete]
```

## VM State (per-feature)

```kotlin
// NotesViewModel.kt

data class NotesUiState(
    val notes: List<Note> = emptyList(),
    val selectedIds: Set<NoteId> = emptySet(),
    val isSelectionMode: Boolean = false,
    val sortOrder: NoteSortOrder = NoteSortOrder.UpdatedDesc,
    val filter: NoteFilter = NoteFilter.All,
    // ...
) {
    val selectedCount: Int get() = selectedIds.size
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
}

// New intents:
sealed interface NotesIntent {
    // ...
    data class EnterSelection(val id: NoteId) : NotesIntent  // long-press
    data class ToggleSelection(val id: NoteId) : NotesIntent  // tap in selection mode
    data object ExitSelection : NotesIntent                     // back press or "X"
    data class BulkDelete(val ids: Set<NoteId>) : NotesIntent
    data class BulkPin(val ids: Set<NoteId>, val pinned: Boolean) : NotesIntent
}

class NotesViewModel(...) : ViewModel() {
    fun processIntent(intent: NotesIntent) = viewModelScope.launch {
        when (intent) {
            is NotesIntent.EnterSelection -> {
                _state.update { it.copy(
                    isSelectionMode = true,
                    selectedIds = setOf(intent.id),
                ) }
            }
            is NotesIntent.ToggleSelection -> {
                _state.update { state ->
                    val newIds = if (intent.id in state.selectedIds) {
                        state.selectedIds - intent.id
                    } else {
                        state.selectedIds + intent.id
                    }
                    state.copy(
                        selectedIds = newIds,
                        isSelectionMode = newIds.isNotEmpty(),
                    )
                }
            }
            is NotesIntent.ExitSelection -> {
                _state.update { it.copy(isSelectionMode = false, selectedIds = emptySet()) }
            }
            is NotesIntent.BulkDelete -> {
                intent.ids.forEach { repo.softDelete(it) }
                _state.update { it.copy(selectedIds = emptySet(), isSelectionMode = false) }
            }
            is NotesIntent.BulkPin -> {
                intent.ids.forEach { repo.setPinned(it, intent.pinned) }
                _state.update { it.copy(selectedIds = emptySet(), isSelectionMode = false) }
            }
        }
    }
}
```

## Screen Integration

```kotlin
@Composable
fun NotesScreen(
    onNavigateToNote: (String) -> Unit,
    viewModel: NotesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            if (state.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedIds.size,
                    onExitSelection = viewModel::exitSelection,
                )
            } else {
                TopAppBar(title = { Text("Notes") })
            }
        },
        bottomBar = {
            if (state.isSelectionMode) {
                SelectionBottomBar(
                    selectedCount = state.selectedIds.size,
                    onPin = { viewModel.processIntent(NotesIntent.BulkPin(state.selectedIds, pinned = true)) },
                    onUnpin = { viewModel.processIntent(NotesIntent.BulkPin(state.selectedIds, pinned = false)) },
                    onDelete = { viewModel.processIntent(NotesIntent.BulkDelete(state.selectedIds)) },
                    onTag = { /* open tag picker */ },
                )
            } else {
                // Normal FAB handled at shell level
            }
        },
    ) { padding ->
        NotesContent(
            state = state,
            onNoteClick = { note ->
                if (state.isSelectionMode) {
                    viewModel.processIntent(NotesIntent.ToggleSelection(note.id))
                } else {
                    onNavigateToNote(note.id.value)
                }
            },
            onNoteLongClick = { note ->
                if (!state.isSelectionMode) {
                    viewModel.processIntent(NotesIntent.EnterSelection(note.id))
                }
            },
            // ...
        )
    }
}
```

## SelectionTopBar

```kotlin
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onExitSelection: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.AutoMirrored.Filled.Close, "Exit selection")
            }
        },
        title = {
            Text("$selectedCount selected")
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    )
}
```

## SelectionBottomBar (Material3 BottomAppBar)

```kotlin
@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    onTag: () -> Unit,
) {
    BottomAppBar(
        actions = {
            IconButton(onClick = onPin) {
                Icon(Icons.Filled.PushPin, "Pin")
            }
            IconButton(onClick = onTag) {
                BadgeBox(
                    badgeContent = { /* optional count */ }
                ) {
                    Icon(Icons.Filled.Label, "Tag")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    )
}
```

## NoteCard with Selection Checkbox

```kotlin
@Composable
fun NoteCard(
    note: Note,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            // Checkbox (visible in selection mode, hidden otherwise)
            if (isSelected) {
                Checkbox(
                    checked = true,
                    onCheckedChange = null,  // handled by onClick
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else if (/* show checkbox hint in selection mode even for unselected */ false) {
                Checkbox(
                    checked = false,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            // Card content (title, preview, etc.)
            Column(modifier = Modifier.weight(1f)) {
                Text(note.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                note.bodyMarkdown?.let { body ->
                    Text(body.take(100), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            // Selection indicator (checkmark circle)
            if (isSelected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
```

## Handling Back Press (Exit Selection Mode)

Handle in the screen's `BackHandler` or navigation:

```kotlin
BackHandler(enabled = state.isSelectionMode) {
    viewModel.processIntent(NotesIntent.ExitSelection)
}
```

Or via `collectEvents` from the ViewModel (if exit is also triggered by UI):
```kotlin
// In NotesUiEvent:
data object ExitSelection : NotesUiEvent
```

## Anti-patterns

- **Do NOT use `selectionMode` as a separate enum** — use `isSelectionMode: Boolean` + `selectedIds: Set<Id>`. Simpler, easier to test.
- **Do NOT trigger selection mode on single tap** — only long-press. Single tap navigates.
- **Do NOT auto-exit selection mode when `selectedIds` becomes empty** — show an empty selection state briefly before auto-exit, or require explicit "X" tap.
- **Do NOT perform destructive bulk operations without confirmation** — use `AlertDialog` before `BulkDelete`.
- **Do NOT show the FAB during selection mode** — it creates confusion about the primary action. Use `bottomBar` instead.

## Testing

```kotlin
@Test
fun `long press enters selection mode`() = runTest {
    val vm = createVm()
    vm.processIntent(NotesIntent.EnterSelection(note1.id))
    assertTrue(vm.state.value.isSelectionMode)
    assertEquals(setOf(note1.id), vm.state.value.selectedIds)
}

@Test
fun `tap in selection mode toggles selection`() = runTest {
    val vm = createVm()
    vm.processIntent(NotesIntent.EnterSelection(note1.id))
    vm.processIntent(NotesIntent.ToggleSelection(note2.id))
    assertEquals(setOf(note1.id, note2.id), vm.state.value.selectedIds)
    vm.processIntent(NotesIntent.ToggleSelection(note1.id))
    assertEquals(setOf(note2.id), vm.state.value.selectedIds)
}

@Test
fun `exit selection clears selectedIds`() = runTest {
    val vm = createVm()
    vm.processIntent(NotesIntent.EnterSelection(note1.id))
    vm.processIntent(NotesIntent.ExitSelection)
    assertFalse(vm.state.value.isSelectionMode)
    assertEquals(emptySet(), vm.state.value.selectedIds)
}
```
