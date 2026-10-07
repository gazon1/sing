# Composable decomposition

Splitting a `Screen.kt` into smaller composables.

### The thin-view rule

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

### When to extract a sub-composable

Extract a function when ANY of these is true:

| Signal | Action |
|---|---|
| Function body > 60 lines | Split — pick a logical seam (a card, a section, a row) |
| Same `Box(Modifier.fillMaxSize()) { Text(...) }` repeated ≥ 2× | Use `EmptyState` |
| Same `AlertDialog(...)` block copy-pasted ≥ 2× | Use `ResultDialog` |
| `Card { Row { ... 5 children ... } }` ≥ 50 lines | Extract `XxxCard`, keep only data + callbacks as params |
| Card accepts ≥ 4 separate callback params | Pack into `@JvmInline value class XxxCardActions` |
| A `when (state)` branch is > 30 lines | Extract `XxxContent(state, …)` private composable |
| Toolbar / list / sheet / form has its own internal state | Extract to `feature/<feature>/components/` |

### Naming conventions

- `TasksContent(state, onClick, …)` — branch dispatcher that `when`'s on a sealed UiState
- `TaskList(tasks, onClick, …)` — the actual `LazyColumn` (separable from empty/loading branches)
- `TaskCard(task, onClick, actions)` — single item card
- `TaskAiBottomSheet(task, onAction, onDismiss)` — modal sheet
- `TasksFormatters.kt` — pure formatting functions next to the feature

### Parameter ordering

Every public composable follows: **state → callbacks → modifier**. Modifier is always last with a default of `Modifier`:

```kotlin
@Composable
fun TaskCard(
    task: Task,                                  // state
    onClick: () -> Unit,                         // callback
    actions: TaskCardActions = TaskCardActions.Empty,  // grouped callback
    modifier: Modifier = Modifier,               // layout override — LAST
)
```

### Grouping callbacks with `@JvmInline value class`

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

### When NOT to use a data class for grouping callbacks

See `singularity-todo-task-callback-groups` for the full decision tree. TL;DR:

- **Do NOT** group unrelated callbacks into a `data class ContentParams` — this creates a 4-level hierarchy and makes refactoring painful
- **Do NOT** use `data class` to group outgoing actions (those go to the VM) — use `@JvmInline value class` instead
- **Only** use a data class when 4+ incoming data parameters form a natural domain unit (e.g., `DueDateModel(date, time, zone)`)
- **Do NOT** introduce a new `Actions` class for 4 callbacks in a single-screen composable — raw lambdas are fine until usage spreads to 2+ screens
## Worked examples

### Worked example: `EditorToolbar` (combining decomposition + formatter extraction)

The toolbar's `apply` table used to live inline in the toolbar composable as a long `when` chain. Now it's an `internal` extension on `RichTextState`:

```kotlin
// components/EditorToolbar.kt — pure helper, internal
internal fun RichTextState.apply(action: EditorAction): RichTextState = when (action) {
    EditorAction.Bold -> apply { toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
    EditorAction.Italic -> apply { toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
    …
}

// Composable is now 12 lines
IconButton(onClick = {
    richTextState.apply(toolbarAction)
    onHtmlChange()
})
```

The `apply` helper is independent of `IconButton` — you can test it directly in `RichTextActionsTest`.

### Worked example: `EditorAction` sealed interface

The previous `value class EditorAction` with `private constructor` forced all consumers to write `when (action.key) { "bold" -> … }` — no exhaustiveness, no IDE help. Replaced with a sealed interface:

```kotlin
sealed interface EditorAction {
    data object Bold : EditorAction
    data object Italic : EditorAction
    …
    companion object { val all: List<EditorAction> = listOf(Bold, Italic, …) }
}
```

Now `RichTextState.apply` is exhaustive over `EditorAction`, the compiler checks every branch, and adding a new action causes a build failure in every `when` site — not a silent miss.

### Worked example: replacing inline loading/empty across 4 screens

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

### Worked example: `TaskEditorContent` — slot API for editor unification

