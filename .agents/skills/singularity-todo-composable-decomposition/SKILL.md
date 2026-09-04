---
name: singularity-todo-composable-decomposition
description: How to decompose Singularity Todo screens into small, focused composables. Use when a Screen.kt grows past ~150 lines, when state/logic/UI mix inside one Composable, when the same inline widget (AlertDialog, Box+Spinner, Box+Text) is copy-pasted across screens, or when a card takes 4+ separate callbacks. Documents the thin-view pattern (VM owns state + Intent, Composable is one Widget), the `*Content` / `*List` / `*Card` extraction rules, and `Modifier`-first parameter ordering. Built on top of `singularity-todo-shared-ui-components`.
---

# Composable Decomposition — Thin View Pattern

A Compose screen in this project should follow the same shape as a React smart/dumb split: a single Screen-level Composable orchestrates state and routing, and every visual block is its own small function. This keeps each function testable in isolation and prevents the "wall of code" smell that previously grew TasksScreen to 306 lines.

## The thin-view rule

**A Composable does ONE thing** — render a widget. It does not:
- collect flows (the parent ViewModel already exposes `StateFlow`)
- build `LaunchedEffect` blocks for one-shot events (use `CollectEvents`)
- format domain results into user-facing strings (use a pure formatter)
- own `remember { mutableStateOf<String?>(null) }` for AI-result dialogs (use `UiEvent.ShowDialog`)

**Before:**
```kotlin
@Composable
fun TasksScreen(...) {
    val vm: TasksViewModel = koinInject()
    val state by vm.state.collectAsState()
    var aiResultText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.aiResult.collectLatest { result ->
            aiResultText = when (result) {
                is AiActionResult.RefineTitle -> "Refined title: ${result.newTitle}"
                // ...
            }
        }
    }
    // ... 100 more lines of layout + branches + dialog
}
```

**After:**
```kotlin
@Composable
fun TasksScreen(...) {
    val vm: TasksViewModel = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    var dialogText by remember { mutableStateOf<String?>(null) }

    CollectEvents(vm.events) { event ->
        if (event is UiEvent.ShowDialog || event is UiEvent.ShowError) {
            dialogText = event.toDialogText()
        }
    }

    Scaffold(...) { padding ->
        Column(Modifier.padding(padding)) {
            FilterChipsRow(filter, vm::setFilter)
            TasksContent(state, ...)
        }
    }
    ResultDialog("AI Result", dialogText) { dialogText = null }
}
```

## When to extract a sub-composable

Extract a function when ANY of these is true:

| Signal | Action |
|---|---|
| Function body > 60 lines | Split — pick a logical seam (a card, a section, a row) |
| Same `Box(Modifier.fillMaxSize()) { Text(...) }` repeated ≥ 2× | Use `EmptyState` from `core/ui/components` |
| Same `AlertDialog(...)` block copy-pasted ≥ 2× | Use `ResultDialog` from `core/ui/components` |
| `Card { Row { ... 5 children ... } }` ≥ 50 lines | Extract `XxxCard`, keep only data + callbacks as params |
| Card accepts ≥ 4 separate callback params (`onClick`, `onToggle`, `onDelete`, `onAiClick`) | Pack into `@JvmInline value class XxxCardActions` |
| A `when (state)` branch is > 30 lines | Extract `XxxContent(state, …)` private composable |
| Toolbar / list / sheet / form has its own internal state | Extract to `feature/<feature>/components/` |

## Naming conventions

- `TasksContent(state, onClick, …)` — branch dispatcher that `when`'s on a sealed UiState
- `TaskList(tasks, onClick, …)` — the actual `LazyColumn` (separable from empty/loading branches)
- `TaskCard(task, onClick, actions)` — single item card
- `TaskAiBottomSheet(task, onAction, onDismiss)` — modal sheet
- `TasksFormatters.kt` — pure formatting functions next to the feature

## Parameter ordering

Every public composable follows: **state → callbacks → modifier**. Modifier is always last with a default of `Modifier`, so child code can override layout:

```kotlin
@Composable
fun TaskCard(
    task: Task,                                  // state
    onClick: () -> Unit,                         // callback
    actions: TaskCardActions = TaskCardActions.Empty,  // grouped callback
    modifier: Modifier = Modifier,               // layout override — LAST
)
```

