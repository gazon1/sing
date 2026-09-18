---
title: "Agenda UI — shared BackTopAppBar, DiscardChangesDialog, SettingsRadioRow adoption"
date: 2026-09-18
tags: [agenda, ui, shared-components]
status: accepted
---

## Context

The `SavedAgendaScreen` and `SavedAgendaListScreen` each had their own inline `Scaffold + TopAppBar` implementations for the back-navigation bar. `SavedAgendaScreen` additionally had a private `enum class ActiveDialog` and two inline `AlertDialog` composables. `AgendaSettingsScreen` had a 36-line private `RadioRow` composable. A 46-line `ReorderableConfig.kt` in the agenda feature had zero usages and was dead code.

## Decision

1. **Deleted** `feature/agenda/presentation/components/ReorderableConfig.kt` (dead code, 0 usages).

2. **Extended `BackTopAppBar`** (`core/ui/components/BackTopAppBar.kt`): added `colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)` to the `TopAppBar` call. This makes all existing `BackTopAppBar` users use surface-colored bars by default.

3. **Extended `DiscardChangesDialog`** (`core/ui/components/DiscardChangesDialog.kt`): added 5 optional parameters (`title`, `text`, `discardButtonText`, `keepEditingButtonText`) with backward-compatible defaults. All existing call sites continue to work unchanged.

4. **Created `SettingsRadioRow`** (`core/ui/components/SettingsRadioRow.kt`): a reusable radio-button settings row using the existing `SettingsRow` trailing slot pattern.

5. **Migrated `AgendaSettingsScreen`**: replaced the private `RadioRow` with the shared `SettingsRadioRow`. Removed unused imports (`clickable`, `Row`, `fillMaxWidth`, `padding`, `Alignment`, `Icon`, `Icons.Filled.Check`, `RadioButton`).

6. **Migrated `SavedAgendaScreen`**: replaced `Scaffold + TopAppBar` with `BackTopAppBar`; replaced `AlertDialog` ConfirmDiscard with `DiscardChangesDialog`; converted `enum class ActiveDialog` to `sealed interface ActiveDialog` with `data object` variants. `SavedAgendaListScreen` retains its `Scaffold` (has FAB; `BackTopAppBar` does not support FAB) and already had `containerColor = surface`.

## Rationale

Consistent `surface` color for top app bars eliminates the dark/light theme flash on navigation. The `sealed interface` gives exhaustive `when` on the JVM and better tooling. Shared `SettingsRadioRow` removes 36 lines from `AgendaSettingsScreen` and makes future reuse straightforward. Removing dead code (`ReorderableConfig`) reduces the codebase surface.

## Consequences

- `BackTopAppBar` now has `containerColor = surface` by default — all 6 existing callers benefit automatically.
- `DiscardChangesDialog` can be repurposed for any "are you sure?" confirmation (not just agenda) by passing custom text.
- `SavedAgendaListScreen` keeps its FAB by using `Scaffold` directly (not `BackTopAppBar` which lacks FAB support).
- `sealed interface ActiveDialog` enables exhaustive `when` on JVM.
- All 633 JVM tests pass after migration.

## Links

- `core/ui/components/BackTopAppBar.kt` (changed)
- `core/ui/components/DiscardChangesDialog.kt` (changed)
- `core/ui/components/SettingsRadioRow.kt` (new)
- `feature/settings/screens/AgendaSettingsScreen.kt` (changed)
- `feature/agenda/presentation/screen/SavedAgendaScreen.kt` (changed)
- `feature/agenda/presentation/components/ReorderableConfig.kt` (deleted)
