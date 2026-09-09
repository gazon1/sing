---
name: singularity-todo-shared-ui-components
description: The complete UI decomposition pattern for Singularity Todo screens. Use when a Screen.kt grows past ~150 lines, when state/logic/UI mix inside one Composable, when the same inline widget (AlertDialog, Box+Spinner, Box+Text, Card+Row+Switch) is copy-pasted across screens, when a card takes 4+ separate callbacks, or when wiring a new ViewModel to its screen via SharedFlow<UiEvent>. Covers both the shared widget library at `core/ui/components/` and the rules for extracting per-feature widgets into `feature/<feature>/components/`. Built on top of `singularity-todo-ui-event-vs-state` and `singularity-todo-pure-formatters`.
---

# Shared UI Components & Composable Decomposition

This skill covers two complementary concerns:

1. **The shared widget library** at `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/` — every primitive that screens consume.
2. **The decomposition rules** for splitting a Screen.kt into smaller, testable, focused composables.

Read this skill first when touching any Compose screen. The two sub-concerns interlock: every decomposition should consume shared widgets, and every shared widget should be small enough to read in 30 seconds.

## Part 1 — The shared widget library

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

`extraBufferCapacity = 4` is important — without it `emit` from a finished coroutine is a no-op. With it, fast screen rotations do not drop events.

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

## Part 2 — Composable decomposition

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

## Part 3 — Worked examples

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

## Test placement reminder

Components in `core/ui/components/` are presentation-only — do **not** add Robolectric / Compose-test tests for them. The widgets themselves are too thin to fail in interesting ways. What you test is the **pure helper next to them** (e.g. `priorityColorByIndex`, `toneColor`, `formatAiResult`). Tests for those go in `shared/src/commonTest/kotlin/…/` in the **same package** as the helper.

---

## Part 6 — Content Slot API Design Rules

This section covers the three ways to pass behaviour into a reusable Composable, and when to use each.

### The three patterns

| Pattern | Signature | When to use |
|---|---|---|
| **Plain lambda** | `onClick: () -> Unit` | Single callback, simple |
| **Content slot** | `content: @Composable () -> Unit` | Arbitrary composable body |
| **Content slot with scope** | `body: @Composable RowScope.() -> Unit` | Caller needs scope functions (e.g. `Modifier.weight()`) |
| **Packed actions** | `actions: XxxActions` | 10+ callbacks — see `singularity-todo-task-callback-groups` |

### Decision tree

```
How many callbacks does the slot need to receive?
│
├─ 1–2 callbacks
│   └─ Use plain lambda parameters (onClick, onToggle, etc.)
│
├─ Arbitrary composable content (no data passed in)
│   └─ Use @Composable () -> Unit with default = {}
│       Example: EmptyState(actions = { Button { ... } })
│
├─ Content inside a Row/Column AND caller needs scope functions
│   └─ Use @Composable RowScope.() -> Unit or ColumnScope.() -> Unit
│       Example: Card(body = { Text(...) }, trailing = { IconButton { ... } })
│       (The scope gives access to Modifier.weight(), align(), etc.)
│
└─ 10+ callbacks all going to the same handler (typically a ViewModel)
    └─ Use @JvmInline value class XxxActions — see task-callback-groups skill
```

### Material 3 slot naming convention

Use **named slots** (not positional). Material 3 `Card` uses `header`/`content`. Material 3 `Scaffold` uses `topBar`/`bottomBar`.

For cards in this project:

```kotlin
@Composable
fun TaskCard(
    task: Task,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    actions: TaskCardActions = TaskCardActions.Empty,
    body: @Composable RowScope.() -> Unit = { DefaultTaskCardBody(task) },
    trailing: @Composable RowScope.() -> Unit = { DefaultTaskCardTrailing(task, actions) },
)
```

**Naming:** `body` for the main content slot, `trailing` for the right-side actions row. Do **not** use `leading`/`center`/`trailing` — this is not Material 3 convention.

### `typealias` for long slot signatures

When a slot type is repeated across multiple components, use a `typealias`:

```kotlin
typealias EmptyStateActions = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
typealias SettingsRowTrailing = @Composable androidx.compose.foundation.layout.RowScope.() -> Unit

@Composable
fun EmptyState(
    title: String,
    actions: EmptyStateActions = {},  // short and readable
    // ...
)
```

### Default empty slot for backward compatibility

