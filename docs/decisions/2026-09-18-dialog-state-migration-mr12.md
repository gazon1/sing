---
title: "TaskEditorSheet and ProjectDetailScreen migrate to DialogState<T>"
date: 2026-09-18
tags: [ui-components, state-hoisting, dialogs]
---

## Context

`TaskCreateScreen`, `TaskEditorContent`, and `ProjectDetailScreen` each held sheet visibility in `var sheetState by remember { mutableStateOf<T?>(null) }`. Dismissal required explicit `sheetState = null` in every branch. The `DialogState<T>` pattern (active property + show/dismiss methods) was already proven in MR8 (SavedAgendaScreen).

## Decision

Migrate all three screens to `rememberDialogState<T>()`:

- `TaskCreateScreen`: `activeSheet mutableStateOf<TaskEditorSheet?>` → `sheets = rememberDialogState<TaskEditorSheet>()`
- `TaskEditorContent`: same migration, sheet opens via fallback `sheets.show(TaskEditorSheet.Priority)` when caller doesn't provide `onPriorityClick`
- `ProjectDetailScreen`: 10-variant `ActiveSheet` sealed interface migrated — including `PickParent(val current: ProjectId?)` data class

## Rationale

`DialogState` centralises `show`/`dismiss` calls and makes the `null` state explicit as `active`. The data-class variant `PickParent` works correctly — `sheets.active` smart-casts to `ActiveSheet.PickParent` within the `when` branch, and `current` is accessed directly. Payload is retained through `sheets.show(ActiveSheet.PickParent(intent.currentParentId))`.

## Consequences

- `DialogState<T>` is the **only** approved pattern for bottom-sheet/dialog state in composables. `mutableStateOf<T?>` for sheet state is now deprecated.
- All 13 sheet-holder screens in the codebase should migrate; remaining are ProjectPickerSheet, TagPickerSheet, ParentPickerSheet (deferred to MR14.b — require VM create-flow rework).
- `Show` extension on `DialogState` is **not used** — prefer `if (dialogs.active == X)` pattern for conditional rendering.
- **Never** use `mutableStateOf<X?>` for sheet/dialog state — always use `rememberDialogState()`.