## Grouping callbacks with `@JvmInline value class`

When a card has multiple actions (toggle / delete / AI / pin / archive), pack them into a value-class dispatcher:

```kotlin
@JvmInline
value class TaskCardActions(val block: (Action) -> Unit) {
    enum class Action { Toggle, Delete, Ai }
    fun onToggle() = block(Action.Toggle)
    fun onDelete() = block(Action.Delete)
    fun onAiClick() = block(Action.Ai)
    companion object { val Empty = TaskCardActions {} }
}
```

Why:
- Adding a new action (e.g. `Pin`) does not break any call site
- The card has **one** parameter for "what can happen here" instead of N callbacks
- Call sites that don't care about a button (e.g. `SearchScreen` showing a read-only preview) pass `TaskCardActions.Empty`

## Reuse the core UI library first

Before extracting a private composable, check `core/ui/components/` — there is already:

- `UiEvent` (sealed ShowDialog / ShowError / NavigateBack)
- `CollectEvents` (LaunchedEffect wrapper for SharedFlow)
- `ResultDialog(title, text, onDismiss)`
- `LoadingIndicator(modifier)` (full-screen)
- `EmptyState(title, subtitle, modifier)`
- `MessageBubble(role, content)` (chat bubble)
- `ChatInputBar(value, onValueChange, onSend, enabled, …)`
- `AiActionButton(onClick, modifier)` / `DeleteActionButton(onClick, modifier)`
- `ButtonSpinner(modifier)` (in-button spinner for Login/Backup)
- `Formatters.kt` (`priorityColorByIndex`, `hexColor`)

If the widget is feature-specific (a task card, a project card), put it in `feature/<feature>/components/` not `core/ui/components/`.

## Anti-patterns to flag in review

- A `Screen.kt` file > 250 lines — almost certainly hiding an un-extracted content/list/card
- `var dialogText by remember { mutableStateOf<String?>(null) }` paired with `LaunchedEffect { vm.X.collectLatest { dialogText = ... } }` — should be `vm.events` + `ResultDialog`
- `AlertDialog(onDismissRequest = ..., title = { Text("AI Result") }, text = { Text(...) }, confirmButton = { TextButton(...) })` — must use `ResultDialog`
- `Card { Row { Column(...) IconButton(...) IconButton(...) IconButton(...) } }` with 3+ callbacks — must use `XxxCardActions` value class
- `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }` — must use `LoadingIndicator`
- `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No X yet") }` — must use `EmptyState`
- Inline `when (state)` inside `Screen.kt` with 4+ branches each > 10 lines — extract `XxxContent`

## Worked example: extracting `TaskCard`

Before (71 lines, 4 callbacks):

```kotlin
@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onAiClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick), ...) {
        Row(modifier = Modifier.padding(12.dp), ...) {
            IconButton(onClick = onToggle) { Icon(...) }       // 10 lines
            Column(Modifier.weight(1f)) { Text(...); Text(...) } // 14 lines
            PriorityChip(...)
            IconButton(onClick = onAiClick) { Icon(...) }
            IconButton(onClick = onDelete) { Icon(...) }
        }
    }
}
```

After:

```kotlin
// components/TaskCardActions.kt — value class
@JvmInline
value class TaskCardActions(val block: (Action) -> Unit) {
    enum class Action { Toggle, Delete, Ai }
    fun onToggle() = block(Action.Toggle)
    fun onDelete() = block(Action.Delete)
    fun onAiClick() = block(Action.Ai)
    companion object { val Empty = TaskCardActions {} }
}

// components/TaskCard.kt — uses core/ui/components for buttons
@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    actions: TaskCardActions = TaskCardActions.Empty,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick), ...) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleButton(task.isCompleted, actions::onToggle)
            TaskText(task, Modifier.weight(1f))
            PriorityChip(task.priority)
            AiActionButton(actions::onAiClick)
            DeleteActionButton(actions::onDelete)
        }
    }
}

@Composable private fun ToggleButton(isCompleted: Boolean, onClick: () -> Unit) { /* 12 lines */ }
@Composable private fun TaskText(task: Task, modifier: Modifier) { /* 14 lines */ }
```

Each inner composable is now independently inspectable and the call site (Screen) just hands a `TaskCardActions { when (it) { ... } }` lambda.
