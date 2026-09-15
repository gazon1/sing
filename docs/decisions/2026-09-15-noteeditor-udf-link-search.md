---
title: "NoteEditor UDF fix — delegate link search to ViewModel"
date: 2026-09-15
tags: ["architecture", "udf", "notes", "di"]
---

## Context

`NoteEditorScreen.kt` had two UDF violations:

1. **`koinInject()` for repositories inside Composable** — `InternalLinkRepository` and `ProfileAwareCurrentUser` were injected at the Composable level and called directly from the `onSearch` lambda in the link picker.
2. **Domain logic in Composable** — the `onSearch` lambda directly called `linkRepo.searchNotes()` and `linkRepo.searchTasks()`, bypassing the ViewModel layer entirely.

The Composable was doing I/O and holding domain state (search results), which violates the state ownership boundary defined in `2026-09-15-viewmodel-state-ownership`.

## Decision

1. **Remove `koinInject` for `InternalLinkRepository` and `ProfileAwareCurrentUser` from `NoteEditorScreen`.** These were only used for link search; no other use case needed them at the Composable level.

2. **Add search methods to `NoteEditorViewModel`:**
   ```kotlin
   suspend fun searchNotesForLink(query: String): List<LinkResult> {
       return linkRepo.searchNotes(userId.value, query)
           .map { LinkResult(it.id.value, it.title, LinkKind.Note) }
   }

   suspend fun searchTasksForLink(query: String): List<LinkResult> {
       return linkRepo.searchTasks(query)
           .map { LinkResult(it.id.value, it.title, LinkKind.Task) }
   }
   ```

3. **Change `NoteEditorScreen` signature** — replace `linkRepo: InternalLinkRepository` and `currentUser: ProfileAwareCurrentUser` parameters with `searchNotesForLink: (suspend (String) -> List<LinkResult>)?` and `searchTasksForLink: (suspend (String) -> List<LinkResult>)?`.

4. **Update `NoteEditorScreenContent`** — the `onSearch` lambda now calls the VM methods:
   ```kotlin
   onSearch = { q ->
       val notes = searchNotesForLink?.invoke(q) ?: emptyList()
       val tasks = searchTasksForLink?.invoke(q) ?: emptyList()
       notes + tasks
   }
   ```

5. **Update `NotesDiModule`** — inject `linkRepo = get()` into the `NoteEditor` constructor alongside existing dependencies.

## Rationale

- **State ownership**: search results and I/O belong in the ViewModel, not the Composable.
- **Testability**: `NoteEditorViewModel.searchNotesForLink()` can be unit-tested directly.
- **Consistency**: `InternalLinkPickerSheet` stays unchanged — it is generic and receives `onSearch` as a lambda, which is the correct pattern for reusable UI components.
- **`searchNotesForLink` and `searchTasksForLink` are nullable** — `NoteEditorScreen` passes `null` when no editor is open (loading/idle states). The VM passes its own methods when an editor is active. This avoids Optional antipattern in the domain while keeping the boundary clean.

## Consequences

- `NoteEditor` now requires `InternalLinkRepository` in its constructor — updated `NotesDiModule` accordingly.
- Previews that don't use Koin continue to work since `searchNotesForLink`/`searchTasksForLink` are nullable.

## Links

- `2026-09-15-viewmodel-state-ownership` — state ownership rules
- `2026-09-15-task-detail-drafts-undo-fix` — related UDF fixes from same audit
