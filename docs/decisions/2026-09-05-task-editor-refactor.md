---
title: "Task Editor Refactor — TickTick-like single-screen editor"
status: accepted
---
# Task Editor Refactor — TickTick-like single-screen editor

## Context

`TaskEditorScreen.kt` (495 lines) was a thin compose wrapper over a `TaskEditorViewModel`
that only supported **creating** new tasks. Editing an existing task went through
`TaskDetailScreen` (a separate read-only view with per-field edit dialogs).
The two screens had divergent UX, duplicated `ProjectPickerSheet`/`TagPickerSheet`,
and `TaskEditorScreen` lacked priority, date presets, clear actions, and
dirty-state protection.

`TaskPriority` already existed in `Ids.kt`, `CreateTaskInput` accepted priority,
but the editor never exposed it.

## Idea

Unify create and edit into a single `TaskEditorScreen` driven by a single
`TaskEditorViewModel`. Keep the pure reducer pattern, expand `TaskEditorUiState`
to carry a `mode` field and all attributes needed for both create and edit,
and add the missing intents (`PriorityChanged`, `DueDateCleared`, `DueTimeCleared`,
`RemovePendingAttachment`, `LoadTask`). Decompose the 495-line Composable into
focused feature components. Add a `TaskEditorSheetHost` that wraps all bottom sheets
with a consistent look (drag handle, title, close/confirm buttons). Add dirty-state
tracking and a discard confirmation sheet.

## Decision

1. **Route** — `AppDestination.TaskEditor(initialDueDate: String? = null, taskId: String? = null)`.
   `taskId == null` means New; otherwise Edit. Backward compatible because the second
   parameter defaults to `null`. `AppNavHost` passes it through; `TasksRoute` only sets
   `initialDueDate` for the New path.

2. **State** — `TaskEditorUiState` gains:
   - `mode: TaskEditorMode = TaskEditorMode.New`
   - `priority: TaskPriority = TaskPriority.None`
   - `loading: Boolean = false`
   - `dirty: Boolean = false`

3. **Intents** — new pure intents:
   - `SetPriority(TaskPriority)`
   - `ClearDueDate`
   - `ClearDueTime`
   - `ProjectChanged(String?)` — already existed but needed `null` path to clear
   - `TagsChanged(List<String>)` — already existed but needed empty path
   - `RemovePendingAttachment(String path)`
   - `LoadTask(TaskId)` — impure; triggers loading from `TaskRepository`
   - `DiscardChanges` — resets to initial state

4. **Save semantics** — `save()` branches on `mode`:
   - `New` → `CreateTaskUseCase` + checklist + reminder + attachments (existing)
   - `Edit` → `UpdateTaskUseCase` + same sub-entities
   - **Bug fix**: `tagIds` were stored in state but never passed to the use case.
     Now both `CreateTaskInput` and `UpdateTaskUseCase` receive them.
   - Partial failure: if any sub-step fails, emit `Error` event and set `saving=false`.
     Do not silently swallow exceptions.

5. **Dirty state** — compare `originalTask` snapshot with current state after load.
   `dirty = title != original.title || description != … || …`. `DiscardChanges` resets
   to `originalTask`. Back press while `dirty` shows a confirmation sheet.

6. **Sheet routing** — a sealed `TaskEditorSheet` enum in the Composable (NOT in VM)
   controls which sheet is open: `None, Priority, Project, Date, Time, Reminder, Tags, Actions`.
   `TaskEditorSheetHost` wraps every sheet with drag handle, title, close (X) and
   optional confirm (✓).

7. **Composable decomposition** (all in `feature/tasks/`):
   - `TaskEditorScreen.kt` — entry, Koin injection, state collection, sheet routing
   - `TaskEditorContent.kt` — LazyColumn scaffold + section composition
   - `TaskEditorHeader.kt` — TopAppBar with back/save/overflow
   - `TaskEditorTitleSection.kt` — title + description inputs
   - `TaskEditorAttributeRow.kt` — reusable icon+label+value+clear chip row
   - `TaskEditorDateTimeSection.kt` — date/time chips + presets (Today/Tomorrow/Next week)
   - `TaskEditorOrganizationSection.kt` — project + tag chips
   - `TaskEditorPriorityRow.kt` — priority icon + label
   - `TaskEditorChecklistSection.kt` — add/toggle/delete, uses existing `ChecklistItemRow`
   - `TaskEditorReminderSection.kt` — reminder row + ReminderPicker
   - `TaskEditorAttachmentsSection.kt` — add/list/remove pending attachments
   - `TaskEditorActionSheet.kt` — overflow actions (discard, pin)
   - `TaskEditorSheetHost.kt` — reusable bottom sheet wrapper with drag handle

8. **Pure formatters** — all human-readable strings in `TasksFormatters.kt`:
   - `formatDueDate`, `formatDueTime`, `formatDatePresets`
   - `formatPriorityLabel`, `formatPriorityColor`
   - `formatProjectSummary`, `formatTagsSummary`
   - `formatReminderSummary`, `formatAttachmentSummary`

## Rationale

- One VM instead of two keeps DI simple (`TaskEditorDeps` stays a single data class).
- Pure reducer stays testable without Compose; sheet routing stays in the Composable
  because it is transient UI state that never needs to survive process death.
- Adding `mode` to the route is backward compatible: old serialised routes that never
  set `taskId` default to `New` just as before.
- The tags bug (stored but not saved) is fixed in the same PR since it touches the
  same `save()` function.
- `TaskEditorSheetHost` eliminates copy-paste sheet chrome across Priority, Project,
  Date, Time, Reminder, Tags sheets.

## Consequences

- `TaskEditorViewModel` constructor signature unchanged; DI registration unchanged.
- `AppDestination.TaskEditor` serialisation is backward compatible (extra field
  with default `null`).
- `TaskEditorReducerTest` must add test cases for new intents.
- `TaskEditorViewModelTest` and `TaskEditorIntegrationTest` must add edit-mode scenarios.
- `TaskDetailScreen` stays as a read-only viewer until a future PR consolidates
  detail → editor navigation.

## Links

- `docs/decisions/DIGEST.md`
- `shared/src/commonMain/.../feature/tasks/TaskEditorViewModel.kt`
- `shared/src/commonMain/.../feature/tasks/TaskEditorScreen.kt`
- `shared/src/commonMain/.../core/ui/components/DatePickerSheet.kt`
- `shared/src/commonMain/.../core/ui/components/BackTopAppBar.kt`
