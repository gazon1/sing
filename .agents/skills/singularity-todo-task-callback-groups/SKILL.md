---
name: singularity-todo-task-callback-groups
description: Decision tree for grouping callbacks in Composable function signatures — when to use raw lambdas (≤3 params), when to use a @JvmInline value class Action hierarchy (outgoing events), and when to avoid data class "parameter objects" that group unrelated callbacks.
---

# Callback Grouping Patterns in Composables

This skill documents when to use each callback-bundling approach, based on lessons from Phase 6 refactoring.

## The Decision Tree

```
How many callbacks does your Composable need?
│
├─ 0–3 callbacks
│   └─ Use RAW LAMBDA PARAMETERS — no wrapper needed
│
├─ 4+ OUTGOING callbacks (Composable → ViewModel actions)
│   └─ Use @JvmInline value class with sealed Action hierarchy
│       Example: NotesActions, NoteCardActions, ProjectDetailActions
│
├─ 4+ INCOMING callbacks (parent → child data)
│   └─ Consider a data class — but ONLY if they form a cohesive group
│       (same domain concept, same lifecycle)
│
└─ 10+ callbacks from the SAME source
    └─ You likely have a design problem — the composable does too much
        Fix: decompose the screen first, then re-evaluate
```

## Pattern 1: Raw Lambdas (≤3 params)

**Use when:** 1–3 callbacks, all from the same logical source.

```kotlin
// ✅ CORRECT — raw lambdas, easy to read, easy to test
@Composable
fun RemindersSection(
    reminders: List<Reminder>,
    timeZone: TimeZone,
    onDeleteReminder: (Reminder) -> Unit,  // 1 callback
    modifier: Modifier = Modifier,
)

// ❌ WRONG — wrapping 1-2 callbacks in a data class is over-engineering
data class ReminderCallbacks(
    val onDelete: (Reminder) -> Unit,
    val onEdit: (Reminder) -> Unit,
)
@Composable
fun RemindersSection(
    reminders: List<Reminder>,
    callbacks: ReminderCallbacks,  // over-engineered
)
```

**When to split a lambda out of an existing actions object:**
- If a section is used in only ONE screen AND has ≤3 callbacks, raw lambdas are fine.
- If a section is used in MULTIPLE screens AND has 4+ callbacks, use a `value class Actions`.

## Pattern 2: `@JvmInline value class` with sealed Action hierarchy

**Use when:** 4+ outgoing actions from a composable, all dispatched to the same handler (typically a ViewModel).

This is the pattern used by `NotesActions` and `NoteCardActions`. `TaskDetailActions` follows it too, but also pairs with a `TaskDetailIntent` hierarchy (see `singularity-todo-vm-intent-pattern`):

```kotlin
@JvmInline
value class NotesActions(
    private val block: (Action) -> Unit,
) {
    sealed class Action {
        data class NavigateToNote(val id: NoteId) : Action()
        data object DeleteSelected : Action()
        // ...
    }

    fun onNavigateToNote(id: NoteId) = block(Action.NavigateToNote(id))
    // ...
}
```

**Why `@JvmInline value class` and not a plain class:**
- Zero runtime allocation overhead — the single-parameter constructor is a value class
- Enables exhaustive `when` in the handler (smart cast on `Action` subtypes)
- Type-safe: passing wrong action type is a compile error
- Consumers get one parameter instead of 10–20 individual lambdas

**Why NOT a plain `data class`:**
```kotlin
// ❌ ANTI-PATTERN — data class of lambdas
data class TaskDetailCallbacks(
    val onToggleComplete: () -> Unit,
    val onTitleChange: (String) -> Unit,
    // ... 20 more
)
// Problems:
// - 20 constructor parameters — still verbose at call site
// - No exhaustiveness checking in when block
// - Runtime allocation for every instance
// - Groups unrelated callbacks into one container (no cohesion)
```

