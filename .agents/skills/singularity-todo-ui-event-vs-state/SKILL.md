---
name: singularity-todo-ui-event-vs-state
description: How to model one-shot UI events (dialogs, navigation, snackbars) separately from continuous UI state in Singularity Todo ViewModels. Use when adding a MutableStateFlow for a dialog flag inside a Composable, when LaunchedEffect.collectLatest is used to format a domain result into a string, when remember mutableStateOf shadows VM-owned state, or when a screen has both a state StateFlow and a SharedFlow for AI results that should be merged. Documents the State vs Event dichotomy, the per-feature UiEvent sealed interface, Pulse events, and the CollectEvents helper.
---

# UI Event vs UI State — The Singularity Todo Pattern

Compose has a notorious pitfall: every `var foo by remember { mutableStateOf<X?>(null) }` inside a Composable is **second state** that mirrors what's already in the ViewModel. This pattern spreads logic across two layers, makes the screen harder to test, and forces every screen to re-invent the same `LaunchedEffect { vm.X.collectLatest { localState = ... } }` plumbing.

This project standardises on a strict split.

## The rule

| Concern | Lives in | Replay on rotation? | Read pattern |
|---|---|---|---|
| **UI state** (the list of tasks, the current filter, the editor state, loading branch) | `StateFlow<UiState>` on the VM | Yes — the new collector sees the latest value | `collectAsStateWithLifecycle()` |
| **One-shot events** (show "AI Result" dialog, navigate back, snackbar, saved pulse) | `SharedFlow<UiEvent>` on the VM | **No** — events fire once | `CollectEvents(vm.events) { … }` → `ResultDialog` / navigation lambda |

## ⚠️ Per-feature UiEvent (not global)

Every feature has its **own** `sealed interface XxxUiEvent` — not a shared global `UiEvent`. This prevents naming conflicts when multiple screens emit `NavigateBack` or `ShowError`.

```kotlin
// ✅ CORRECT — per-feature sealed interface
sealed interface NotesUiEvent {
    data object NavigateBack : NotesUiEvent
    data class SaveFailed(val message: String) : NotesUiEvent
    data class AiResult(val text: String) : NotesUiEvent
    data object SavedPulse : NotesUiEvent  // Phase 1: one-shot saved indicator
}

sealed interface TasksUiEvent {
    data object NavigateBack : TasksUiEvent
    data class ShowError(val message: String) : TasksUiEvent
    // ... feature-specific events
}
```

```kotlin
// ❌ WRONG — global UiEvent (deprecated, causes naming conflicts)
sealed interface UiEvent {
    data class ShowDialog(val title: String, val text: String) : UiEvent
    data object NavigateBack : UiEvent
    // ...
}
```

**Migration:** All screens have been migrated to per-feature events (ADR `2026-09-05-ui-event-per-feature`). Do not reintroduce a global `UiEvent`.

## Pulse Events (one-shot signals without payload)

Some events carry no data — they are just a signal to trigger a UI animation or reaction. Use `MutableSharedFlow` with `extraBufferCapacity = 1` and `replay = 0`:

```kotlin
// NotesViewModel — Saved pulse (Phase 1)
private val _savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val savedPulse: SharedFlow<Unit> = _savedPulse.asSharedFlow()

private fun scheduleAutosave(id: String) {
    autosaveJob?.cancel()
    autosaveJob = scope.launch(Dispatchers.Unconfined) {
        autosaveScheduler.awaitTick()
        val current = _editorState.value as? EditorState.Editing ?: return@launch
        try {
            repo.updateContent(...).getOrThrow()
            _editorState.value = current.copy(isDirty = false)
            _savedPulse.emit(Unit)  // ← triggers "Saved" animation in UI
        } catch (e: Exception) {
            _events.emit(NotesUiEvent.SaveFailed(e.message ?: "Save failed"))
        }
    }
}
```

**Screen side — dedup with Animatable:**
```kotlin
@Composable
fun NoteEditorScreen(...) {
    val savedAlpha = remember { Animatable(0f) }

    LaunchedEffect(state.id) {
        vm.savedPulse.collect {
            // Animate: fade in → hold → fade out
            savedAlpha.snapTo(1f)
            delay(800)
            savedAlpha.animateTo(0f)
        }
    }

    // Render pill with animated alpha:
    if (savedAlpha.value > 0.01f) {
        Text(
            "Saved",
            modifier = Modifier.graphicsLayer { alpha = savedAlpha.value },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
```

