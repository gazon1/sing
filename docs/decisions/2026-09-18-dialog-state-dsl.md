---
title: "MR8: DialogState<T> — state hoisting for dialog overlays"
date: 2026-09-18
tags: [ui, state-hoisting, refactor]
status: accepted
---

## Context

Screen composables across the codebase managed dialog/sheet visibility using ad-hoc `var x by remember { mutableStateOf<X?>(null) }` patterns. When a screen had multiple dialogs (as in `SavedAgendaScreen` with ConfirmDelete, ConfirmDiscard, and AddSection), each required a separate state variable, and the conditional rendering used verbose `if (activeDialog == X)` chains.

## Decision

Created `core/ui/components/DialogState.kt` with:

- **`DialogState<T>`** — a thin state holder class: `active: T?` property, `show(dialog: T)`, `dismiss()`.
- **`rememberDialogState<T>()`** — composable factory scoped to the current composable.

The `if (dialogs.active == X)` conditional pattern replaces `var x by remember { mutableStateOf<X?>(null) }` + `if (x == X)`.

Migrated `SavedAgendaScreen` (3 dialogs) to use `DialogState<ActiveDialog>`.

## Rationale

- `DialogState` encapsulates the common "one active dialog at a time" pattern without adding DSL complexity.
- `dialogs.active == X` is readable and explicit — no extension function or operator overloading needed.
- `show()` / `dismiss()` are clearer than `x = X` / `x = null`.
- State is hoisted to a named variable (`dialogs`) making it easy to pass to helper composables.

## Consequences

- `DialogState` is the canonical state holder for single-dialog overlays. Use directly with `if (dialogs.active == X) { ... }`.
- `ProjectDetailScreen` (10 dialogs) remains a future migration candidate — its data-class variants (`PickParent(current: ProjectId?)`) require additional consideration for smart-cast ergonomics.
- `SavedAgendaScreen` now uses `dialogs.show(X)` and `dialogs.dismiss()` instead of `activeDialog = X` and `activeDialog = null`.

## Links

- Commit: MR8 — DialogState<T> state hoisting for dialog overlays
- New file: `core/ui/components/DialogState.kt`
- Modified file: `feature/agenda/presentation/screen/SavedAgendaScreen.kt`
