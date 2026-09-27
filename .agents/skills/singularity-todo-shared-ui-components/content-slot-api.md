# Content Slot API

The `content:` seam that keeps a component from growing a long parameter list.

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

This is a project-style convention for visually separating "pure UI branch" from
"branch with side effects / state reads" in long Composable bodies. Use the native
`if (cond) { ... }` when the branch is short or when consistency with surrounding
Kotlin matters more than visual separation.

### What NOT to do

- **`enum class Action` for slots that carry data** — use `sealed class Action` with `data class` variants (see `TaskDetailActions`). An `enum` can't carry payload.
- **Nullable slot** (`content: @Composable () -> Unit? = null`) — use default `{}` instead. Nullable slots force null-checks at every call site.
- **`Modifier.apply { ... }`** — discouraged for `Modifier` chains: `apply` implies
  in-place mutation, but each modifier call returns a new immutable instance, so the
  chain reads as if it mutates but doesn't. Chain modifiers directly, or use
  `with(Modifier) { ... }` if you need a scope. (Note: this is a Compose convention,
  not a Kotlin language deprecation — `apply` itself is not deprecated in Kotlin 2.x.)
- **Positional slot names** (`slot1`, `slot2`, `leading`, `center`) — use Material 3 names (`body`, `trailing`).

### Reference implementations

- `EmptyState.kt` — `actions: @Composable ColumnScope.() -> Unit` with typealias and `If` helper
- `SettingsSection.kt` — `SettingsRow` with `trailing: @Composable RowScope.() -> Unit`
- `TaskCard.kt` — `body` + `trailing` slots with `RowScope`
- `BackTopAppBar.kt` — `actions` + `content` slots
- `TaskEditorContent.kt` — canonical slot API: `extraSections`, `bottomBar`, `menuItems`, `titleLeading` slots; `TaskEditorAttribute` with `key: Any` for recomposition stability; `TaskEditorMenuItem` as declarative list

See also:
- `singularity-todo-ui-event-vs-state` — full State vs Event dichotomy rationale and migration recipe
- `singularity-todo-pure-formatters` — when to extract formatters, three flavours, testing recipes

---