**Why not `mutableStateOf<String?>` for the pill?** Because that would duplicate the VM's knowledge of when a save happened. The screen should only react to the VM's event, not maintain its own mirror state.

## The `CollectEvents` helper

Always use `CollectEvents(flow) { … }` instead of `LaunchedEffect(Unit) { vm.events.collectLatest { … } }`:

```kotlin
@Composable
fun NoteEditorScreen(
    editorState: EditorState,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
    onSaveNow: () -> Unit,
    onBack: () -> Unit,
    onAiClick: () -> Unit,
) {
    CollectEvents(vm.events) { event ->
        when (event) {
            is NotesUiEvent.NavigateBack -> onBack()
            is NotesUiEvent.SaveFailed -> { /* show error */ }
            is NotesUiEvent.AiResult -> { /* show AI result dialog */ }
            NotesUiEvent.SavedPulse -> { /* handled by savedAlpha Animatable above */ }
        }
    }
}
```

`CollectEvents` is a thin wrapper:
```kotlin
@Composable
fun <T : UiEvent> CollectEvents(
    flow: SharedFlow<T>,
    onEvent: (T) -> Unit,
) {
    LaunchedEffect(flow) {
        flow.collect { onEvent(it) }
    }
}
```

## Why this matters

1. **The VM stays source of truth.** No way for the Composable to drift out of sync.
2. **Formatting is testable without Compose.** `formatAiResult(...)` is a pure function.
3. **The screen is thin.** No `LaunchedEffect`, no local state for domain events.
4. **The VM is testable without Compose.** `vm.events.test() { … }` from a `runTest` block.

## Test recipe for VM events

```kotlin
@Test
fun `saveNow emits NavigateBack on success`() = runTest {
    val vm = createVm(repo = FakeNotesRepository().apply { seed(note) })
    vm.saveNow()
    advanceUntilIdle()
    val event = vm.events.first()
    assertIs<NotesUiEvent.NavigateBack>(event)
}

@Test
fun `scheduleAutosave emits SavedPulse on success`() = runTest {
    val vm = createVm(repo = FakeNotesRepository().apply { seed(note) })
    vm.editTitle("n1", "Updated title")
    // simulate autosave completion
    val pulse = vm.savedPulse.first()
    assertIs<Unit>(pulse)  // SavedPulse is a data object
}
```

## Anti-patterns

**Do NOT use `mutableStateOf` for domain-derived strings:**
```kotlin
// ❌ WRONG — mirrors VM state in Composable
@Composable
fun TasksScreen(...) {
    var aiResultText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.aiResult.collectLatest { result ->
            aiResultText = when (result) {
                is AiActionResult.RefineTitle -> "Refined: ${result.newTitle}"
                // ...
            }
        }
    }
}

// ✅ CORRECT — format in VM, emit via event, render in ResultDialog
// ViewModel:
_events.emit(NotesUiEvent.AiResult(formatAiResult(result)))

// Screen:
CollectEvents(vm.events) { event ->
    if (event is NotesUiEvent.AiResult) dialogText = event.text
}
ResultDialog("AI Result", dialogText) { dialogText = null }
```

**Do NOT use Pulse events for replayable state:**
```kotlin
// ❌ WRONG — savedPulse should not replay; use StateFlow for replayable saves
val lastSaveTime: StateFlow<Instant?> = MutableStateFlow(null)

// ✅ CORRECT — savedPulse is one-shot, extraBufferCapacity = 1
val savedPulse: SharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
```

## When to keep `MutableStateFlow` inside a Composable

Genuinely Composable-local UI affordances only:
- `var sheetState by remember { mutableStateOf<Sheet?>(null) }` — UI routing, not domain
- `var searchQuery by remember { mutableStateOf("") }` — local to a search field before submission
- `Animatable` for one-shot UI animations (saved pill fade, etc.)

What must **not** live in a Composable:
- Anything derived from a VM `StateFlow` (duplicated state)
- Domain formatting (`when (result)` that produces user-facing strings)
- Anything other Composables on the same screen also need
