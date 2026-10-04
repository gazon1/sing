---
name: singularity-todo-task-detail-ux
description: 'Document-style vs form-style UX pattern for task detail screens. Covers the hero block (checkbox + title + description), meta-chips row (date/time/priority/project), inline-edit tap-to-edit, bottom action bar, and the TickTick/Todoist reference. Documents 10 known regressions: AlertDialog-to-ModalBottomSheet migration trap, dead FieldMode state, emoji-icon usage, empty-section noise, Saved-spam from debounced inline edits, TOCTOU race in debounced collectors, ReminderPicker always resetting to 15 min, missing Clear on date/time pickers, missing inline create in project/tag pickers, and using AlertDialog for confirm-delete instead of ModalBottomSheet with Snackbar undo.'
---

# Task Detail UX — Document-Style vs Form-Style

There are two distinct UX patterns for a task detail screen. Most to-do apps start with **form-style** (a field per row, explicit Edit/Save buttons) and eventually migrate to **document-style** (tap-to-edit inline, grouped metadata, bottom action bar). TickTick, Todoist, and Things 3 all use document-style. This skill documents the target pattern and the regressions to avoid.

## The two patterns

### Form-style (the anti-pattern in this project)

```
┌──────────────────────────────────────┐
│  Title                          [Edit] │  ← one field per row
│  Description                    [Edit] │    explicit Edit button
│  Due date                      [Edit] │    separate text input
│  Priority                       [Edit] │
│  Reminders (0)                      ... │
└──────────────────────────────────────┘
```

**Problems:**
- Every field is a separate row with an `Edit` button — visual noise (5+ buttons on screen).
- Date/time entered as free-form text — no DatePicker, no validation.
- Priority as text (`"High"`) — no colour, no icon.
- Empty sections (`Reminders (0)`, `Attachments (0)`) still occupy vertical space with `"(none)"`.
- Emoji icons (`📌`, `🗑`) instead of Material Icons.
- Completion checkbox missing from the most prominent position.

### Document-style (the target — TickTick/Todoist reference)

```
┌──────────────────────────────────────┐
│  ←                                  │  ← BackTopAppBar (no title in bar)
├──────────────────────────────────────┤
│  ☐  Complete project proposal        │  ← Hero: checkbox + inline-edit title
│      Add description...              │    description below, placeholder
├──────────────────────────────────────┤
│  [📅 Today, 9:00 AM] [🚩 High]      │  ← Meta chips row (FlowRow)
│  [📁 Work]                           │    tap chip → bottom sheet picker
├──────────────────────────────────────┤
│  [urgent ×] [client ×] [+ Add tag]  │  ← Tags row
├──────────────────────────────────────┤
│  Checklist ────── 2/3 ▓▓▓░░          │
│  ☑ Research phase            [🗑]     │  ← Progress bar + checklist items
│  ☐ Final review              [🗑]     │
│  [Add item______________] [+]        │
├──────────────────────────────────────┤
│  [🔔 1] [📎 0] [📌] [🗑]            │  ← Bottom action bar (badge counts)
└──────────────────────────────────────┘
```

**Principles:**
1. **Hero block at top** — checkbox + title (largest text on screen) + description as placeholder. No label, no Edit button.
2. **Tap-to-edit, not button-to-edit** — tap the title text → becomes an input field. Save on focus-loss or Enter.
3. **Metadata as chips, not rows** — date+time, priority, project are `FilterChip`/`SuggestionChip` in a `FlowRow`. Tap any chip → bottom sheet picker.
4. **Empty sections are invisible** — Reminders/Attachments show a count badge on the bottom bar icon. The section is gone from the body when empty.
5. **Bottom action bar** — 4 icon buttons (Remind, Attach, Pin, Delete). Badge on icon shows count > 0. No inline text.
6. **Material Icons throughout** — `Icons.Filled.PushPin`, `Icons.Filled.DeleteOutline`, `Icons.Filled.CalendarMonth`. No emoji.

## Component breakdown

### Hero block

```
Checkbox (large, left) + Column {
    BasicTextField (titleLarge, LineThrough if completed)
    BasicTextField (bodyMedium, placeholder, onSurfaceVariant)
}
```

- No `OutlinedTextField` wrapper — plain `BasicTextField` with no visual border.
- Placeholder: `"Add description..."` in `onSurfaceVariant` colour.
- Save is implicit: focus lost or IME action Done → `viewModel.onTitleChange(draft)`.
- Completion toggle via the checkbox — the **only** way to mark complete on this screen.

### Meta chips row (`FlowRow`)