**Why NOT a `ContentParams` data class with typed sub-objects:**
```kotlin
// ❌ ANTI-PATTERN — nested data classes for grouping related callbacks
data class ContentParams(
    val hero: HeroCallbacks,
    val meta: MetaCallbacks,
    val checklist: ChecklistCallbacks,
    // ...
)
// This creates a 4-level nested hierarchy that is:
// - Hard to construct at the call site
// - Refactors poorly when a callback moves between groups
// - Not cohesive — hero/meta/checklist are all the SAME type of thing (outgoing actions)
```

**Correct usage:**
```kotlin
// Section composable receives ONE actions parameter
@Composable
fun TaskSubtasksSection(
    subtasks: List<Task>,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    subtasks.forEach { task ->
        SubtaskRow(
            task = task,
            onToggle = { actions.onToggleSubtask(task) },  // fires Action.ToggleSubtask
            onDelete = { actions.onDeleteSubtask(task) },
            onClick = { actions.onNavigateToSubtask(task.id) },
        )
    }
}
```

## Pattern 3: Data Class for Incoming Data (rare)

**Use when:** You have 4+ related **incoming** data parameters that always change together and form a cohesive domain concept.

```kotlin
// ✅ ACCEPTABLE — cohesive domain object
data class DueDateModel(
    val date: LocalDate?,
    val time: LocalTime?,
    val timeZone: TimeZone,
)

@Composable
fun DueDateChip(model: DueDateModel, onPick: () -> Unit) {
    // All 3 params change together and form one concept
}
```

```kotlin
// ❌ ANTI-PATTERN — unrelated params grouped into data class
data class HeroDisplayParams(
    val title: String,
    val onTitleChange: (String) -> Unit,  // NOT incoming data!
    val description: String?,
    val onDescriptionChange: (String) -> Unit,  // NOT incoming data!
)
// Problem: mixes incoming (title, description) with outgoing (callbacks)
// This is a "parameter object" anti-pattern — the class groups things
// that happen to come from the same parent, not things that form a natural unit
```

## Common Mistakes

### Mistake 1: Grouping outgoing actions into nested data classes

```kotlin
// ❌ WRONG — outgoing actions split into sub-groups
data class HeroCallbacks(
    val onToggle: () -> Unit,
    val onTitleChange: (String) -> Unit,
)
data class ChecklistCallbacks(
    val onToggleItem: (ChecklistItem) -> Unit,
    val onDeleteItem: (ChecklistItemId) -> Unit,
)
data class ContentParams(
    val hero: HeroCallbacks,
    val checklist: ChecklistCallbacks,
)

// At the call site — painful to construct:
ContentParams(
    hero = HeroCallbacks(onToggle = vm::toggle, onTitleChange = vm::setTitle),
    checklist = ChecklistCallbacks(onToggleItem = vm::toggleItem, onDeleteItem = vm::deleteItem),
)

// ✅ CORRECT — single flat NotesActions with sealed hierarchy
val actions = NotesActions { action ->
    when (action) {
        is NotesActions.Action.NavigateToNote -> onNavigateToNote(action.id)
        is NotesActions.Action.Delete -> viewModel.delete(action.id)
        // ...
    }
}
```

### Mistake 2: Creating a new `Actions` class for every screen

```kotlin
// ❌ OVER-ENGINEERED — creating a new actions class for 4 callbacks
data class ProjectDetailActions(/* ... */)

// When ProjectDetailScreen only has 4 callbacks and is used in 1 place
@Composable
fun ProjectDetailScreen(
    actions: ProjectDetailActions,  // over-engineered for 4 callbacks
)

// ✅ CORRECT — raw lambdas when count is low
@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
)
```

### Mistake 3: Adding `Actions` too early

If a section composable has 4 callbacks today but is only used in 1 screen, **wait**. It may grow to 10 callbacks across refactors, at which point you extract the `Actions` object. Extracting it later is easy; extracting it prematurely creates unnecessary indirection.

### Mistake 4: Actions value class whose helpers fan out to N individual VM methods

The `value class Actions` is a **Compose-side packing** convention. Its `block` should end in **one** call to the VM — `viewModel.onIntent(intent)` — not in 27 individual `viewModel.setPriority(...)`, `viewModel.openDatePicker(...)` etc.

