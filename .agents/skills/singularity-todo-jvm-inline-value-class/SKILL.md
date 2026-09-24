---
name: singularity-todo-jvm-inline-value-class
description: JvmInline value class patterns for Singularity Todo. Covers when to use @JvmInline value class for ID wrappers (TaskId, ProjectId, TagId) vs plain @JvmInline for callback bundles (TaskDetailActions, ProjectDetailActions), why avoid inline value classes for sealed hierarchies with many variants, and the stability contract (remember without keys).
---

# JvmInline Value Class Patterns

## The Two Uses in This Project

### 1. ID Wrappers — `@JvmInline value class TaskId`

```kotlin
@JvmInline
value class TaskId private constructor(val value: String) {
    companion object {
        fun fromString(s: String): TaskId = TaskId(s)
        fun generate(): TaskId = TaskId(nextId())
    }
}
```

**Rules:**
- `private constructor` forces construction via `fromString` / `generate` — controls instantiation
- `value` is the raw String — never expose a `val id: TaskId` field alongside `val id: String` in the same class
- `@Serializable` required when used in NavGraph routes (`@Serializable data class OpenTask(val taskId: String)`)
- Mappers: `TaskId.fromString(entity.id)`, `entity.id = id.value`

### 2. Callback Bundles — `@JvmInline value class XxxActions`

```kotlin
@JvmInline
value class ProjectDetailActions(private val block: (ProjectDetailIntent) -> Unit) {
    fun onOpenColorSheet() = block(ProjectDetailIntent.Routing.OpenColorSheet)
    fun onOpenChildrenSheet() = block(ProjectDetailIntent.Routing.OpenChildrenSheet)
    fun onNavigateToChild(projectId: ProjectId) = block(ProjectDetailIntent.Routing.NavigateToChild(projectId))
    // ... 20+ methods

    companion object {
        internal val Empty = ProjectDetailActions {}  // internal — no production use
    }
}
```

**Rules:**
- The single `block: (T) -> Unit` parameter is the target of all delegation
- Each method calls `block(...)` with the appropriate intent variant
- `Empty` is `internal` — forces production callers to construct a real dispatcher
- **Never** use `@JvmInline value class` for sealed hierarchies with >15 variants — each `.invoke()` call on a sealed subtype has JVM overhead; use a plain object instead

### When NOT to Use `@JvmInline value class`

```kotlin
// ❌ WRONG — sealed hierarchy with 20+ variants wrapped in inline class
@JvmInline
value class TaskDetailActions(private val block: (TaskDetailIntent) -> Unit) {
    fun onOpenSheet(s: TaskEditorSheet) = block(TaskDetailIntent.OpenSheet(s))
    // 24 more methods...
}

// ✅ CORRECT — plain object when sealed hierarchy is large
object TaskDetailActions {
    operator fun invoke(block: (TaskDetailIntent) -> Unit) = block
    fun onOpenSheet(block: (TaskEditorSheet) -> Unit) = ...
}
```

## Stability Contract

The `value class` wrapper provides **referential stability** only if the underlying lambda is stable:

```kotlin
// ❌ WRONG — lambda recreated on every recomposition
val actions = remember(ui) { TaskDetailActions { intent -> ... } }

// ✅ CORRECT — lambda is stable; sections recompose only on real intent emissions
val actions = remember { TaskDetailActions { intent -> ... } }
```

`remember` with no keys on a `value class` lambda is almost always correct — the class itself is the key.

## Remember Without Keys — The Correct Pattern

```kotlin
// In a Composable (e.g. ProjectDetailContent):
val actions = remember {
    ProjectDetailActions { intent ->
        when (intent) {
            is ProjectDetailIntent.Routing.OpenColorSheet -> sheets.show(ActiveSheet.PickColor)
            is ProjectDetailIntent.Routing.NavigateToChild -> nav.openDetail(intent.projectId)
            is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
        }
    }
}
```

The `when` is evaluated lazily (on first call), not on every recomposition. This is the correct pattern for ALL `*Actions` value classes.

## Related Skills

| Skill | What it contributes |
|---|---|
| `singularity-todo-task-callback-groups` | Decision tree: raw lambdas vs value class actions vs data class |
| `singularity-todo-vm-intent-pattern` | Sealed Intent hierarchy used inside the `block` |
| `singularity-todo-sheet-extraction` | Routing intents (`NavigateToChild`) that flow through the `*Actions` block |