Three chip types:

| Chip | Icon | Colour logic | Tap action |
|---|---|---|---|
| DateTime | `CalendarMonth` | `errorContainer` if overdue; `secondaryContainer` if today; `surfaceVariant` if future | Opens `DatePickerSheet` + `TimePickerSheet` |
| Priority | `Flag` | `priorityColorByIndex(priority.ordinal)` tint | Opens `TaskEditorPrioritySheet` |
| Project | `Folder` | Neutral | Opens `ProjectPickerSheet` |

**Three due-date visual states (TickTick-level UX):**
- `Overdue` (date < today AND not completed) → `errorContainer` background, `onErrorContainer` text
- `Today` (date == today) → `secondaryContainer`, `onSecondaryContainer` (warning yellow)
- `Future` (date > today) → `surfaceVariant` (neutral grey)

### Tags row

`FlowRow` of `AssistChip` with `trailingIcon = Icons.Filled.Close`. Tap × → `viewModel.removeTag(tagId)`. "+ Add tag" chip → `TagPickerSheet` (multi-select).

### Checklist section

- `LinearProgressIndicator(progress = { done / total })` — **the lambda form is required** in Material3 1.4+ (`value=` is deprecated).
- Each item: `ChecklistItemRow` (already exists at `feature/checklist/components/ChecklistItemRow.kt:41`). Its KDoc explicitly states it replaces the inline checkbox rows from `TaskDetailScreen`.
- Inline add: `OutlinedTextField` + `IconButton(Icons.Filled.Add)`. Enter key = save.

### Bottom action bar

`BottomAppBar` with 4 equally-spaced `IconButton`s:
- `Notifications` — badge `reminders.size` if > 0
- `AttachFile` — badge `attachments.size` if > 0
- `PushPin` — tinted `primary` when `isPinned`, else `onSurfaceVariant`
- `DeleteOutline` — always `error` tint, tap → `AlertDialog` confirmation

## 10 known regressions (anti-patterns to avoid)

### Regression 1: AlertDialog → ModalBottomSheet migration trap

`ProjectPickerSheet` and `TagPickerSheet` currently use `AlertDialog`. A reasonable instinct is to migrate them to `ModalBottomSheet` for visual consistency. **Do not do this in the same PR as the task-detail rewrite.** These sheets are called from multiple callers (`TaskEditorScreen`, possibly others). Migrating them changes their behaviour (overlay vs dialog) and breaks callers that expect dialog semantics. Make it a separate PR with its own testing.

### Regression 2: Dead `FieldMode` state

`TaskDetailUi` has fields `titleField: FieldMode`, `descriptionField: FieldMode`, etc. These are declared but **never mutated by the ViewModel** — `saveField` does not flip them back to `View`. The `EditableTextRow` composable is permanently stuck in `View` mode. Fix: delete these fields entirely and replace `EditableTextRow` with inline-edit composables that call VM methods directly.

### Regression 3: Emoji icons

`📌 Pinned` and `🗑 Archived` are rendered as inline emoji text. These should be Material Icons rendered in the bottom action bar (Pin/Delete buttons) or hidden when the state is inactive. Emoji in production UI is a polish regression.

### Regression 4: Empty section noise

Sections like `Reminders (0)` and `Attachments (0)` are always rendered with an `"(none)"` placeholder, occupying 2–3 lines of vertical space for a state that conveys zero information. Fix: remove these sections from the body entirely when count == 0. Show the count via badge on the bottom action bar icon instead.

### Regression 5: Saved-spam from debounced inline edits

**Root cause:** `TaskDetailViewModel.kt` has a debounced collector at line ~100 that emits `Saved("Title updated")` on every debounced keystroke — not just on explicit save actions. This produces a "Saved" pulse on every character typed, which is annoying UX and floods the event channel.

**The correct pattern (per `singularity-todo-inline-edit-saved-feedback` skill):**
- Inline edits use a **silent debounce**: `_lastEditedAt: MutableStateFlow<Instant?>` — written on every debounced keystroke, never exposed as a `Saved` event.
- Only **explicit actions** (checkbox toggle, date pick confirmed, sheet dismissed with explicit save) emit `SavedUiEvent` / `SavedPulse`.
- The top bar shows `formatSavedRelative(now, lastEditedAt)` as a **continuous** `Text` composable derived from `_lastEditedAt`, NOT as a one-shot pulse animation on every keystroke.
- The `SavedUiEvent` / `SavedPulse` event is reserved for cases where the user explicitly expects confirmation: completing a task, applying an AI suggestion, bulk operations.

