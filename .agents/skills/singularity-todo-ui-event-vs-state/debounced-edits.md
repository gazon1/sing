# Debounced edits — silent save pattern

## Debounced Edits — Silent Save Pattern

For inline-editable fields (title, description), the debounced save must **not** emit a `Saved` event or pulse. The user is still typing — surfacing a "Saved" indicator on every keystroke is annoying UX and floods the event channel.

### The correct pattern

```kotlin
// ViewModel
private val _lastEditedAt = MutableStateFlow<Instant?>(null)
val lastEditedAt: StateFlow<Instant?> = _lastEditedAt.asStateFlow()

private var debounceJob: Job? = null

fun onTitleChange(id: TaskId, draft: String) {
    debounceJob?.cancel()
    debounceJob = viewModelScope.launch {
        delay(300)  // debounce
        repo.updateTitle(id, draft).getOrThrow()
        _lastEditedAt.value = clock.now()  // SILENT — no event emitted
    }
}
```

```kotlin
// Screen — top bar shows continuous "Saved X ago" text
val lastEditedAt by vm.lastEditedAt.collectAsStateWithLifecycle()

TopAppBar(
    title = {
        Row(verticalAlignment = CenterVertically) {
            Text(state.title)
            lastEditedAt?.let { time ->
                Text(
                    formatSavedRelative(clock.now(), time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
)
```

### When to emit `Saved` / pulse events

Only emit `SavedUiEvent` or `SavedPulse` when:
1. The user performs an **explicit action** (taps checkbox, confirms date picker, applies AI suggestion)
2. A **bulk operation** completes (archive all, delete selected)
3. A **navigation-triggered save** occurs (user taps Back and unsaved changes auto-flush)

### The anti-pattern (Regression 5 in `singularity-todo-task-detail-ux`)

```kotlin
// ❌ WRONG — emits Saved on every debounced keystroke
debouncedTitle
    .debounce(300)
    .onEach { repo.updateTitle(it).getOrThrow() }
    .onEach { _events.emit(TaskDetailUiEvent.Saved("Title updated")) }  // SPAM!
    .launchIn(viewModelScope)

// ✅ CORRECT — silent, no event
debouncedTitle
    .debounce(300)
    .onEach {
        repo.updateTitle(it).getOrThrow()
        _lastEditedAt.value = clock.now()  // silent continuous state
    }
    .launchIn(viewModelScope)
```

See `singularity-todo-inline-edit-saved-feedback` skill for the full worked example.

---
