---
title: Content slot API design rules
date: 2026-09-09
tags: [architecture, compose, ui]
deciders: [Singularity Developer]
status: accepted
---

# Content Slot API Design Rules

## Context

As the shared UI component library grew, three distinct patterns emerged for
passing behaviour into a Composable:

1. **Plain callbacks** — `onClick: () -> Unit`
2. **Content slots** — `content: @Composable () -> Unit`
3. **Packed actions** — `actions: XxxActions` (value class with sealed Action)

The codebase lacked a documented convention for choosing between these patterns,
leading to inconsistent signatures (e.g., `trailing: @Composable (RowScope.() -> Unit)?`
vs `leading: @Composable RowScope.() -> Unit` vs positional slot parameters).

## Decision

### Rule 1 — Callback count drives the pattern

| Scenario | Pattern to use |
|---|---|
| 1–3 callbacks | Plain lambda parameters |
| 4–9 callbacks, one screen | Plain lambda parameters |
| 4–9 callbacks, 2+ screens | `@JvmInline value class XxxActions` |
| 10+ callbacks | `@JvmInline value class XxxActions` |
| Arbitrary composable body (no data in) | Content slot |

**Rationale:** `value class XxxActions` adds indirection — use only when the
callback surface area is large enough or shared across screens.

### Rule 2 — Use `sealed class Action`, not `enum class Action`

When packing callbacks into a `value class`, always use `sealed class Action`:

```kotlin
// ✅ CORRECT — sealed class with data class for payload
@JvmInline
value class NotesActions(val block: (Action) -> Unit) {
    sealed class Action {
        data object ExitSelection : Action()
        data class NavigateToNote(val id: NoteId) : Action()  // payload
        data class CreateNote(val title: String) : Action()   // payload
    }
    fun onExitSelection() = block(Action.ExitSelection)
    fun onNavigateToNote(id: NoteId) = block(Action.NavigateToNote(id))
    fun onCreateNote(title: String) = block(Action.CreateNote(title))
    companion object { val Empty = NotesActions {} }
}
```

```kotlin
// ❌ WRONG — enum can't carry payload
enum class Action {
    ExitSelection,
    NavigateToNote,  // where does the NoteId go?
    CreateNote,      // where does the title go?
}
```

`sealed class Action` enables:
- **Exhaustive `when`** with smart-cast
- **Type-safe payloads** via `data class`
- Zero overhead (`@JvmInline`)

### Rule 3 — Material 3 slot naming

Use **named slots** following Material 3 conventions:

| Slot name | Meaning |
|---|---|
| `body` | Main content area |
| `trailing` | Right-side actions row |
| `header` | Top section (instead of `top`) |

**Never use:** `leading`, `center`, `slot1`, `slot2`.

### Rule 4 — Receiver scope only when needed

Use `RowScope.() -> Unit` or `ColumnScope.() -> Unit` only when the
caller needs scope functions (e.g., `Modifier.weight()`):

```kotlin
// ✅ When caller needs weight()
fun TaskCard(
    body: @Composable RowScope.() -> Unit = { DefaultBody(task) },
    trailing: @Composable RowScope.() -> Unit = { DefaultTrailing(actions) },
)

// ✅ Plain lambda when scope not needed
fun EmptyState(
    actions: @Composable ColumnScope.() -> Unit = {},
)
```

### Rule 5 — Default empty slot for backward compatibility

**Always** provide a default value of `{}` for optional slots:

```kotlin
@Composable
fun EmptyState(
    title: String,
    actions: @Composable ColumnScope.() -> Unit = {},  // ✅ default empty
)
```

This avoids nullable slots (`content: @Composable () -> Unit? = null`) which
force null-checks at every call site.

### Rule 6 — `typealias` for long slot signatures

When a slot type is repeated across components, use a `typealias`:

```kotlin
typealias EmptyStateActions = @Composable ColumnScope.() -> Unit
typealias SettingsRowTrailing = @Composable RowScope.() -> Unit
```

## Consequences

### Positive
- Consistent API across all shared components
- Easier to extend cards and editors without breaking call sites
- Type-safe actions via `sealed class Action` with exhaustive `when`

### Negative
- `value class XxxActions` indirection — harder to read at first glance
- Migration from plain lambdas requires updating call sites

## References

- `TaskDetailActions` — canonical example (`feature/tasks/components/TaskDetailActions.kt`)
- `EmptyState` — `actions: @Composable ColumnScope.() -> Unit` with typealias
- `SettingsSection` — `SettingsRow` with `trailing: @Composable RowScope.() -> Unit`
- `TaskCard` — `body` + `trailing` Material 3 slot pattern
- `singularity-todo-shared-ui-components` skill — Part 6
- `singularity-todo-task-callback-groups` skill — decision tree
