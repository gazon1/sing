# One-shot events

## The rule (enforced)

**Domain state must live in the ViewModel.** No `remember { mutableStateOf }` that copies a value already present in a VM `StateFlow`. No `LaunchedEffect` that mirrors VM state into a local variable. No repository calls from inside a Composable. The Composable subscribes; the ViewModel owns.

See `docs/decisions/2026-09-15-viewmodel-state-ownership.md` for the canonical decision record with the full rationale and anti-pattern catalog.

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

Some events carry no data — they are just a signal to trigger a UI animation or reaction. Use `MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 0)`:

(`extraBufferCapacity = 1` here vs `= 4` for payload-bearing events is a deliberate
convention — see `singularity-todo-testable-vm` for the canonical explanation.)

```kotlin
// NotesViewModel — Saved pulse (Phase 1)
private val _savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val savedPulse: SharedFlow<Unit> = _savedPulse.asSharedFlow()

private fun scheduleAutosave(id: String) {
    autosaveJob?.cancel()
    // Dispatchers.Unconfined runs inline until the first suspension point (here,
    // `awaitTick()`); after resume it continues on whatever thread the resumed
    // continuation uses. For autosave this is fine: the work is short, idempotent
    // on retry, and we're not relying on a particular dispatcher downstream.
    // If autosave grew to do heavy CPU work, swap to `Dispatchers.Default`.
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
