# The shared widget library

Primitives in `core/ui/components/` that every screen consumes.

### Catalogue

| Component | Purpose | Replaces |
|---|---|---|
| `UiEvent` (sealed) | One-shot events emitted by ViewModels | `var dialogText by remember { mutableStateOf<String?>(null) }` |
| `CollectEvents(flow, onEvent)` | `LaunchedEffect` wrapper for `SharedFlow` collection | `LaunchedEffect(Unit) { flow.collectLatest { … } }` boilerplate |
| `ResultDialog(title, text, onDismiss)` | Standard "AI Result" / error dialog | 4 copy-pasted `AlertDialog { title = "AI Result"; text = …; confirmButton = { TextButton("OK") } }` blocks |
| `LoadingIndicator(modifier)` | Full-screen spinner | `Box(Modifier.fillMaxSize(), contentAlignment = Center) { CircularProgressIndicator() }` |
| `EmptyState(title, subtitle?, modifier)` | Centered placeholder for empty lists / error messages | `Box(Modifier.fillMaxSize(), contentAlignment = Center) { Text("No X yet") }` |
| `MessageBubble(role, content, modifier)` | Single canonical chat-bubble widget | Two parallel implementations in old ChatScreen files |
| `ChatInputBar(value, onValueChange, onSend, enabled, placeholder, maxLines, modifier)` | Outlined text field + Send button | Inline `Row { OutlinedTextField + IconButton }` in AI Chat |
| `AiActionButton(onClick, modifier)` | Sparkle icon button | Inline `IconButton { Icon(AutoAwesome, primary tint) }` in TaskCard / ProjectCard / NoteEditor toolbar |
| `DeleteActionButton(onClick, modifier)` | Trash icon button | Inline `IconButton { Icon(Delete, error tint) }` in every card |
| `ButtonSpinner(modifier)` | 24 dp in-button spinner | `CircularProgressIndicator(modifier = Modifier.size(24.dp))` in Login/Backup buttons |
| `SettingsSection(title, modifier) { content }` | Titled card used as a visual sub-section | `Card { Column { Text("Section", titleSmall) … } }` repeated in every settings screen |
| `SettingsSwitchRow(title, subtitle?, checked, onCheckedChange, modifier)` | Label + optional subtitle + Switch | `Row { Column { Text; Text } Switch }` for every toggle setting |
| `Formatters.kt` (`priorityColorByIndex`, `hexColor`) | Pure color helpers, testable without Compose | `when (priority) { … Color(0xFF…) … }` blocks duplicated across screens |
| `OverlayState<S>` (`show`, `dismissSheet`, `toggleOverflow`, `dismissAll`, `snackbarHostState`) | Coordinates sheet + overflow menu + snackbar in one place | 3 independent `remember { mutableStateOf(false/true) }` flags per screen |
| `FormState<T>` (abstract base) | Immutable form data with `update { copy() }`; concrete subclass provides `Saver` | Mutable `var` fields + manual `rememberSaveable` in each screen |

All of these live at `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/`.

### When to add a new component here

Add to `core/ui/components/` when the widget:

1. Is **purely presentational** — no domain knowledge (no Task, no Note, no Project types)
2. Is **used by ≥ 2 features** OR replaces ≥ 3 near-identical inline copies across the codebase
3. Has **no business state of its own** — it just renders given props and fires callbacks

If a widget renders a domain entity (`Task`, `Note`, `Project`, `Tag`), put it in `feature/<feature>/components/` instead. Example: `TaskCard` lives in `feature/tasks/components/`, but `AiActionButton` lives in `core/ui/components/` because every feature needs it.

### The `UiEvent` contract

`UiEvent` is the standard one-shot event channel between ViewModel and Screen. Every screen that needs to show a dialog / toast / navigate-back uses this — never a local `mutableStateOf<String?>(null)`.

```kotlin
sealed interface UiEvent {
    data class ShowDialog(val title: String, val text: String) : UiEvent
    data class ShowError(val message: String) : UiEvent
    data object NavigateBack : UiEvent
}
```

**ViewModel side:**
```kotlin
private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
val events: SharedFlow<UiEvent> = _events.asSharedFlow()

fun refineTask(task: Task) = viewModelScope.launch {
    useCase(task).onSuccess {
        _events.emit(UiEvent.ShowDialog("AI Result", formatResult(it)))
    }.onFailure {
        _events.emit(UiEvent.ShowError(it.message ?: "Failed"))
    }
}
```