`TaskCreateScreen` and a second task-detail screen both rendered near-identical task editor forms. Three files were unified into one:

**Before** (two separate composables + a duplicate top bar):
```
TaskCreateContent.kt       — title row, description, priority/due chips, save bar
TaskDetailViewContent.kt   — same structure + checklist/project/tags sections
TaskCreationTopBar.kt      — duplicate of TaskDetailTopBar
```

**After** — one `TaskEditorContent` with slot parameters:

```kotlin
@Composable
fun TaskEditorContent(
    // State
    titleDraft: String,
    onTitleChange: (String) -> Unit,
    titleLeading: (@Composable () -> Unit)? = null,        // checkbox for view mode
    descriptionDraft: String,
    onDescriptionChange: (String) -> Unit,
    attributes: List<TaskEditorAttribute>,                   // priority, due date chips

    // Slots
    extraSections: (@Composable () -> Unit)? = null,         // checklist, project, tags, timestamps
    bottomBar: (@Composable () -> Unit)? = null,            // save button (create) or null (view)
    menuItems: List<TaskEditorMenuItem> = emptyList(),       // archive/delete dropdown

    // Navigation
    onBack: () -> Unit,
)
```

**Call site for create mode:**
```kotlin
TaskEditorContent(
    titleDraft = state.draft.title,
    onTitleChange = { vm.onIntent(TaskCreateIntent.TitleChanged(it)) },
    titleLeading = null,  // no checkbox in create mode
    descriptionDraft = state.draft.description,
    onDescriptionChange = { ... },
    attributes = attributes,
    extraSections = null,  // no view-only sections in create mode
    bottomBar = { TaskSaveBar(isEnabled = state.isSaveEnabled, ...) },
    menuItems = emptyList(),
    onBack = guardedBack,
)
```

**Call site for view mode:**
```kotlin
TaskEditorContent(
    titleDraft = ui.titleDraft,
    onTitleChange = { vm.onIntent(TaskDetailIntent.Domain.TitleChanged(it)) },
    titleLeading = { Checkbox(checked = ui.task.isCompleted, ...) },  // inline checkbox
    descriptionDraft = ui.descriptionDraft,
    onDescriptionChange = { ... },
    attributes = attributes,
    extraSections = {
        if (ui.checklist.isNotEmpty()) TaskChecklistCard(...)
        ui.project?.let { TaskAttributeCard(icon = Icons.Filled.Folder, label = it.name, ...) }
        if (ui.tags.isNotEmpty()) TaskAttributeCard(...)
        if (ui.subtasks.isNotEmpty()) TaskCounterCard(...)
        if (ui.attachments.isNotEmpty()) TaskCounterCard(...)
        Text(timestamps, style = MaterialTheme.typography.bodySmall)
    },
    bottomBar = null,  // no save bar in view mode
    menuItems = listOf(
        TaskEditorMenuItem(label = "Archive") { vm.onIntent(TaskDetailIntent.Domain.Archive) },
        TaskEditorMenuItem(label = "Delete") { vm.onIntent(TaskDetailIntent.Domain.Delete) },
    ),
    onBack = { navigator.back() },
)
```

**Key design decisions:**
- `extraSections: @Composable () -> Unit?` — single slot for all view-only sections; avoids 7 separate slot parameters
- `attributes: List<TaskEditorAttribute>` — iteration over stable list; `key: Any` in data class handles recomposition stability for lambda callbacks
- `menuItems: List<TaskEditorMenuItem>` — declarative list instead of lambda-in-lambda (`dropdownMenu: (closeMenu: () -> Unit) -> Unit`)
- `bottomBar: @Composable () -> Unit?` — null for view mode (no save bar), set for create mode
## Test placement
Components in `core/ui/components/` are presentation-only — do **not** add Robolectric / Compose-test tests for them. The widgets themselves are too thin to fail in interesting ways. What you test is the **pure helper next to them** (e.g. `priorityColorByIndex`, `toneColor`, `formatAiResult`). Tests for those go in `shared/src/commonTest/kotlin/…/` in the **same package** as the helper.

---
