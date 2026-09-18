---
title: "ProjectDetailScreen — 15-fixes rework (2026-09-09)"
date: 2026-09-09
tags: [projects, screen-architecture, preview, koin, reactive]
status: accepted
---

## Context

A comprehensive audit of `ProjectDetailScreen` and `ProjectDetailViewModel` revealed 15 distinct problems spanning: preview crashes, dead UI, missing reactivity, and wrong architecture patterns. This ADR documents all decisions made during the fix, which was split into 6 atomic commits.

## Decisions

### 1. Preview crash — VM-as-parameter, not `PreviewKoin` helper

`@Preview` crashed with `IllegalStateException: KoinApplication has not been started`. Attempted a `PreviewKoin` helper that started a minimal Koin application in preview context. The Koin DSL (`factoryFor`, `include`) had different availability in the `KoinAppDeclaration` lambda, making it unworkable.

**Decision:** Every screen split into public `XxxScreen` (Koin wrapper) and private `XxxContent` (VM as parameter). `@Preview` calls `XxxContent(vm, ...)` with manually constructed FakeRepositories.

### 2. Dead `Edit`/`MoreMenu` in `ActiveSheet` sealed interface

`ActiveSheet.Edit` and `ActiveSheet.MoreMenu` were no-op branches. The edit functionality was already inline in `ProjectHeroSection` (inline-editable name field).

**Decision:** Remove `Edit` and `MoreMenu` from `ActiveSheet`. Replace `MoreMenu` icon button with a real `DropdownMenu` anchored to the `MoreVert` icon button, with Archive and Delete options.

### 3. Dead quick-add input — no `createTask` intent

`ProjectDetailQuickAddInput` rendered an `OutlinedTextField` but had no `onSubmit` handler wired to anything.

**Decision:**
- Add `createTask(title: String)` to `ProjectDetailViewModel` — creates a `Task` via `taskRepo.create()`
- `ProjectDetailQuickAddInput` signature changed from `(projectId, modifier)` to `(onSubmit: (String) -> Unit, modifier)`
- Keyboard: `ImeAction.Done` + `KeyboardActions(onDone = { onSubmit(text); text = ""; focus.clearFocus() })`

### 4. `ParentPickerSheet` — TODO instead of real list

`ParentPickerSheet` had a `// TODO: list all root projects` comment and only rendered "None (root)".

**Decision:** `ParentPickerSheet` now receives `options: List<ParentOption>` (DTO) from `viewModel.parentOptionsFlow`. "None (root)" is a separate `TextButton` above a `LazyColumn` of `FilterChip` items. Cycle prevention: current project and deleted/non-root projects are excluded in the ViewModel.

### 5. Dead `Remind`/`Attach` buttons — `ProjectBottomActionBar`

Both `IconButton` handlers were `/* TODO */`.

**Decision:**
- `Remind` → `sheetState = ActiveSheet.PickReminder` → renders `ReminderPicker` composable
- `Attach` → `sheetState = ActiveSheet.AddAttachment` → placeholder sheet (project-level attachments are future enhancement; task-level via `TaskDetailScreen`)
- `ActiveSheet` extended with `PickReminder`, `AddAttachment`, `PickDueDate`, `ShowChildren`

### 6. Dead due-date chip in `ProjectMetaChipsRow`

`FilterChip(selected = false, onClick = { /* open date picker */ })` — no-op.

**Decision:** `ProjectMetaChipsRow` now takes `onDueDateClick: () -> Unit` and `onChildClick: () -> Unit` parameters. Wired to `sheetState = ActiveSheet.PickDueDate` (uses `DatePickerSheet` from `core/ui/components/`) and `sheetState = ActiveSheet.ShowChildren` (simple `LazyColumn` of child projects).

### 7. Dead task click in `ProjectBodySection`

`TaskCard(onClick = { /* TODO: navigate to task detail */ })` — no-op.

**Decision:** `onNavigateToTask: (TaskId) -> Unit` added to `ProjectDetailContent` signature. `AppNavHost` wires it to `navigator.navigate(AppDestination.TaskDetail(taskId.value))`.

