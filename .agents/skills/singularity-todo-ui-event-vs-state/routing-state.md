# Routing state — the third category

## Routing State — The Third Category

There are **three** kinds of UI-related data, not two:

| Category | Where it lives | Example |
|---|---|---|
| **UI state** | `StateFlow<UiState>` on VM | task list, filter, loading branch |
| **One-shot events** | `SharedFlow<UiEvent>` on VM | snackbar, dialog, navigate back |
| **Routing state** | `remember { mutableStateOf(...) }` on screen | `activeSheet: ActiveSheet?`, `menuExpanded: Boolean` |

Routing state is the **which sheet / dialog / menu is currently open**. It belongs on the screen, not in the VM, because:
- It is Composable-local (only the current screen cares).
- It has no persistence requirement (rotating the device closes the sheet).
- Routing decisions are cheap and local (no repository call needed).

**The anti-pattern: routing through the VM's SharedFlow.**

```kotlin
// ❌ WRONG — opening a date picker goes through the VM
// TaskDetailViewModel:
fun openDatePicker() = scope.launch { _events.emit(TaskDetailUiEvent.OpenDatePicker) }
// TaskDetailScreen:
CollectEvents(vm.events) { event ->
    if (event is TaskDetailUiEvent.OpenDatePicker) activeSheet = ActiveSheet.Date
}

// ✅ CORRECT — screen owns routing state directly
// TaskDetailActions helper:
fun onPickDate() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Date))
// TaskDetailScreen:
val actions = remember { TaskDetailActions { intent ->
    when (intent) {
        is TaskDetailIntent.OpenSheet -> activeSheet = intent.sheet
        // ...
    }
} }
```

`ConfirmDelete`/`ConfirmArchive` in the original `TaskDetailScreen` were already doing this correctly — the round-trip was unnecessary.

**Exception: routing that requires domain data.** If you need to ask the VM which project to pre-select in a picker, read that from `state.ui.project` (already available). You do not need to emit a `SharedFlow` event to ask.

**ProjectDetailIntent follows the same pattern.** Routing variants (`OpenColorSheet`, `OpenIconSheet`, `OpenDeleteSheet`, etc.) are `Routing` sealed-subinterface members. They are handled on the screen via `remember { ProjectDetailActions { ... } }` that sets `activeSheet = ActiveSheet.PickColor` directly. The VM never knows which sheet is open.

```kotlin
// ProjectDetailScreen — routing handled entirely on screen
val actions = remember {
    ProjectDetailActions { intent ->
        when (intent) {
            is ProjectDetailIntent.Routing.OpenColorSheet -> { sheetState = ActiveSheet.PickColor }
            is ProjectDetailIntent.Routing.OpenDeleteSheet -> { sheetState = ActiveSheet.ConfirmDelete }
            is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
        }
    }
}
```

## When to keep `MutableStateFlow` inside a Composable

Genuinely Composable-local UI affordances only:
- `var activeSheet by remember { mutableStateOf<ActiveSheet?>(null) }` — UI routing, not domain
- `var searchQuery by remember { mutableStateOf("") }` — local to a search field before submission
- `Animatable` for one-shot UI animations (saved pill fade, etc.)

What must **not** live in a Composable:
- Anything derived from a VM `StateFlow` (duplicated state)
- Domain formatting (`when (result)` that produces user-facing strings)
- Anything other Composables on the same screen also need

---
