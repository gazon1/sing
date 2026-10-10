---
title: "Agenda Editor No Selector Parameter Configuration"
date: 2000-01-01
status: RESOLVED
tags: ["deferred", "selector-and-tag-identity"]
---

**Tracked as:** #107
**OpenSpec change:** `openspec/changes/selector-and-tag-identity/`

**Found in:** MR-0, кодовая разведка SavedAgendaScreen.kt AddSection sheet.

**Symptom:** `ListPickerSheet<Selector>` в `SavedAgendaScreen.kt:190-198` предлагает **7 жёстко зашитых шаблонов** (Active, Completed, Due today, Overdue, No date, This week, Next week). Редактор **не позволяет** пользователю задать параметры селектора: тег/проект/приоритеты/regexp/диапазон дат. `SectionEditorCard` — read-only display, только Move Up/Down и Delete.

`Selector.Tags`, `Selector.Projects`, `Selector.Priorities`, `Selector.Regexp`, `Selector.DateRange` доступны в движке, но **не в UI**.

**Status: RESOLVED** (2026-10-04). `SavedAgendaScreen.kt` gained
`SelectorParameterSheet` (`:276`), which opens a `MultiSelectSheet` (`:291`)
of live options for the selected template: tags, projects, priorities, status
and date buckets. The engine types listed above — `Selector.Tags`,
`Selector.Projects`, `Selector.Priorities`, `Selector.Regexp`,
`Selector.DateRange` — are now reachable from the editor.

Three things were worth getting right, and each is a trap the naive version
falls into:

1. **Option list = validation list.** The ids submitted are validated against
   the same option list the picker showed. Rebuilding the list at submit time
   produced an empty set, and `Selector.Tags(emptySet())` matches zero tasks —
   a section that silently filters everything away, with no error anywhere.
2. **Unresolvable selection returns `null`,** not an empty selector. A section
   with no valid selection is not created.
3. **Stale ids are dropped from the sheet,** so a tag deleted after the view was
   saved does not leave an unselectable row.

`MultiSelectSheet` exists because `ListPickerSheet` is single-select by
contract — it calls `onDismiss()` on every tap — so widening it in place would
have broken its other callers. ADR: `2026-10-04-multi-select-sheet.md`.
Coverage: `SelectorTemplateTest` (common) plus
`SavedAgendaSelectorConfiguratorFlowTest` (3 desktop flows).

---