### 8. `combine` with 5+ flows — Kotlin limit

`ProjectDetailViewModel` needed `project`, `tasks`, `childProjects`, `parent`, `hideCompleted` (5 flows) plus `parentOptions`. Kotlin's `combine(a, b, c, d, e) { ... }` supports exactly 5 arguments.

**Decision:** `parentOptionsFlow` is a **separate** `StateFlow` with its own `combine + stateIn`. It is NOT part of the main 5-flow `combine`. The screen collects it independently: `val parentOptions by viewModel.parentOptionsFlow.collectAsStateWithLifecycle()`.

### 9. `ProjectDetailUi.parent = null` — hardcoded

`parent` was hardcoded to `null` in the UI construction inside the `combine` block.

**Decision:** Added `projectFlow.flatMapLatest { if (it?.parentId == null) flowOf(null) else projectRepo.watchProject(it.parentId) }` as a 4th flow in the main `combine`, matching the `parent` parameter in the `combine` block. `ProjectDetailUi.parent` is now reactive.

### 10. `ProjectPickerSheet` — snapshot `.first()` instead of continuous `.collect()`

`ProjectPickerSheet` used `LaunchedEffect { val userId = settingsRepo.userId.first(); projects = projectsRepo.watchProjects(userId).first() }` — reactive data was loaded once as a snapshot.

**Decision:** Replaced with continuous collection:
```kotlin
val userId by currentUser.scopedUserId.collectAsStateWithLifecycle()
LaunchedEffect(userId) {
    projectsRepo.watchProjects(userId.value).collect { projects = it }
}
```
Also replaced inline create's `settingsRepo.userId.first()` with `currentUser.scopedUserId.value.value`.

### 11. `ProjectDetailScreen` — `onNavigateToTask` missing from public API

The screen's public function signature didn't include `onNavigateToTask`, making task click navigation impossible from external callers.

**Decision:** Added `onNavigateToTask: (TaskId) -> Unit` to both `ProjectDetailScreen` (public, Koin) and `ProjectDetailContent` (private, VM parameter).

### 12. 4 new `ActiveSheet` variants — `PickReminder`, `AddAttachment`, `PickDueDate`, `ShowChildren`

These were needed to wire the previously dead buttons/chips.

**Decision:** Each is a separate `data object` in `ActiveSheet`. Each renders a corresponding sheet composable. `ReminderPickerSheet` wraps `ReminderPicker` from `feature/reminders/`. `DatePickerSheet` is reused from `core/ui/components/`. `ChildProjectsSheet` is a simple new `ModalBottomSheet` with a `LazyColumn`.

### 13. `Remind` button opens `ReminderPicker` — no project-level reminder exists

`ReminderPicker` was designed for tasks (picks a `ReminderOffset` for task due-date reminders). Projects don't have a reminder concept yet.

**Decision:** `ReminderPickerSheet` calls `ReminderPicker` with `AT_DUE` as placeholder and dismisses on selection. The UX is "see the options, dismiss" — future enhancement will wire project-level reminders properly.

### 14. `AttachmentSheet` requires `AttachmentsViewModel` — not available in ProjectDetail

`AttachmentSheet` takes `AttachmentsViewModel` as a parameter, which is constructed via `koinViewModel()`. `ProjectDetailScreen` doesn't have access to this VM.

**Decision:** `AttachmentPlaceholderSheet` — a simple informational `ModalBottomSheet` explaining project-level attachments are a future enhancement and directing users to task-level attachments via `TaskDetailScreen`.

### 15. `@OptIn(ExperimentalMaterial3Api::class)` needed on new sheet composables

Material3 APIs (`DatePicker`, `ModalBottomSheet` in some configurations) are experimental.

**Decision:** Added `@OptIn(ExperimentalMaterial3Api::class)` to `ReminderPickerSheet`, `AttachmentPlaceholderSheet`, and `ChildProjectsSheet`.

## Consequences

- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen`
- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters
