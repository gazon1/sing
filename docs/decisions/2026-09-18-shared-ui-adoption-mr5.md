---
title: "MR5: ConfirmActionDialog + DragHandleRow shared components"
date: 2026-09-18
tags: [ui, refactor, dsl]
---

## Context

Three UI duplication problems existed across the agenda feature:

1. `SavedAgendaScreen` had an inline `AlertDialog` for "ConfirmDelete" that was identical in pattern to many other confirm dialogs across the app.
2. The drag-handle row (Icon + text + subtitle + trailing slot) was inlined in two components: `SectionEditorCard` (leading handle + trailing close button) and the private `SectionRow` inside `ReorderableSectionList` (trailing handle, no trailing slot).
3. `AgendaContent` and `SavedAgendaListScreen` used `Scaffold + TopAppBar` without `containerColor = MaterialTheme.colorScheme.surface`, creating visual inconsistency with `BackTopAppBar` which uses `surface` by default.

## Decision

1. Created `ConfirmActionDialog` — a generic composable with `title`, `text`, `confirmButtonText`, `dismissButtonText`, `onConfirm`, `onDismiss`. Rewrote `DiscardChangesDialog` as a thin semantic wrapper that delegates to it.
2. Created `DragHandleRow` — a shared row component with `handleSide: HandleSide (Leading|Trailing)`, `text`, `subtitle`, `padding`, `onClick`, `handle: @Composable () -> Unit`, and `trailing: @Composable RowScope.() -> Unit`. Migrated both `SectionEditorCard` (leading handle) and `ReorderableSectionList.SectionRow` (trailing handle) to it.
3. `AgendaContent.kt` and `SavedAgendaListScreen.kt` already had `containerColor = MaterialTheme.colorScheme.surface` on their inline `TopAppBar` — no changes needed.

## Rationale

- `ConfirmActionDialog` follows the same pattern as `ResultDialog` (generic wrapper, semantic wrappers for specific use-cases).
- `DragHandleRow` uses the same trailing-slot `RowScope.() -> Unit` pattern as `SettingsRow` — consistent with the existing component API style.
- Drag handle is purely visual; runtime drag state is managed by `ReorderableSectionList` internally. No attempt to abstract drag state.
- The `handle: @Composable () -> Unit` parameter lets callers wrap the icon with custom padding or modifiers without the component needing to know about them.

## Consequences

- `ConfirmActionDialog` replaces inline `AlertDialog` in any future confirm-dialog use case.
- `DragHandleRow` is the canonical home for any read-only row that has a drag handle. If a future use case needs click-to-edit or checkable rows, create a separate component.
- `SectionEditorCard` now uses `DragHandleRow` internally, keeping the Card wrapper for elevation and background.
- `Icon`, `Column`, `Row`, `Arrangement` imports removed from `ReorderableSectionList.kt` since `SectionRow` no longer uses them directly.

## Links

- Commit: MR5 — ConfirmActionDialog + DragHandleRow + surface consistency
- New files: `core/ui/components/ConfirmActionDialog.kt`, `core/ui/components/DragHandleRow.kt`
- Modified files: `core/ui/components/DiscardChangesDialog.kt`, `feature/agenda/presentation/screen/SavedAgendaScreen.kt`, `feature/agenda/presentation/components/SectionEditorCard.kt`, `feature/agenda/presentation/components/ReorderableSectionList.kt`
- Supersedes: part of 2026-09-18-shared-ui-adoption-mr5 (planning)