```kotlin
// ❌ WRONG — Actions fans out to 27 individual VM methods
val actions = TaskDetailActions { action ->
    when (action) {
        is Action.OpenDatePicker -> viewModel.openDatePicker()    // VM method 1
        is Action.OpenTimePicker -> viewModel.openTimePicker()     // VM method 2
        is Action.SetPriority    -> viewModel.setPriority(...)      // VM method 3
        // 24 more...
    }
}

// ✅ CORRECT — Actions dispatches to one VM entry point
val actions = TaskDetailActions { intent ->
    when (intent) {
        is TaskDetailIntent.OpenSheet   -> activeSheet = intent.sheet   // routing
        is TaskDetailIntent.Domain      -> viewModel.onIntent(intent)     // single entry
    }
}
```

The anti-pattern defeats the purpose of the value class: it relocates the 27-branch `when` from the VM into the screen, creating a large file that is hard to navigate. Fix: push routing intents to the screen, domain intents to `onIntent`.

### `remember` without keys — killing the stability of the value class

The `value class` wrapper only gives **referential stability** if the lambda itself is stable. Creating it inside `remember { }` with a state dependency recreates it on every state change:

```kotlin
// ❌ WRONG — lambda recreated on every recomposition, all sections recompose
val actions = remember(ui) { TaskDetailActions { intent -> ... } }

// ✅ CORRECT — lambda is stable; sections only recompose on real intent emissions
val actions = remember { TaskDetailActions { intent -> ... } }
```

When in doubt: `remember` with no keys on a `value class` lambda is almost always correct (the class itself is the key). Adding state as a key defeats the purpose.

## Rule of Thumb

| Scenario | Recommendation |
|---|---|
| 1–3 callbacks | Raw lambdas |
| 4–9 callbacks, outgoing, used in 1 screen | Raw lambdas (but watch for growth) |
| 4–9 callbacks, outgoing, used in 2+ screens | Extract `value class XxxActions` |
| 10+ callbacks of any kind | Extract `value class XxxActions` + consider screen decomposition |
| 4+ incoming data params that are a cohesive unit | Data class |
| Mixing incoming + outgoing in one class | Never — split by direction |

### `enum class Action` vs `sealed class Action`

**Use `enum class Action` when:** all actions are simple toggles/state-changes with **no payload**.
Example — `TaskCardActions.Action` (4 simple actions: Toggle, Delete, Ai, Pin).

**Use `sealed class Action` when:** any action carries **payload** (an ID, a string, a domain object).
Example — `NotesActions.Action.CreateNote(title: String)`, `TaskDetailActions.Action.TitleChange(title: String)`.

```kotlin
// ✅ CORRECT — sealed class with data class for payload
sealed class Action {
    data object ToggleComplete : Action()           // no payload
    data class TitleChange(val title: String) : Action()  // payload
    data class NavigateToNote(val id: NoteId) : Action()   // payload
}

// ❌ WRONG — enum can't carry payload
enum class Action {
    ToggleComplete, TitleChange, NavigateToNote  // where does the title/id go?
}
```

The `sealed class` also enables exhaustive `when` with smart-cast — impossible with `enum`.

## Reference Implementation

- `NotesActions` at `feature/notes/components/NotesActions.kt` — `@JvmInline value class` with sealed `Action` hierarchy (10 callbacks)
- `NoteCardActions` at `feature/notes/components/NoteCardActions.kt` — `@JvmInline value class` with sealed `Action` hierarchy (4 callbacks)
- `TaskDetailActions` at `feature/tasks/components/TaskDetailActions.kt` — pairs with `TaskDetailIntent` sealed hierarchy; each helper calls `block(TaskDetailIntent.Domain.X)` and the screen dispatches to `viewModel.onIntent(intent)` (see `singularity-todo-vm-intent-pattern`)

## See Also

- `singularity-todo-vm-intent-pattern` — how the VM side of this pattern should look; the mistake of fanning Actions out to N individual VM methods is documented there
