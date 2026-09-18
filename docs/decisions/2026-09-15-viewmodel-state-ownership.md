---
title: "ViewModel owns all domain state; Composable owns only routing and animation"
date: 2026-09-15
tags: [architecture, compose, udf, vm-state]
status: accepted
---

## Context

Seven persistent UDF violations were found in the codebase:

1. `TaskDetailViewContent.kt:62-71` — `titleDraft`/`descriptionDraft` mirrored in Composable via `mutableStateOf` + two `LaunchedEffect` sync blocks
2. `ProjectDetailScreen.kt:360-365` — `draftName`/`draftDesc` + `LaunchedEffect` mirroring
3. `TaskListScreen.kt:104-106` — `lastDeleted`/`snackbarJob` held in Screen instead of VM
4. `TaskListScreen.kt:82` — `koinInject()` for a ViewModel instead of `koinViewModel()`
5. `NoteEditorScreen.kt:73-75` — `koinInject()` for `InternalLinkRepository` and `ProfileAwareCurrentUser` inside Composable; repo called directly from `onSearch` lambda
6. `ProjectPickerSheet.kt:54-55, 74-82, 123-136` — `koinInject()` for repos + `mutableStateOf<List<Project>>` + direct `projectsRepository.create()` in `scope.launch`
7. `AccountSettingsScreen.kt:41` — `profileRepository.activeProfile().collectAsState()` inside Composable

Existing ADRs cover parts of the picture (`2026-09-05-ui-event-per-feature`, `2026-09-08-task-detail-critical-fixes`, `2026-09-09-projectdetail-write-through-fix`, `2026-09-09-preview-with-koin-helper`) but the **state ownership boundary** — exactly which layer owns which data — was never written down as a single rule.

## Decision

Three categories of UI-related data, not two:

| Category | Lives in | Example |
|---|---|---|
| **Domain state** — lists, filters, current entity, domain-derived values | `StateFlow` on the ViewModel | `state.tasks`, `_filter`, `_selectedIds`, `recentlyDeleted`, `draftTitle` |
| **One-shot events** — dialogs, snackbars, navigation | `SharedFlow<XxxUiEvent>` on the ViewModel | `NavigateBack`, `ShowError`, `SavedPulse` |
| **Routing state** — which sheet/dialog/menu is open | `remember { mutableStateOf }` in Composable | `activeSheet: ActiveSheet?`, `menuExpanded`, `selectedTab` |

Composable exceptions that are **not** domain state:
- `Animatable` / `animateFloatAsState` for one-shot UI animations (saved-pill fade, etc.)
- `MutableStateFlow<String>` used as a **write-port** — e.g. `queryFlow` in a search field that is immediately passed to a VM call. The Composable writes to it, the VM reads from it. This is a communication channel, not a duplicate of VM state.
- Transient TextField input **before** `onValueChange` fires — a user is still typing and has not yet sent the value to the VM. Once `onValueChange` fires, the VM owns the value.

### Rules

1. **Composable never copies domain state.** `var foo by remember { mutableStateOf(X) }` where `X` comes from a VM `StateFlow` is a mirror-state violation. Delete the mirror; read from the VM directly.
2. **Composable never calls repositories or use cases directly.** All I/O goes through the ViewModel. Use `koinViewModel()` for VMs, `koinInject()` for repositories only (per `2026-09-06-koin-vm-viewmodelof-koinviewmodel`).
3. **Domain logic lives in the ViewModel.** Filtering, sorting, validation — all in VM. The Composable only renders what the VM exposes.
4. **Input drafts use `MutableStateFlow` in the VM, not the Composable.** For inline-editable TextFields, the VM holds `MutableStateFlow<String> _draftTitle` alongside `_latest<Task>` in the same `combine`. The Composable reads `state.draftTitle` and calls `vm.editTitleDraft(text)` on each keystroke. This avoids cursor-jump when the VM mutates state asynchronously.
5. **`recentlyDeleted` is a `StateFlow`, not a `SharedFlow` event.** Per `2026-09-08-task-restore-undo`: «never store more than one recently-deleted task». A `SharedFlow` event can be missed on recomposition; a `StateFlow` always has the latest value.

### Anti-patterns

**Mirror-state in a Composable (❌ → ✅):**

```kotlin
// ❌ WRONG — mirror of VM state
var titleDraft by remember { mutableStateOf(ui.task.title) }
LaunchedEffect(ui.task.title) { titleDraft = ui.task.title }
TextField(value = titleDraft, onValueChange = { titleDraft = it })

// ✅ CORRECT — read from VM, write to VM
TextField(value = ui.draftTitle, onValueChange = { vm.onIntent(Domain.UpdateTitle(it)) })
```

**Repository call inside Composable (❌ → ✅):**

```kotlin
// ❌ WRONG — repo called from Composable
val linkRepo: InternalLinkRepository = koinInject()
InternalLinkPickerSheet(onSearch = { q -> linkRepo.searchNotes(q).map { ... } })

// ✅ CORRECT — VM exposes a search method, Composable calls it
val vm: NoteEditor = koinViewModel()
InternalLinkPickerSheet(onSearch = { q -> vm.searchNotesForLink(q).first() })
```

**Business logic in `remember` (❌ → ✅):**

```kotlin
// ❌ WRONG — filtering in Composable
val filtered = remember(tasks, query) {
    if (query.isBlank()) tasks.take(10)
    else tasks.filter { it.title.contains(query, ignoreCase = true) }.take(10)
}

// ✅ CORRECT — VM exposes a filtered StateFlow
val filteredTasks by vm.filteredAvailableTasks(queryFlow).collectAsStateWithLifecycle()
```

## Consequences

- **+~20% lines in ViewModels** — state that was implicit in Composables must be made explicit in VMs.
- **+100% testability** — all business logic is in pure Kotlin, testable without Compose.
- **−100% UDF violations** in this category — the rule is now written and enforced via skill.
- **Migration cost** — 7 violations across 5 PRs. See the implementation plan for the sequence.

## Links

- ADR `2026-09-05-ui-event-per-feature` — per-feature `UiEvent` sealed interface
- ADR `2026-09-08-task-detail-critical-fixes` — silent saves, debounce, `_latest<Entity>` write-through
- ADR `2026-09-08-task-restore-undo` — `_recentlyDeleted` as `StateFlow`
- ADR `2026-09-09-projectdetail-write-through-fix` — VM as sole source of truth for inline edits
- ADR `2026-09-09-preview-with-koin-helper` — `PublicScreen` / `PrivateContent` split
- ADR `2026-09-06-koin-vm-viewmodelof-koinviewmodel` — `koinViewModel()` for VMs, `koinInject()` for repos
- Skill: `singularity-todo-ui-event-vs-state` — primary skill with full anti-pattern catalog
- Skill: `singularity-todo-vm-koin-scoping` — VM injection patterns
- Skill: `singularity-todo-clean-architecture-audit` — layer boundary enforcement