**Always** provide a default value of `{}` for optional slots:

```kotlin
@Composable
fun EmptyState(
    title: String,
    actions: EmptyStateActions = {},  // ✅ default empty — backward-compatible
)

// Usage without the slot:
EmptyState(title = "No tasks yet")

// Usage with the slot:
EmptyState(title = "No tasks yet") {
    Button(onClick = {}) { Text("Create one") }
}
```

### `@Composable inline fun If` / `IfElse` helpers

Use `If` / `IfElse` for conditional rendering at the top level of a Composable body — cleaner than `if (cond) { content() }`:

```kotlin
@Composable
inline fun If(condition: Boolean, content: @Composable () -> Unit) {
    if (condition) content()
}

@Composable
inline fun IfElse(
    condition: Boolean,
    ifTrue: @Composable () -> Unit,
    ifFalse: @Composable () -> Unit,
) { if (condition) ifTrue() else ifFalse() }
```

**Usage:**
```kotlin
// Instead of:
if (state is NotesUiState.Empty) { EmptyState(...) }

// Use:
If(state is NotesUiState.Empty) {
    EmptyState(title = "No notes")
}
```

`inline` is used so the compiler inlines the lambda at each call site — no runtime allocation.

### What NOT to do

- **`enum class Action` for slots that carry data** — use `sealed class Action` with `data class` variants (see `TaskDetailActions`). An `enum` can't carry payload.
- **Nullable slot** (`content: @Composable () -> Unit? = null`) — use default `{}` instead. Nullable slots force null-checks at every call site.
- **`Modifier.apply { ... }`** — deprecated in Kotlin 2.x. Use `with(Modifier) { ... }` or just chain modifiers normally.
- **Positional slot names** (`slot1`, `slot2`, `leading`, `center`) — use Material 3 names (`body`, `trailing`).

### Reference implementations

- `EmptyState.kt` — `actions: @Composable ColumnScope.() -> Unit` with typealias and `If` helper
- `SettingsSection.kt` — `SettingsRow` with `trailing: @Composable RowScope.() -> Unit`
- `TaskCard.kt` — `body` + `trailing` slots with `RowScope`
- `BackTopAppBar.kt` — `actions` + `content` slots

See also:
- `singularity-todo-ui-event-vs-state` — full State vs Event dichotomy rationale and migration recipe
- `singularity-todo-pure-formatters` — when to extract formatters, three flavours, testing recipes

---

## Part 5 — Choosing between DropdownMenu, ModalBottomSheet, and AlertDialog

Three dismissal surfaces, one rule: **pick based on consequence severity, not screen real estate**.

| Surface | When to use | Example |
|---|---|---|
| **`DropdownMenu`** | ≤3 options, no complex content, anchored to a button | MoreVert overflow: Archive / Delete |
| **`ModalBottomSheet`** | >3 options, scrollable content, pickers (color, icon, date, parent) | Color picker, parent project picker, date picker |
| **`AlertDialog`** | Binary confirm/dismiss with irreversible consequence | Delete confirmation, archive confirmation |

### Decision tree

```
Does the action have irreversible consequences (delete, archive, discard)?
  → YES → AlertDialog (confirmButton is the dangerous action, dismissButton is safe)
  → NO  → How many distinct options/content blocks?
      → ≤3 options, simple labels → DropdownMenu (anchored to the triggering button)
      → >3 options OR scrollable content OR multi-step → ModalBottomSheet
```

### DropdownMenu — always anchored

```kotlin
Box {
    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.MoreVert, "More")
    }
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
    ) {
        DropdownMenuItem(
            text = { Text(if (isArchived) "Unarchive" else "Archive") },
            onClick = { menuOpen = false; sheetState = ActiveSheet.ConfirmArchive },
        )
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { menuOpen = false; sheetState = ActiveSheet.ConfirmDelete },
        )
    }
}
```

**Rule:** `DropdownMenu` MUST be inside a `Box` with the `IconButton` as the anchor. Never use `Box` alone — `IconButton` provides correct touch target semantics.

### AlertDialog — for destructive actions

```kotlin
AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete project?") },
    text = { Text("\"$projectName\" will be deleted. This cannot be undone.") },
    confirmButton = {
        TextButton(onClick = onConfirm) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    },
    dismissButton = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
    },
)
```

