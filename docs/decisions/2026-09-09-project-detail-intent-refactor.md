---
title: "ProjectDetailViewModel/Screen: sealed Intent + onIntent dispatcher pattern"
date: 2026-09-09
tags: [vm, intent, refactor, koin]
status: accepted
---

## Context

Post-audit of Phase 4 refactoring (`TaskDetailViewModel` → sealed `TaskDetailIntent`) revealed that `ProjectDetailViewModel` had the same god-class smell: 11 public methods (`toggleHideCompleted`, `updateName`, `updateDescription`, `updateColor`, `updateIcon`, `updateParent`, `updateDueDate`, `toggleArchive`, `delete`, `moveTaskToProject`, `createTask`) all mutating a shared `StateFlow<ProjectDetailUiState>`.

`ProjectDetailScreen` passed 8 callbacks into each of 3 sections — 24 individual lambda parameters — with routing logic (which sheet to open) scattered across the screen body.

`ProjectDetailUiEvent.NavigateToTasks` was emitted as a VM event but represented screen routing, violating the "screens own routing state" rule established in the `ui-event-vs-state` skill.

## Idea

Apply the same `TaskDetailIntent` pattern used in Phase 4:
1. `sealed interface ProjectDetailIntent` with `Routing` (screen) vs `Domain` (VM) sub-hierarchies
2. Single `onIntent(intent: Domain)` dispatcher
3. `@JvmInline value class ProjectDetailActions` for callback packing
4. Remove `NavigateToTasks` from events → routing Intent
5. Add `_latestProject` TOCTOU cache (mirrors TaskDetail fix)
6. Replace manual `Job`-based debounce with `MutableStateFlow` drafts + `debounce()` collectors in `init{}`
7. `mutate{}` helper replaces 7 near-identical setter bodies

## Decision

`ProjectDetailViewModel` now has:
- **`ProjectDetailIntent`** — `Routing.NavigateTo*` (screen) + `Domain.*` (VM)
- **`onIntent(intent: Domain)`** — single dispatcher replacing 11 methods
- **`_latestProject` StateFlow cache** — TOCTOU guard for all mutations
- **`nameDraft` / `descriptionDraft` MutableStateFlow** — debounce via `init{}` collectors
- **`mutate{}` private helper** — `updateProject(id, transform)` + `_lastEditedAt` + silent failure

`ProjectDetailScreen` now has:
- **`ProjectDetailActions`** — single callback parameter per section
- **Routing via `remember { ProjectDetailActions { ... } }`** — `OpenColorSheet`, `OpenIconSheet`, etc. directly set `activeSheet`; no VM event round-trip
- **Sheet openers as routing intents** — `ActiveSheet.PickColor`, `ActiveSheet.ConfirmDelete`, etc. are `Routing` intents, not VM events
- **`NavigateToTasks` removed from `ProjectDetailUiEvent`** — becomes `Routing.NavigateToTasks(projectId)` handled directly by screen

## Rationale

Same rationale as Phase 4's TaskDetail decision: exhaustiveness checking on the `when(intent)` branch catches missing cases at compile time. Adding a new mutation = one case in the sealed hierarchy, not one new public method + one new flow.

Routing intents (sheet openers) belong to the screen: they're UI state transitions, not domain operations. The previous pattern of `viewModel.toggleArchive()` → `Saved event` → `CollectEvents → set activeSheet` created an unnecessary event round-trip.

`_latestProject` cache: `ProjectDetailViewModel.state` combines `projectFlow` + 4 other flows; without the cache, debounce collectors read from a potentially-stale upstream snapshot.

## Consequences

- **`ProjectDetailIntent`** is the canonical list of all project mutations — adding a new field mutation = one `Domain` case
- **`ProjectDetailUiEvent`** now has only 2 cases: `NavigateBack` (post-delete) and `ShowError`
- **`NavigateToTasks`** is no longer a VM event — screen handles it as routing
- **`toggleArchive`** no longer emits `Saved` — `lastEditedAt` drives "Saved X ago" UI via the `mutate{}` helper
- **`createTask` and `moveTaskToProject`** remain in VM (require repository writes)
- **No pure reducer needed** — `ProjectDetailViewModel` is write-through like `TaskDetailViewModel`

## Links

- Previous decision: `2026-09-09-task-detail-intent-refactor.md`
- VM pattern: `singularity-todo-vm-intent-pattern` skill
- Routing vs domain: `singularity-todo-ui-event-vs-state` skill
- Callback packing: `singularity-todo-task-callback-groups` skill
