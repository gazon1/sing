---
name: singularity-todo-ui-event-vs-state
description: How to model one-shot UI events (dialogs, navigation, snackbars) separately from continuous UI state in Singularity Todo ViewModels. Use when adding `MutableStateFlow` for a dialog flag inside a Composable, when `LaunchedEffect.collectLatest` is used to format a domain result into a string, when `remember { mutableStateOf<String?>(null) }` shadows VM-owned state, or when a screen has both a `state: StateFlow` and a `SharedFlow<String>` for AI results that should be merged. Documents the State vs Event dichotomy, the `UiEvent` sealed interface, and the `CollectEvents` helper.
---

# UI Event vs UI State — The Singularity Todo Pattern

Compose has a notorious pitfall: every `var foo by remember { mutableStateOf<X?>(null) }` inside a Composable is **second state** that mirrors what's already in the ViewModel. This pattern spreads logic across two layers, makes the screen harder to test, and forces every screen to re-invent the same `LaunchedEffect { vm.X.collectLatest { localState = ... } }` plumbing.

This project standardises on a strict split.

## The rule

| Concern | Lives in | Replay on rotation? | Read pattern |
|---|---|---|---|
| **UI state** (the list of tasks, the current filter, the editor state, loading branch) | `StateFlow<UiState>` on the VM | Yes — the new collector sees the latest value | `collectAsStateWithLifecycle()` |
| **One-shot events** (show "AI Result" dialog, navigate back, snackbar) | `SharedFlow<UiEvent>` on the VM | **No** — events fire once | `CollectEvents(vm.events) { … }` → `ResultDialog` / navigation lambda |

**Anti-pattern** (do not do):

```kotlin
// Composable owns a string flag that mirrors what the VM already knows
@Composable
fun TasksScreen(...) {
    var aiResultText by remember { mutableStateOf<String?>(null) }
    val vm: TasksViewModel = koinInject()

    LaunchedEffect(Unit) {
        vm.aiResult.collectLatest { result ->
            aiResultText = when (result) {
                is AiActionResult.RefineTitle -> "Refined title: ${result.newTitle}"
                is AiActionResult.Error -> "Error: ${result.message}"
                // … 6 more branches of formatting
            }
        }
    }

    if (aiResultText != null) {
        AlertDialog(
            onDismissRequest = { aiResultText = null },
            title = { Text("AI Result") },
            text = { Text(aiResultText!!) },
            confirmButton = { TextButton(onClick = { aiResultText = null }) { Text("OK") } },
        )
    }
}
```

**Correct pattern**:

```kotlin
// ViewModel owns a single source of truth
class TasksViewModel(...) : ViewModel() {
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun refineTask(task: Task) = viewModelScope.launch {
        useCase(task).fold(
            onSuccess = { _events.emit(UiEvent.ShowDialog("AI Result", formatAiResult(it))) },
            onFailure = { _events.emit(UiEvent.ShowError(it.message ?: "Failed")) },
        )
    }
}

// Composable is a thin subscriber
@Composable
fun TasksScreen(...) {
    val vm: TasksViewModel = koinInject()
    var dialogText by remember { mutableStateOf<String?>(null) }

    CollectEvents(vm.events) { event ->
        when (event) {
            is UiEvent.ShowDialog -> dialogText = event.text
            is UiEvent.ShowError -> dialogText = event.message
            UiEvent.NavigateBack -> onBack()
        }
    }

    ResultDialog(title = "AI Result", text = dialogText, onDismiss = { dialogText = null })
}
```

Why this matters:

1. **The VM stays the source of truth.** No way for the Composable to drift out of sync — if a second observer shows the same event, both fire identically.
2. **Formatting is testable without Compose.** `formatAiResult(...)` is a pure function that lives in `TasksFormatters.kt` and gets `TasksFormattersTest` — no Robolectric.
3. **The screen is 4 lines instead of 20** — no `LaunchedEffect`, no `AlertDialog` ceremony.
4. **The VM is testable without Compose** — you can `vm.events.test() { … }` from a `runTest` block.

## `UiEvent` variants

```kotlin
sealed interface UiEvent {
    data class ShowDialog(val title: String, val text: String) : UiEvent
    data class ShowError(val message: String) : UiEvent
    data object NavigateBack : UiEvent
}
```

Extend with new variants when a new pattern emerges (e.g. `ShowSnackbar`, `OpenUrl`). Don't overload `ShowDialog` with a new boolean flag — that means it's a different event.

## The `MutableSharedFlow` setup

Always:

```kotlin
private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
val events: SharedFlow<UiEvent> = _events.asSharedFlow()
```

- `extraBufferCapacity = 4` — without it, `emit` from a finished coroutine silently drops. With it, the event queue survives quick screen rotations.
- Expose as `SharedFlow` (read-only), not `MutableSharedFlow`.
- Use `replay = 0` (default) — events are one-shot; replaying them on every recomposition would re-show the dialog.

## When to keep a `MutableStateFlow` inside a Composable

Genuinely Composable-local UI affordances:

- `var dialogText by remember { mutableStateOf<String?>(null) }` — captures the **latest** event for rendering. The state is gone on rotation; that's fine because the event is also gone.
- `var selectedTab by remember { mutableStateOf(SettingsTab.Appearance) }` — local navigation inside one screen; not a domain concern.
- `var sheetState = remember { mutableStateOf<Task?>(null) }` — which task is currently shown in the bottom sheet. Not domain state, just UI routing.

What must **not** live in a Composable:

- Anything derived from a VM `StateFlow` (you'd be duplicating the VM's data)
- Anything formatted from a VM result (use a formatter, not inline `when`)
- Anything that other Composables on the same screen would also need (extract it to a sibling Composable or hoist to the VM)

## Migrating a screen to the event pattern

When you see a screen with one of the anti-patterns:

1. Add `events: SharedFlow<UiEvent>` to the VM.
2. Move the formatting logic into a pure formatter (`FeatureFormatters.kt`) and test it.
3. Replace inline `when` branches with `emit(UiEvent.ShowDialog(title, formatter(result)))`.
4. In the screen, add `var dialogText by remember { mutableStateOf<String?>(null) }`, call `CollectEvents`, and render `ResultDialog`.
5. Delete the `LaunchedEffect.collectLatest` block.
6. Delete the inline `AlertDialog` and the import.

The screen is now testable as a thin view, and the formatter is testable as pure Kotlin.

## Test recipe for VM events

```kotlin
@Test
fun `refine emits ShowDialog on success`() = runTest {
    val vm = TasksViewModel(FakeTaskRepo(), scriptedUseCase(success = "Improved title"))

    vm.refineTask(sampleTask)

    val event = vm.events.first()
    assertIs<UiEvent.ShowDialog>(event)
    assertEquals("Improved title", event.text)
}
```

Use `MutableSharedFlow.test()` or `events.first()` with `runTest` + `UnconfinedTestDispatcher`. No Compose runtime needed.
