---
title: "PR 5 UDF fixes — ProjectDetail, ProjectPicker, AccountSettings, TagPicker"
date: 2026-09-15
tags: [architecture, udf, compose, di]
status: accepted
---

## Context

A second audit of the codebase (after PR 3/4 fixes) found 4 remaining UDF violations in the `core/ui` and `profile` features:

1. **`ProjectDetailScreen.kt:360–365`** — mirror-state: `draftName`/`draftDesc` held in `mutableStateOf` in `ProjectHeroSection`, synced back to VM via `LaunchedEffect`. The VM already owns `nameDraft`/`descriptionDraft` `MutableStateFlow`s.
2. **`ProjectPickerSheet.kt:48`** — `koinInject()` for `ProjectPickerViewModel` (a ViewModel, not a repository). This recreates the VM on every recomposition.
3. **`AccountSettingsScreen.kt:39–41`** — `koinInject()` for `ProfileRepository` + `collectAsState` directly in Composable, bypassing the ViewModel layer entirely.
4. **`TagPickerSheet.kt:51–65, 130–136`** — `koinInject()` for `TagsRepository`/`SettingsRepository`, with direct repository calls (`tagsRepo.create()`, `tagsRepo.watchTags().first()`) inside Composable scope. All domain logic (tag creation, selection) lived in the Composable.

## Decision

### 1. `ProjectDetailScreen` — drafts via VM StateFlow, not Composable mirror

- **VM**: exposed `nameDraft: StateFlow<String?>` and `descriptionDraft: StateFlow<String?>` as public properties (backed by private `MutableStateFlow`s). Added seed-if-empty in the `combine` block: when the project loads, drafts are seeded from `project.name` / `project.description` only if currently null (preserves user's in-progress edits).
- **Screen**: `ProjectDetailContent` now collects `viewModel.nameDraft` and `viewModel.descriptionDraft` via `collectAsStateWithLifecycle()` and passes them as parameters to `ProjectHeroSection`.
- **`ProjectHeroSection`**: removed `mutableStateOf` mirror-state and `LaunchedEffect` sync blocks. `BasicTextField` now calls `actions.onUpdateName(it)` / `actions.onUpdateDescription(...)` directly on each keystroke — the VM debounces and persists.
- **Snackbar**: removed `errorMessage` mirror variable. `LaunchedEffect(Unit) { viewModel.events.collect { ... } }` handles `ShowError` directly (suspend-compatible). `CollectEvents` removed in favor of inline `LaunchedEffect` for the suspend call.

### 2. `ProjectPickerSheet` — `koinInject` → `koinViewModel`

`ProjectPickerViewModel` was already registered in `ProjectsDiModule` via `viewModel { ProjectPickerViewModel(...) }`. The Composable was using `koinInject()` instead of `koinViewModel()`, causing VM recreation on recomposition.

```kotlin
// Before (UDF violation):
vm: ProjectPickerViewModel = koinInject()

// After (correct):
vm: ProjectPickerViewModel = koinViewModel()
```

### 3. `AccountSettingsScreen` — extract `AccountSettingsViewModel`

Created `AccountSettingsViewModel` (new file) that exposes `activeProfile: Flow<Profile?>` from `profileRepository.activeProfile()`. The Screen now uses `koinViewModel<AccountSettingsViewModel>()` and subscribes via `collectAsStateWithLifecycle()`.

Registered `AccountSettingsViewModel` in `Modules.kt` alongside other profile bindings.

### 4. `TagPickerSheet` — extract `TagPickerViewModel`

Created `TagPickerViewModel` (new file) owning:
- `tags: StateFlow<List<Tag>>` — reactive list via `settingsRepo.userId.flatMapLatest { tagsRepo.watchTags(it) }`
- `selected: StateFlow<Set<String>>` — selection set
- `isCreating: StateFlow<Boolean>` — inline-create form visibility
- `newTagName: StateFlow<String>` — inline-create text input
- `toggleTag(tagId)`, `setCreating(on)`, `setNewTagName(name)`, `createTags()` actions

`TagPickerSheet` now uses `koinViewModel { parametersOf(selectedTagIds) }` and subscribes to all state flows. All domain logic (comma-split tag creation, color assignment, ID generation) moved into `createTags()`.

Registered `TagPickerViewModel` in `Modules.kt` as a factory.

## Consequences

- 4 new files: `AccountSettingsViewModel.kt`, `TagPickerViewModel.kt`, plus DI registrations.
- 6 modified files: `ProjectDetailViewModel.kt`, `ProjectDetailScreen.kt`, `ProjectPickerSheet.kt`, `AccountSettingsScreen.kt`, `SettingsScreen.kt`, `Modules.kt`.
- Previews updated: `AccountSettingsScreenLightPreview` / `DarkPreview` now construct `AccountSettingsViewModel(FakeProfileRepository())`; `SettingsScreen` preview updated similarly.
- `collectAsState` replaced with `collectAsStateWithLifecycle` in previews.

## Links

- `2026-09-15-viewmodel-state-ownership` — state ownership rules
- `2026-09-15-task-detail-drafts-undo-fix` — similar seed-if-empty pattern in TaskDetail
