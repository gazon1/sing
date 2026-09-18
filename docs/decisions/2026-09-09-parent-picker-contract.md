---
title: "ParentPickerSheet receives ParentOption DTO, not Project entity"
date: 2026-09-09
tags: [ui-contract, projects, picker, architecture]
status: accepted
---

## Context

`ParentPickerSheet` was called with a raw `ProjectId?` (the current parent's ID) and had a `// TODO: list all root projects` comment. The sheet needed to render a list of options to pick from, but had no access to the list of available parent projects.

The question was: should the sheet receive `List<Project>` directly (domain entity leak to UI), or a dedicated DTO?

## Decision

The sheet receives `List<ParentOption>`, a dedicated DTO defined alongside `ProjectDetailUi`:

```kotlin
// ProjectDetailUi.kt
data class ParentOption(
    val id: ProjectId,
    val name: String,
    val isCurrent: Boolean,  // currently selected option
)

// Usage in screen
val parentOptions by viewModel.parentOptionsFlow.collectAsStateWithLifecycle()
...
is ActiveSheet.PickParent -> ParentPickerSheet(
    options = parentOptions,  // List<ParentOption>, not List<Project>
    onPick = { parentId -> viewModel.updateParent(parentId); sheetState = null },
    onDismiss = { sheetState = null },
)
```

`ParentOption` is derived in the ViewModel from `projectFlow + projectRepo.watchProjects()`:

```kotlin
// ProjectDetailViewModel
private val parentOptionsFlow: StateFlow<List<ParentOption>> = combine(
    projectFlow,
    projectRepo.watchProjects(currentUser.scopedUserId.value),
) { project, allProjects ->
    if (project == null) emptyList()
    else allProjects
        .filter { it.id != project.id && it.parentId == null && !it.isDeleted }
        .map { ParentOption(it.id, it.name, it.id == project.parentId) }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

## Rationale

**Why not `List<Project>`:**
- Exposes deleted projects, sub-projects, and the current project (cycle) to the UI layer — UI must know filtering rules
- The UI shouldn't need to know that `parentId == null` means "root" or that `isDeleted` must be filtered
- If the domain rules change, every UI consumer would break

**Why `ParentOption` is correct:**
- Single source of truth: the ViewModel owns the filtering logic
- UI receives exactly what it needs: `id`, `name`, `isCurrent`
- Cycle prevention (`it.id != project.id`) lives in one place
- `isCurrent` replaces a manual `currentParentId` parameter

## Consequences

- `ParentPickerSheet` signature: `options: List<ParentOption>`, NOT `currentParentId: ProjectId?`
- `ParentOption` is a `@JvmInline value class` candidate if it grows beyond 3 fields (currently 3 — plain data class is fine)
- Parent options are reactive (`StateFlow`) — picker updates automatically when projects change
- The "None (root)" option is rendered as a `TextButton` above the `LazyColumn`, not as part of `options`