**Screen side:**
```kotlin
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

`extraBufferCapacity = 4` is the project convention for `*UiEvent` SharedFlows — see
`singularity-todo-testable-vm` for the full explanation (why 4 vs 1 vs UNLIMITED, what
actually goes wrong with `emit()` on rotation, and the distinction from `tryEmit()`).

### The `NotificationHost` mapper — sealed interface gotcha

When a ViewModel emits a **per-feature sealed interface** (not the global `UiEvent`), the `NotificationHost` mapper's `it` is the **sealed interface type**, not the concrete data class:

```kotlin
// The ViewModel emits SavedAgendaListEvent (sealed interface)
sealed interface SavedAgendaListEvent {
    data class ShowError(val message: String) : SavedAgendaListEvent
    // No NavigateBack — that's handled via onNavigateBack
}

// WRONG — 'it' is SavedAgendaListEvent (sealed interface), which has no 'message'
NotificationHost(
    events = viewModel.events,
    mapper = { Notification.Error(it.message) },  // ← compile error: 'message' unresolved
)

// CORRECT — 'when' over subtypes, explicit type on lambda parameter
NotificationHost(
    events = viewModel.events,
    mapper = { e ->
        when (e) {
            is SavedAgendaListEvent.ShowError -> Notification.Error(e.message)
            else -> Notification.None
        }
    },
    onNavigateBack = { navigator.back() },
)
```

The same issue occurs with any lambda over a sealed interface — always use a typed `when` for exhaustive matching.

### `CollectEvents` vs `LaunchedEffect`

Always use `CollectEvents(flow) { … }` instead of `LaunchedEffect(Unit) { vm.events.collectLatest { … } }`. Reasons:
- The `flow` is the `LaunchedEffect` key — if the VM is recreated with a new flow, the collector restarts automatically
- Saves one line of plumbing per screen
- Makes the intent obvious: "collect events from this source"

### Adding a new shared component — checklist

1. **Confirm reusability**: `grep -rn <inline pattern> shared/src/commonMain/` — if ≥ 2 matches exist, extract.
2. **Place under** `core/ui/components/` (or `feature/<feature>/components/` if feature-specific).
3. **Default to `modifier: Modifier = Modifier` as the last parameter** so children can wrap the widget.
4. **Make the pure parts `internal`** so they can be unit-tested from the same package without `public` exposure.
5. **Wrap Compose-runtime reads inside `@Composable @ReadOnlyComposable`** if the helper is pure but reads `MaterialTheme.typography`.
6. **No state** in the widget — `var foo by remember` belongs in the parent (or in the ViewModel).

### Anti-patterns

- **`var dialogText by remember { mutableStateOf<String?>(null) }` paired with a custom `AlertDialog`** — must be `var dialogText = remember { … }` + `CollectEvents(vm.events)` + `ResultDialog`.
- **A `ButtonSpinner` that calls `LoadingIndicator` from inside a Button** — wrong size; use `ButtonSpinner`.
- **Feature-specific widget in `core/ui/components/`** — `TaskCard` doesn't belong here; only generic primitives do.
- **Compose-`@Composable` helper that's actually pure** (e.g. maps enum → color) — make it a plain `internal fun`.

### Architecture Rule: `core/` Must Not Import Feature Types

`core/ui/components/` is shared infrastructure. **It must never import from `feature/notes/`, `feature/tasks/`, `feature/projects/`, etc.**

**Why:** `core/` is loaded before any feature module and has no knowledge of domain entities. Introducing feature imports creates a hard circular dependency boundary and breaks modularity.

**If a component needs feature-specific data:**
1. Define a generic data class in the feature layer (e.g. `LinkResult` in `feature/notes/`)
2. Pass it as a constructor parameter / callback to the shared component
3. Keep the shared component purely presentational

**Correct example — generic picker:**
```
feature/notes/
└── components/InternalLinkPickerSheet.kt   ← feature-specific, knows about LinkResult

NoteEditorScreen.kt                       ← imports InternalLinkPickerSheet, passes LinkResult
```

**Wrong example — feature types in core:**
```
core/ui/components/
└── InternalLinkPickerSheet.kt           ← imported Note/Task from feature/notes
```

**Migration pattern:** When a component in `core/ui/components/` gains a feature-domain import, move it to `feature/<feature>/components/` and make it generic (accept adapter types from the caller).