---
title: "MR6: ListPickerSheet<T> + DSL for agenda pickers"
date: 2026-09-18
tags: [ui, dsl, refactor]
---

## Context

Two bottom sheets in the agenda feature had identical structure — a `ModalBottomSheet` containing a title, a lazy column of clickable rows, and dismiss handling — yet were implemented as separate inline implementations:

1. `SavedAgendaScreen` `AddSection` sheet: 7 predefined `SectionTemplate` items (label + Selector).
2. `SavedAgendaListScreen` `ProfilePickerSheet`: profile list with emoji, name, and "Default" badge.

Both follow the pattern: `title + scrollable list of labeled items + onSelect callback`.

## Decision

Created two new files in `core/ui/components/`:

- **`ListPickerSheet.kt`** — data class `ListPickerItem<T>` with `key, label, subtitle, selected, enabled, leading: @Composable (RowScope.() -> Unit)` and the renderable `ListPickerSheet<T>` composable using `LazyColumn`.
- **`ListPickerDsl.kt`** — `@DslMarker`, `ListPickerScope<T>` receiver class with `item()` method, and a trailing-lambda overload of `ListPickerSheet<T>`.

Migrated both agenda sheets to the DSL. `SavedAgendaScreen` now uses the trailing-lambda form; `SavedAgendaListScreen.ProfilePickerSheet` uses the data-class form with a mapped `ListPickerItem` list.

Removed the now-unused `private data class SectionTemplate` and `SectionTemplates` list from `SavedAgendaScreen`.

## Rationale

- The two-line `item("label", key)` DSL call is cleaner than defining a data class + building a list for simple cases.
- The `leading: @Composable (RowScope.() -> Unit)` slot lets callers inject any composable (emoji, icon, checkbox) without the component needing to know about it.
- `ListPickerItem` is a plain data class — can be constructed outside the DSL for cases where items come from a repository (as in `ProfilePickerSheet`).
- `@DslMarker` prevents accidentally using `ListPickerScope` methods outside the trailing lambda.

## Consequences

- `ListPickerSheet` is the canonical bottom-sheet picker in this codebase. For simple static lists, use the DSL form. For dynamic lists (from a repository), construct `ListPickerItem` objects and pass to the data-class overload.
- `SectionTemplate` data class and `SectionTemplates` list removed from `SavedAgendaScreen`. If templates need to be reused elsewhere, promote them to a shared location.
- Future picker sheets (ProjectPickerSheet, TagPickerSheet) should consider `ListPickerSheet` before implementing custom sheets.

## Links

- Commit: MR6 — ListPickerSheet<T> + DSL
- New files: `core/ui/components/ListPickerSheet.kt`, `core/ui/components/ListPickerDsl.kt`
- Modified files: `feature/agenda/presentation/screen/SavedAgendaScreen.kt`, `feature/agenda/presentation/screen/SavedAgendaListScreen.kt`