**Do not replicate this bug in any new screen.** When adding document-style inline edit to `ProjectDetailScreen` or any other screen, follow `singularity-todo-inline-edit-saved-feedback` skill exactly.

### Regression 6: TOCTOU race in debounced collectors

**Root cause:** `TaskDetailViewModel` used `taskRepo.watchTask(taskId).filterNotNull().first()` inside a debounced collector (lines ~106, ~118). Between the debounce delay (300 ms) and the `.first()` call, a concurrent remote edit could update the task. Calling `.first()` after the debounce fetches a fresh copy, discards any changes made during the debounce window, and overwrites them with the stale draft.

**The correct pattern:** Cache the latest task value in a `MutableStateFlow<Task?>(_latestTask)` that is updated synchronously inside the `combine` block that assembles the UI state. The debounced collector reads from `_latestTask.value` instead of calling `.first()`:

```kotlin
// ✅ CORRECT — use cached latest, updated on every state emission
private val _latestTask = MutableStateFlow<Task?>(null)

val state: StateFlow<UiState> = _taskId.flatMapLatest { id ->
    combine(taskFlow, ...) { task, ... ->
        _latestTask.value = task  // update cache synchronously
        UiState(...)
    }
}

// Debounced collector reads from cache, not a new fetch
scope.launch {
    titleDraft.debounce(300).filterNotNull().collect { title ->
        val current = _latestTask.value ?: return@collect  // TOCTOU-safe
        updateTask(current.copy(title = title))            // no Saved event
    }
}
```

**Also affects:** Any other detail ViewModel that uses `repo.watchX(id).first()` inside a debounced collector. Fix by adding a `_latestEntity` cache and updating it in the `combine` block.

### Regression 7: ReminderPicker always resets to 15 min before

**Root cause:** `ReminderPickerSheetContent` always initialised `selectedOffset` to `ReminderOffset.FIFTEEN_MIN` regardless of the task's existing reminders. The sheet was re-composed on every keystroke in the parent, resetting the selection.

**The fix (PR 3):** The initial offset is derived from `reminders.firstOrNull()?.offsetMinutes` via `ReminderOffset.entries.find { it.minutes == reminder.offsetMinutes } ?: ReminderOffset.FIFTEEN_MIN`. The `remember { mutableStateOf(initialOffset) }` pattern ensures the selection survives sheet re-compositions.

### Regression 8: No Clear action on date/time pickers

**Root cause:** `DatePickerSheet` and `TimePickerSheet` had no way to clear a previously-set date or time — the only options were "Cancel" (dismiss) or "OK" (confirm). Users who wanted to remove a due date had no UI affordance to do so.

**The fix (PR 3):** Both pickers are now `ModalBottomSheet` (not `DatePickerDialog`/`AlertDialog`) with three buttons: "Clear" (left), "Cancel" (centre-right), "OK" (right-most). "Clear" calls `onDateSelected(null)` / `onTimeSelected(null)` — a silent update, no `Saved` event.

### Regression 9: No inline create in project/tag pickers

**Root cause:** `ProjectPickerSheet` and `TagPickerSheet` only listed existing items. Creating a new project or tag required a full round-trip to a separate screen. This breaks the flow — the user had to abandon the picker, create the item, then re-open the picker to select it.

**The fix (PR 3):** Both pickers now show a "+ Create new project" / "+ Create new tag" `TextButton` at the bottom of the list. Tapping it reveals an inline `OutlinedTextField` + "Create" button. On "Create":
- `ProjectPickerSheet`: creates a `Project` with `ProjectId.generate()`, default green colour (`0xFF4CAF50`), calls `projectsRepo.create()`.
- `TagPickerSheet`: supports comma-separated multi-word input (e.g. `"urgent, client"`), creates one tag per word, auto-selects newly created tags.
- Both refresh the list and keep the new item visible.

### Regression 10: AlertDialog for confirm-delete instead of ModalBottomSheet + Snackbar undo

**Root cause:** `ConfirmDeleteDialog` and `ConfirmArchiveDialog` were implemented as `AlertDialog` — a Material3 dialog component — rather than `ModalBottomSheet`. AlertDialog is appropriate for truly destructive one-shot confirms, but delete-with-undo is better served by a `ModalBottomSheet` with a SnackbarHost showing an "Undo" action for 4 seconds.

