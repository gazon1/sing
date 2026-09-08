---
name: singularity-todo-cross-feature-navigation
description: How to navigate from one feature's detail screen to another feature's screen (e.g., TaskDetailScreen → ProjectDetailScreen, TaskDetailScreen → NoteEditorScreen). Documents the 3 options for chip-based navigation (IconButton vs overflow vs long-press), the generic-widget contract for MetaChipsRow, and the cross-feature navigation rules for this KMP project.
---

# Cross-Feature Navigation Pattern

## The Problem

When a `TaskDetailScreen` shows a project chip and the user taps it, where should they go?

Options:
1. **IconButton adjacent to chip** — `[📁 Work] →` — explicit, discoverable, clean
2. **Overflow menu item** — `⋮ → Open project` — discoverable but buried
3. **`combinedClickable` on chip (long-press = pick, short-press = navigate)** — hidden, conflicts with multi-select

## The Rule

**Option 1 (IconButton) is the only correct choice for cross-feature navigation from a chip.**

**Never use `combinedClickable` on a chip for navigation** — long-press is the universal "enter selection mode" gesture in this app (per `singularity-todo-multi-select`). Repurposing it breaks future multi-select on any screen that uses chips.

## The Generic-Widget Contract

`MetaChipsRow` in `core/ui/components/` is a **generic widget** — it renders chips for Date, Priority, Project, Tags without knowing about any specific feature. Adding `onLongClick = { openProject() }` to the project chip **inside the generic widget** violates this contract.

The correct approach:

```kotlin
// ❌ WRONG — leaks feature-specific behaviour into the generic widget
@Composable
fun MetaChipsRow(
    // ... other params
    onProjectLongClick: (() -> Unit)? = null, // DO NOT ADD THIS
) {
    // ...
    FilterChip(
        onClick = { /* open picker */ },
        onLongClick = onProjectLongClick, // BREAKS generic contract
    )
}
```

```kotlin
// ✅ CORRECT — MetaChipsRow stays generic; feature screen adds the affordance
@Composable
fun TaskDetailContent(
    // ...
) {
    val (projectChip, onProjectClick) = remember(state.project) { state.project to {} }
    
    MetaChipsRow(
        date = state.dueDate,
        priority = state.priority,
        project = projectChip,
        // ... no onProjectClick needed here
    )
    
    // IconButton adjacent to the chip's row — feature-specific, not in the generic widget
    Row(verticalAlignment = CenterVertically) {
        // MetaChipsRow renders here
        IconButton(
            onClick = { onNavigateToProject(state.projectId) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ChevronRight,
                contentDescription = "Open project"
            )
        }
    }
}
```

## Option 2: Overflow Menu Item

Overflow menu navigation is acceptable **as a secondary affordance** — when the user has already opened the `MoreVert` menu. Do NOT use overflow as the **only** navigation path (users won't discover it).

```kotlin
// In the screen's overflow / MoreVert menu:
DropdownMenuItem(
    text = { Text("Open project") },
    onClick = { onNavigateToProject(projectId) },
    leadingIcon = {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
    },
    enabled = projectId != null,
)
```

## Option 3: `combinedClickable` — DO NOT USE

```kotlin
// ❌ WRONG — breaks multi-select gesture contract
FilterChip(
    modifier = Modifier.combinedClickable(
        onClick = { onNavigateToProject(projectId) },
        onLongClick = { showProjectPicker() } // CONFLICTS with multi-select!
    ),
    // ...
)
```

## The Cross-Feature Navigation Checklist

When adding a navigation link from screen A to screen B:

- [ ] Is the navigation target a **different feature** (not a sibling screen in the same feature)?
- [ ] Does the generic widget (`MetaChipsRow`, `FilterChipsRow`, etc.) stay free of feature-specific callbacks?
- [ ] Is the affordance **discoverable** (visible icon or menu item)?
- [ ] Is the callback passed as a **composable-level lambda**, not baked into the generic widget?
- [ ] Is `enabled = false` when the reference is null (e.g., task has no project)?

## Cross-Feature Read Dependencies (Permitted)

It is **acceptable** for `ProjectDetailViewModel` to inject `TaskRepository` for reading task lists filtered by project. This is a **unidirectional read dependency**:

```
feature/tasks/ → feature/projects/ (TaskDetailViewModel reads ProjectsRepository)
feature/projects/ → feature/tasks/ (ProjectDetailViewModel reads TaskRepository)
```

This is NOT a circular dependency — both are read-only. Write operations stay within their feature boundary.

**Do NOT create a shared port interface** (`ProjectTaskCountPort`) to decouple this — a 3-line JOIN query doesn't need an abstraction layer. See `singularity-todo-relational-counts`.

## Worked Examples

### TaskDetailScreen → ProjectDetailScreen

```kotlin
// TaskDetailScreen.kt
@Composable
fun TaskDetailContent(
    state: TaskDetailUi,
    onNavigateToProject: (ProjectId) -> Unit,
    onNavigateBack: () -> Unit,
    // ...
) {
    // The IconButton is in TaskDetailContent (feature-specific),
    // NOT inside MetaChipsRow (generic)
    Row(verticalAlignment = Alignment.CenterVertically) {
        MetaChipsRow(
            dueDate = state.dueDate,
            priority = state.priority,
            project = state.projectName,
            onDueDateClick = { sheet = ActiveSheet.Date },
            onPriorityClick = { sheet = ActiveSheet.Priority },
            onProjectClick = { sheet = ActiveSheet.Project },
        )
        // Navigation affordance — adjacent to chip, not on it:
        if (state.projectId != null) {
            IconButton(
                onClick = { onNavigateToProject(state.projectId) },
                modifier = Modifier.size(20.dp).padding(start = 4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ChevronRight,
                    contentDescription = "Open project",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Overflow menu addition:
DropdownMenuItem(
    text = { Text("Open project") },
    onClick = { onNavigateToProject(state.projectId) },
    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
    enabled = state.projectId != null,
)
```

### AppNavHost wiring

```kotlin
// AppNavHost.kt
composable<AppDestination.TaskDetail> { backStackEntry ->
    val route = backStackEntry.toRoute<AppDestination.TaskDetail>()
    TaskDetailScreen(
        taskId = TaskId.fromString(route.taskId),
        onNavigateBack = { navigator.popBackStack() },
        onNavigateToProject = { projectId ->
            navigator.navigate(AppDestination.ProjectDetail(projectId.value))
        },
    )
}
```

## Anti-Patterns

1. **`combinedClickable` on chip for navigation** — breaks multi-select.
2. **Feature-specific callback in generic widget** — `MetaChipsRow` must stay generic.
3. **No disabled state** — always `enabled = false` when `projectId == null`.
4. **Navigation without back-stack** — use `navigator.navigate(dest)` (push), not `navigateTopLevel` (replaces tab).
