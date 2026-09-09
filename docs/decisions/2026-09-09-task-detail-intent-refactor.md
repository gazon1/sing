---
title: "TaskDetailViewModel: sealed Intent + single onIntent dispatcher"
date: 2026-09-09
tags: [architecture, viewmodel, compose, tasks]
deciders: [Singularity Developer]
status: accepted
---

# TaskDetailViewModel: sealed Intent + single onIntent dispatcher

## Context

`TaskDetailViewModel` had **37 public methods** — the highest count of any ViewModel in the project. Five siblings (`TaskEditorViewModel`, `ChatViewModel`, `ProjectEditorViewModel`, `ChecklistEditorViewModel`, `SettingsIntent`) already used the `sealed Intent` + `onIntent` pattern documented in AGENTS.md, but `TaskDetailViewModel` had drifted away from it. The file also mixed two mechanisms for opening sheets: `ConfirmDelete`/`ConfirmArchive` set `activeSheet` directly, while the other nine pickers went through `VM → TaskDetailUiEvent → toActiveSheet() → activeSheet` — a round-trip that violated the project's own rule ("screens own routing state, VMs own domain logic"). The 13 `setX(current, value)` methods read `current` from UI state snapshots, not from the VM's `_latestTask` cache, silently overwriting concurrent remote edits.

## Idea

Bring `TaskDetailViewModel` in line with the house pattern:

- **Sealed `TaskDetailIntent`** replaces the three parallel hierarchies (`TaskDetailUiEvent` sheet-triggers, `ActiveSheet` routing, and 27 `TaskDetailActions` variants). Routing variants (`OpenSheet`, `Navigate*`, `Attachment`) stay in the screen; domain variants (`ToggleComplete`, `SetPriority`, `Delete`, …) go to the VM.
- **Single `fun onIntent(intent: TaskDetailIntent.Domain)`** replaces 37 public methods.
- **Private `mutate()` helper** eliminates the 13 near-identical `scope.launch { updateTask(current.copy(X = value)) }` bodies.
- All mutations read `_latestTask`, never a UI snapshot — fixing the TOCTOU class of bugs.

Three alternatives were considered:
1. **Keep methods-per-action** (status quo) — rejected: 37 methods with no compile-time enforcement of routing/domain boundary.
2. **Extract separate VMs** for checklist / reminders / subtasks — rejected: creates cross-VM consistency problems for a single-entity screen.
3. **MVI with pure reducer** (like `TaskEditorUiState.reduce`) — rejected: `TaskDetailUi` is entirely derived from Room flows, no local draft state exists; a reducer would be cargo cult.

## Decision

- Created `TaskDetailIntent.kt` with a `sealed interface` hierarchy:
  - `TaskDetailIntent.OpenSheet(ActiveSheet)`, `NavigateToTask`, `NavigateToProject` — routing, handled by screen.
  - `TaskDetailIntent.Attachment` — screen-only (delegates to `AttachmentsViewModel`).
  - `TaskDetailIntent.Domain` — all domain operations, single `onIntent(Domain)` entry in VM.
- Removed 10 `TaskDetailUiEvent` sheet-trigger variants and `toActiveSheet()` mapper.
- VM now has 3 public methods: `start`, `onTitleChange`, `onIntent`. `_latestTask` is the single source of truth for all mutations.
- Screen sets `activeSheet` directly; routing intent never reaches the VM.

## Rationale

The decisive argument is **internal consistency**: a developer reading `TaskEditorViewModel` (1 method) and then `TaskDetailViewModel` (37 methods) sees two different architectures for two screens of the same entity. The `sealed Intent` pattern is already the project standard — `TaskDetailViewModel` had drifted, not chosen a different valid approach. Fixing the TOCTOU bug (all mutations now use `_latestTask`) was a bonus. The `mutate {}` helper reduces boilerplate without changing semantics.

## Consequences

- `TaskDetailScreen.kt`: `when (action)` on 27 branches → `when (intent)` on 6 branches. Routing now uniform (all `activeSheet = …`).
- `TaskDetailViewModel.kt`: 450 → ~270 lines, 37 public methods → 3 (`start`, `onTitleChange`, `onIntent`).
- `TaskDetailUiEvent.kt`: 34 → ~18 lines (10 sheet-triggers removed).
- `ActiveSheet.kt`: 35 → ~15 lines (`toActiveSheet()` removed).
- New file `TaskDetailIntent.kt` (~120 lines).
- `TaskDetailViewModelTest`: updated 5 tests to call `vm.onIntent(Domain.X)` instead of `vm.setX(task, value)`.
- `TasksFormatters.kt`: added `dueChipColors` formatter and `parseDueTime` utility.
- `TaskDetailContent` is now `internal` (stateless, previewable without Koin).
- **Known limitation**: 10 constructor parameters remain; next candidate for `TaskDetailDeps` by analogy with `TaskEditorDeps`.

## Links

- AGENTS.md — ViewModel contract (`StateFlow<SealedUiState>`, `sealed Intent`, `viewModelScope`)
- `TaskEditorViewModel.kt` — canonical reference for the pattern
- `docs/decisions/2026-09-09-content-slot-pattern.md` — callback packing conventions
- `.agents/skills/singularity-todo-vm-intent-pattern/` — new skill documenting the pattern