**The fix (PR 4):** `ConfirmDeleteSheet` and `ConfirmArchiveSheet` are now `ModalBottomSheet`. The `deleteTask` VM method emits `TaskDetailUiEvent.UndoDelete(taskId)` instead of navigating back immediately. The screen shows a `Snackbar` with an "Undo" action. If the user taps "Undo", `viewModel.restoreTask()` is called (backed by `TaskRepository.restore`). Archive is soft-delete only — it has no undo snackbar since archived tasks are recoverable from the Archive screen.

### Regression 11: Deprecated sheet API — `rememberModalBottomSheetState` → `rememberBottomSheetState`

**Problem:** In Material3 1.12.x, `rememberModalBottomSheetState(...)` is deprecated in favor of `rememberBottomSheetState(initialValue = SheetValue.Hidden, ...)`.

**The fix (PR B, Phase 6):** Mechanical replacement across all sheets.

**Critical gotcha:** `rememberModalBottomSheetState(skipPartiallyExpanded = true)` auto-expanded on mount. `rememberBottomSheetState(initialValue = Hidden)` does NOT — you must call `LaunchedEffect(Unit) { sheetState.show() }` after declaration or the sheet will not open:

```kotlin
// ❌ WRONG — sheet will not open
val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
ModalBottomSheet(sheetState = sheetState) { ... }

// ✅ CORRECT — explicit expand on mount
val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
LaunchedEffect(Unit) { sheetState.show() }
ModalBottomSheet(sheetState = sheetState) { ... }
```

**Also:** `skipPartiallyExpanded` is NOT a parameter of `rememberBottomSheetState` — it belongs to `ModalBottomSheet`, not to the state constructor.

### Regression 12: Roborazzi plugin resolution failure

**Problem:** The original ADR referenced `io.github.nickid:roborazzi` which does not exist on Maven Central. The correct group is `io.github.takahirom.roborazzi`.

**The fix (PR D, Phase 6):** Updated `libs.versions.toml` to use `io.github.takahirom.roborazzi:roborazzi:1.74.0`. See `singularity-todo-task-snapshot-testing` skill for the correct Maven coordinates and API usage.

### Regression 13: TOCTOU in detail ViewModels — reading state snapshot instead of write-through cache

**Root cause:** `ProjectDetailViewModel.toggleArchive` (and similar mutation methods) read `state.value` to determine current `isDeleted`:

```kotlin
// ❌ WRONG — state.value may be stale if debounce collector is mid-flight
fun toggleArchive() = viewModelScope.launch {
    val current = (state.value as? ProjectDetailUiState.Content)?.ui?.project ?: return@launch
    updateProject(projectId) { it.copy(isDeleted = !current.isDeleted) }
}
```

If the debounce collector for `updateName` fired during the 300ms debounce window, the Room entity was updated but `state` hasn't emitted the new combined value yet. `current.isDeleted` is stale, and `toggleArchive` flips to the wrong value.

**The fix:** `_latestProject` write-through cache (mirrors `_latestTask` from `TaskDetailViewModel`):

```kotlin
// ✅ CORRECT — read from the write-through cache, not state snapshot
private val _latestProject = MutableStateFlow<Project?>(null)

init {
    viewModelScope.launch {
        projectRepo.watchProject(projectId).collect { _latestProject.value = it }
    }
}

private fun handleToggleArchive() = viewModelScope.launch {
    val current = _latestProject.value ?: return@launch
    updateProject(projectId) { it.copy(isDeleted = !current.isDeleted) }
    _events.emit(ProjectDetailUiEvent.Saved)
}
```

**The invariant:** In any detail VM that combines multiple flows, **all mutation methods must read from the `_latest<Entity>` StateFlow**, not from `state.value`. The `_latest<Entity>` is updated synchronously before any downstream `combine`, so it is never stale.

**Rule:** If you find yourself writing `state.value as? UiState.Content)?.ui?.entity` inside a `viewModelScope.launch`, introduce `_latest<Entity>` as a write-through cache. See ADR `2026-09-09-projectdetail-write-through-fix.md`.

## Reference apps

- **TickTick** (Android/iOS) — primary reference for document-style detail screen. Priority chip with coloured flag, combined date+time chip, checklist with progress bar, bottom action bar.
- **Todoist** — similar pattern. Quick-add for subtasks at bottom of checklist.
- **Things 3** (iOS) — the gold standard for metadata chips and inline editing.

## Relationship to shared-ui-components skill

`singularity-todo-shared-ui-components` documents `SettingsSection`, `ResultDialog`, and screen decomposition patterns. This skill is the **application of those patterns** to the task-detail screen specifically, plus the document-style conventions that apply to any read-only detail screen (notes, projects, etc.).