**Rule:** `confirmButton` carries the dangerous action. `dismissButton` is always safe. Title says what will happen, not what button to press.

### ModalBottomSheet — for pickers and multi-option flows

```kotlin
ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Parent project", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onPick(null) }) { Text("None (root)") }
        LazyColumn {
            items(options) { opt ->
                FilterChip(
                    selected = opt.isCurrent,
                    onClick = { onPick(opt.id) },
                    label = { Text(opt.name) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
```

**Rule:** Use `LazyColumn` for lists >5 items. Use `TextButton` for "None (root)" option — it's not a chip, it's a separate action. Always add `Spacer(Modifier.height(24.dp))` at the bottom for keyboard clearance.

### Anti-patterns

- **DropdownMenu for >3 options** — creates an uncomfortably long list; use `ModalBottomSheet`
- **AlertDialog for color picker** — color grid needs scroll and clear/cancel; use `ModalBottomSheet`
- **ModalBottomSheet for delete confirmation** — overkill for binary choice; use `AlertDialog`
- **No `onDismissRequest`** on `ModalBottomSheet` — always provide a way to dismiss without choosing

---

## Part 4 — Document-Style Decomposition (4-section rule)

Any detail screen (`XxxDetailScreen`) that follows the TickTick/Todoist document-style pattern must be decomposed into **exactly 4 sections**, never more. This limit is cognitive-load discipline: 4 sections fit comfortably in a senior engineer's mental model and are trivially testable.

### The 4 sections

| # | Section | Content | Example |
|---|---|---|---|
| 1 | **Hero** | Entity title (inline-editable), description placeholder, key identifier (checkbox, icon) | `ProjectHeroSection`, `TaskHeroSection` |
| 2 | **Meta chips** | Date, priority, status, tags — all as `FilterChip` in `FlowRow` | `ProjectMetaChipsRow`, `TaskMetaChipsRow` |
| 3 | **Body** | The entity's own content — tasks list, notes content, checklist | `ProjectTasksList`, `NoteBodySection` |
| 4 | **Bottom action bar** | Icon buttons for cross-cutting ops (Remind, Attach, Delete) + overflow menu | `ProjectBottomActionBar`, `TaskBottomActionBar` |

### What is NOT a section

Do **not** count as separate sections:
- `ActiveSheet` / bottom sheet routing (dialog overlay, not a screen section)
- `BottomAppBar` — the 4th section is the bottom bar itself; sub-components within it don't add to the count
- A "See all" link navigating to another screen — a navigation affordance within section 3, not a 5th section
- A `QuickAddInput` inline at the top of section 3 — still within section 3

### Rule: 4 sub-components maximum

A screen file should have no more than **4 private sub-composable functions** responsible for each of the 4 sections. If you write a 5th (e.g. separate "Tags row" extracted from "Meta chips"), consolidate: tags row + meta chips row = one `FlowRow` composable.

### Routing: `ActiveSheet` sealed interface

All pickers and confirmations are routed through a single `ActiveSheet` sealed interface on the screen — **not** a 5th section:

```kotlin
private sealed interface ActiveSheet {
    data object ConfirmDelete : ActiveSheet
    data class PickColor(val current: Int) : ActiveSheet
    data class PickIcon(val current: String?) : ActiveSheet
}

when (val sheet = sheetState.value) {
    is ActiveSheet.ConfirmDelete -> ConfirmDeleteSheet(onConfirm, onDismiss)
    is ActiveSheet.PickColor -> ColorPickerSheet(current = sheet.current, onPick, onDismiss)
    // ...
}
```

### Cross-feature navigation from detail screens

When a detail screen links to another entity (e.g. task → project), the navigation affordance must be **adjacent to the chip**, not on the chip:

```kotlin
// ✅ CORRECT — separate IconButton next to the chip
Row(verticalAlignment = CenterVertically) {
    FilterChip(project.name, ...)
    IconButton(Icons.AutoMirrored.Filled.ChevronRight, "Open project") {
        onNavigateToProject(project.id)
    }
}

// ❌ WRONG — combinedClickable on the chip
FilterChip(
    project.name,
    modifier = Modifier.combinedClickable(onClick = ..., onLongClick = ...)
)
```

`combinedClickable` conflicts with multi-select gestures (long-press enters selection mode). The chevron is a clear affordance that doesn't interfere. See `singularity-todo-cross-feature-navigation` skill.
