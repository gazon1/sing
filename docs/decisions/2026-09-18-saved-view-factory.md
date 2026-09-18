---
title: "SavedAgendaView — DraftState.markSaved(), inline copy() in VMs"
date: 2026-09-18
tags: [agenda, viewmodel, draft]
---

## Context

The `SavedAgendaViewModel.onSave()` for Edit mode updated the draft's `isDirty` flag by directly mutating `originalName`/`originalSections`. When the save succeeded, the UI still showed "unsaved changes" indicators because `isDirty = (name != originalName || sections != originalSections)` still evaluated to `true` after the structural copy. The `SavedAgendaView` data class had no factory functions — all construction logic lived inline in the VMs, and KMP metadata compilation made extension functions on the companion object fail with "Unresolved reference" at compile time.

## Decision

1. Added `DraftState.markSaved()` to `SavedAgendaViewModel.kt`:
   ```kotlin
   fun markSaved() {
       _state.update { it.copy(originalName = it.name, originalSections = it.sections) }
   }
   ```
   `onSave()` success path now calls `draftState.markSaved()` to clear `isDirty` immediately on save.

2. Factory functions (`newSavedView`, `edit`, `copyToProfile`) were considered for `SavedAgendaView` companion object but were **not added** due to a persistent KMP metadata resolution issue: extension functions on the companion object compiled in isolation but failed when the file was referenced from the JVM test source set with "Unresolved reference" errors. The decision is pragmatic — inline `copy()` calls remain in VMs, factory functions are deferred.

3. `SavedAgendaListViewModel.CopyToProfile` continues to use inline `copy()` with generated `SavedAgendaViewId` and `Instant.now()`.

## Rationale

`markSaved()` is a small, targeted fix that solves the UX problem directly. The factory function limitation is a known KMP metadata boundary issue; spending more time on it would delay other work. Inline `copy()` is idiomatic Kotlin data class usage and is already well-understood by the team. The deferred factory functions can be revisited when the KMP issue is better understood.

## Consequences

- `onSave()` success in Edit mode clears `isDirty` immediately — no stale "unsaved changes" state after save.
- `SavedAgendaView` companion object has no factory functions; VMs use inline `copy()`.
- `SavedAgendaViewModel` and `SavedAgendaListViewModel` are the only callers of `SavedAgendaView` construction.

## Links

- `feature/agenda/presentation/viewmodel/SavedAgendaViewModel.kt` (changed — `markSaved()`)
- `feature/agenda/presentation/viewmodel/SavedAgendaListViewModel.kt` (changed — inline copy)
- `feature/agenda/domain/model/SavedAgendaView.kt` (unchanged, factory functions deferred)
