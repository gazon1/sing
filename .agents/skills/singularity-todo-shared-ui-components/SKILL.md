---
name: singularity-todo-shared-ui-components
description: The shared presentation library at `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/`. Use when adding a new reusable widget (state-aware dialog, empty-state placeholder, in-button spinner, action button), when replacing inline `AlertDialog` / `Box(CircularProgressIndicator)` / `Box(Text)` / inline icon buttons across screens, or when wiring a new ViewModel to its screen via `SharedFlow<UiEvent>`. Documents each existing component, its signature, when to use it, and how to add a new one without breaking the project's no-Compose-in-tests discipline.
---

# Shared UI Components (`core/ui/components/`)

The presentation layer of `shared/` exposes a small library of presentation primitives that every screen in `feature/*` consumes. **No feature-local copy of any of these is allowed** — if you find yourself writing `Box(Modifier.fillMaxSize()) { CircularProgressIndicator() }` in a screen, use `LoadingIndicator` instead.

## Catalogue

| Component | Purpose | Replaces |
|---|---|---|
| `UiEvent` (sealed) | One-shot events emitted by ViewModels | `var dialogText by remember { mutableStateOf<String?>(null) }` |
| `CollectEvents(flow, onEvent)` | `LaunchedEffect` wrapper for `SharedFlow` collection | `LaunchedEffect(Unit) { flow.collectLatest { … } }` boilerplate |
| `ResultDialog(title, text, onDismiss)` | Standard "AI Result" / error dialog | 4 copy-pasted `AlertDialog { title = "AI Result"; text = …; confirmButton = { TextButton("OK") } }` blocks |
| `LoadingIndicator(modifier)` | Full-screen spinner | `Box(Modifier.fillMaxSize(), contentAlignment = Center) { CircularProgressIndicator() }` |
| `EmptyState(title, subtitle?, modifier)` | Centered placeholder for empty lists / error messages | `Box(Modifier.fillMaxSize(), contentAlignment = Center) { Text("No X yet") }` |
| `MessageBubble(role, content, modifier)` | Single canonical chat-bubble widget | Two parallel implementations in old `ChatScreen.kt` files |
| `ChatInputBar(value, onValueChange, onSend, enabled, placeholder, maxLines, modifier)` | Outlined text field + Send button | Inline `Row { OutlinedTextField + IconButton }` in AI Chat |
| `AiActionButton(onClick, modifier)` | Sparkle icon button | Inline `IconButton { Icon(AutoAwesome, primary tint) }` in TaskCard / ProjectCard / NoteEditor toolbar |
| `DeleteActionButton(onClick, modifier)` | Trash icon button | Inline `IconButton { Icon(Delete, error tint) }` in every card |
| `ButtonSpinner(modifier)` | 24 dp in-button spinner | `CircularProgressIndicator(modifier = Modifier.size(24.dp))` in Login/Backup buttons |
| `Formatters.kt` (`priorityColorByIndex`, `hexColor`) | Pure color helpers, testable without Compose | `when (priority) { … Color(0xFF…) … }` blocks duplicated across screens |

All of these live at `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/`.

## When to add a new component here

Add to `core/ui/components/` when the widget:

1. Is **purely presentational** — no domain knowledge (no Task, no Note, no Project types)
2. Is **used by ≥ 2 features** OR replaces ≥ 3 near-identical inline copies across the codebase
3. Has **no business state of its own** — it just renders given props and fires callbacks

If a widget renders a domain entity (`Task`, `Note`, `Project`, `Tag`), put it in `feature/<feature>/components/` instead. Example: `TaskCard` lives in `feature/tasks/components/`, but `AiActionButton` lives in `core/ui/components/` because every feature needs it.

## The UiEvent contract

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

`extraBufferCapacity = 4` is important — without it `emit` from a finished coroutine is a no-op. With it, fast screen rotations do not drop events.

## `CollectEvents` vs `LaunchedEffect`

Always use `CollectEvents(flow) { … }` instead of `LaunchedEffect(Unit) { vm.events.collectLatest { … } }`. Reasons:

- The `flow` is the `LaunchedEffect` key — if the VM is recreated with a new flow, the collector restarts automatically
- Saves one line of plumbing per screen (4 screens × 5 lines = 20 lines removed in the refactor that created this skill)
- Makes the intent obvious: "collect events from this source"

## Adding a new shared component — checklist

1. **Confirm reusability**: `grep -rn <inline pattern> shared/src/commonMain/` — if ≥ 2 matches exist, extract.
2. **Place under** `core/ui/components/` (or `feature/<feature>/components/` if feature-specific).
3. **Default to `modifier: Modifier = Modifier` as the last parameter** so children can wrap the widget.
4. **Make the pure parts `internal`** so they can be unit-tested from the same package without `public` exposure:
   ```kotlin
   internal fun priorityColorByIndex(index: Int): Color = when (index) { … }
   ```
   Test goes in `shared/src/commonTest/kotlin/…/PriorityColorTest.kt` in the same package — `internal` is visible to tests in the same compilation unit.
5. **Wrap Compose-runtime reads inside `@Composable @ReadOnlyComposable`** if the helper is pure but reads `MaterialTheme.typography` etc.
6. **No state** in the widget — `var foo by remember` belongs in the parent (or in the ViewModel).

## Worked example: replacing inline loading/empty across 4 screens

**Before** (Tasks, Projects, Notes, Tags each have):

```kotlin
is TasksUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
is TasksUiState.Empty   -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No tasks for …") }
is TasksUiState.Error   -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Error: ${s.message}") }
```

**After** (one line each):

```kotlin
is TasksUiState.Loading -> LoadingIndicator()
is TasksUiState.Empty   -> EmptyState(title = "No tasks for …")
is TasksUiState.Error   -> EmptyState(title = "Error: ${s.message}")
```

Removed: ~24 lines of duplicated Box+Alignment ceremony across 4 screens.

## Anti-patterns

- **`var dialogText by remember { mutableStateOf<String?>(null) }` paired with a custom `AlertDialog`** — must be `var dialogText = remember { … }` + `CollectEvents(vm.events)` + `ResultDialog`.
- **A `ButtonSpinner` that calls `LoadingIndicator` from inside a Button** — `LoadingIndicator` wraps itself in `Box(fillMaxSize)` which is wrong inside a button; use `ButtonSpinner` (24 dp CircularProgressIndicator).
- **Feature-specific widget in `core/ui/components/`** — `TaskCard` doesn't belong here; only generic primitives do.
- **Compose-`@Composable` helper that's actually pure** (e.g. maps enum → color) — make it a plain `internal fun` so it can be tested without a Compose runtime.

## Test placement reminder

Components in `core/ui/components/` are presentation-only — do **not** add Robolectric / Compose-test tests for them. The widgets themselves are too thin to fail in interesting ways. What you test is the **pure helper next to them** (e.g. `priorityColorByIndex`, `toneColor`, `formatAiResult`). Tests for those go in `shared/src/commonTest/kotlin/…/` in the **same package** as the helper.
