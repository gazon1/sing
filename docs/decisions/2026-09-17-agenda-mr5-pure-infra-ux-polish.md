---
title: "Agenda MR5: Pure Infrastructure + Selector Cohesion + UX Polish"
date: 2026-09-17
tags: [agenda, infrastructure, selectors, ux]
status: accepted
---

## Context

MR5 continued the agenda engine refactor from MR1–4, driven by lessons from `2026-09-17-orgmode-architectural-lessons.md` and `2026-09-17-orgmode-functional-patterns.md`. Three themes:

1. **Pure infrastructure** — shared tree utilities and single-source `isOverdue`
2. **Selector cohesion** — per-variant functional classes in `feature/agenda/domain/selector/`
3. **UX polish** — drag-and-drop reorder, section add/delete, copy-to-profile

---

## Ideas

### Pure Infrastructure

- `core/tree/Cascade.kt`: Generic `cascadeUp` with cycle detection (`LinkedHashSet` + `IllegalStateException`). Used for `Task.cascadeProjectColor` — ancestor color inheritance.
- `core/tree/TreeVisitor.kt`: Non-inline `traverseDepthFirst`, `preOrderList`, `countNodes`. Avoids Kotlin local-function-in-inline limitations.
- `feature/tasks/domain/logic/Computed.kt`: `TaskComputed.isOverdue/isReady/isBlocked` — single source of truth for derived predicates, replacing duplicated inline expressions in `AgendaEvaluator`, `TaskDomain`, `TaskUi`, `AgendaContent`.

### Selector Cohesion

- `feature/agenda/domain/selector/SelectorMatcher.kt`: `Selector.matches(task, today)` extension — single `when` dispatch for all 13 variants.
- `feature/agenda/domain/selector/SelectorDescriptor.kt`: `Selector.typeDescription` extension — human-readable labels for UI.
- `feature/agenda/domain/selector/SelectorBadge.kt`: `SelectorTransformer` interface + `DefaultBadgeRules` (pinned/completed/noDate/overdue) — badge composition via `fold`.
- `AgendaDefinition.transformers: List<SelectorTransformer> = emptyList()` with `@Transient` — backwards compatible with legacy JSON (field is dropped on serialize, defaults to empty on deserialize).
- `AgendaPresets.byTag(id)` → single-line delegation to `byTags(setOf(id))`.

### UX Polish

- `ReorderableSectionList`: Custom `pointerInput`-based drag-and-drop (no platform-specific library). Long-press activates drag, haptic on start + each boundary cross, `animateScrollToItem` keeps target visible.
- `ReorderableConfig` interface — abstraction for drag callbacks (haptic, auto-scroll). Default no-op impl for callers that don't need callbacks.
- `SectionEditorCard`: Delete icon button per section in edit mode.
- `SavedAgendaIntent.SectionAdded(Section, Int)` / `SectionRemoved(Int)` — explicit template parameter, VM uses `DraftState.addSection/removeSection`.
- `SavedAgendaCard`: Overflow menu (Edit / Copy to profile / Delete) replacing simple `DeleteActionButton`.
- `SavedAgendaListIntent.CopyToProfile(viewId, ProfileId)` + `SavedAgendaListEvent.CopySuccess` — full copy-to-profile via `ProfileRepository` + `SavedAgendaViewsRepository.watchById().first()` → new `SavedAgendaView` with fresh `SavedAgendaViewId.generate()`.
- `ProfilePickerSheet`: Modal bottom sheet listing all profiles with emoji + name, shown when Copy to profile is tapped.
- `SettingsRepository.defaultSavedAgendaViewId` + `setDefaultSavedAgendaViewId` — DataStore-backed per-profile default view storage.
- `AgendaStartRoute.SavedAgendaEdit` used as deeplink target — `AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaEdit(viewId))` routes to saved view from notifications.

---

## Decisions

- Use `@Transient` on `AgendaDefinition.transformers` for backwards compat with legacy JSON.
- `SelectorMatcher` and `SelectorDescriptor` are extension properties on `Selector`, not newtypes — avoids a breaking change to all call sites.
- Drag-and-drop uses custom `pointerInput` rather than `sh.calvin.reorderable` (no KMP artifact) or `MultiplatformDragAndDrop` (experimental, unstable as of Sep 2026).
- Copy-to-profile generates a fresh `SavedAgendaViewId` — no risk of ID collision across profiles.
- Profile picker is a bottom sheet (same pattern as other pickers in the app), not a separate screen.

---

## Consequences

- `when (selector)` appears only in `SelectorMatcher.matches` and `SelectorDescriptor.typeDescription` — compile-time enforcement of exhaustiveness for all 13 variants.
- `cascadeUp` throws `IllegalStateException` on cycle — no silent infinite loops.
- `TaskComputed.isOverdue` is the ONLY place `isOverdue` logic lives — `grep "dueDate < today"` returns 0 hits.
- All new pure functions are `internal` or `private` where possible.
- `ReorderableConfig` interface allows future swap to `sh.calvin.reorderable` without changing call sites.
- `ProfilePickerSheet` depends on `ProfileRepository.all()` — screens requiring profile context must inject `ProfileRepository`.
- D5 (Settings tab + default view picker UI) and D7 (full notification→navigator deeplink wiring) are deferred — `SettingsRepository` storage is in place; UI wiring requires further settings-screen integration work.

---

## Links

- Predecessors: MR1 → MR2a/b/c → MR3 → MR4
- Driven by: `2026-09-17-orgmode-architectural-lessons.md`, `2026-09-17-orgmode-functional-patterns.md`
- Related: `2026-09-17-selector-serializer-plain-kserializer.md`
