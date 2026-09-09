---
title: "Notes — generic InternalLinkPickerSheet with merged Notes+Tasks results"
date: 2026-09-09
tags: [notes, ui-components, linking, architecture]
---

## Context

`InternalLinkPickerSheet` lived in `core/ui/components/` but imported `Note` and `Task` domain types from `feature/notes` and `feature/tasks`. This violated the architecture rule: `core/` must not depend on `feature/`.

Additionally, it had two separate tabs (Notes / Tasks) that performed two sequential searches. The UX was worse than a merged list with an icon indicating kind.

## Decision

**Generic sheet in `feature/notes/components/`.**

`LinkResult` and `LinkKind` moved to `feature/notes/LinkResult.kt`:
```kotlin
data class LinkResult(
    val id: String,
    val title: String,
    val kind: LinkKind,
)
enum class LinkKind { Note, Task }
```

The sheet signature is now caller-driven:
```kotlin
@Composable
fun InternalLinkPickerSheet(
    queryFlow: MutableStateFlow<String>,
    onSearch: suspend (String) -> List<LinkResult>,
    onSelected: (LinkResult) -> Unit,
    onDismiss: () -> Unit,
)
```

The caller (NoteEditorScreen) provides `onSearch` that merges results:
```kotlin
onSearch = { q ->
    val notes = linkRepo.searchNotes(currentUser.scopedUserId.value, q)
        .map { LinkResult(it.id.value, it.title, LinkKind.Note) }
    val tasks = linkRepo.searchTasks(q)
        .map { LinkResult(it.id.value, it.title, LinkKind.Task) }
    notes + tasks  // merged, sorted naturally by DAO
}
```

The sheet no longer imports any feature domain type. The `core/ui/components/` location is eliminated.

## Consequences

### Positive
- `core/ui/components/` is now free of feature-domain imports
- Single search + merged results = better UX (one tap instead of tab switching)
- Sheet is reusable by any feature that needs internal linking (e.g. TaskEditor)
- Icon per `LinkKind` makes the list scannable

### Negative
- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site
- Search debouncing (300ms) is now the caller's responsibility (implemented inside the sheet via `LaunchedEffect`)

## Alternatives Considered

1. Keep tabs but genericise — rejected: merged results are better UX
2. Use sealed interface in `core/` — rejected: would still pull feature types into core
3. Keep sheet in core, add generic adapter — rejected: over-engineered for the actual usage pattern

## Links

- `feature/notes/components/InternalLinkPickerSheet.kt` — new generic sheet
- `feature/notes/LinkResult.kt` — `LinkResult` data class + `LinkKind` enum
- `NoteEditorScreen.kt` — caller providing merged search
