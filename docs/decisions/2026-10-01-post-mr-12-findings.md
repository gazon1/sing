---
title: "Post-MR-12 findings — TaskCreate editor full fields"
date: 2026-10-01
tags: [task-editor, agenda, mr-12]
status: accepted
---

# Post-MR-12 audit findings

## MR-12: TaskCreateViewModel + TaskEditorContent full fields

**Decision**: Added all missing intents to `TaskCreateIntent`, wired all attribute rows in `TaskCreateScreen`, extended `TaskDraft` with all create-mode fields, and added testTags to the new editor rows.

### What was done

#### `TaskCreateIntent` — new intents

Added intents for every attribute not previously represented:
- `SetProject`, `SetTags`, `SetRecurrence`, `PinToggled`
- `AddChecklistItem`, `ToggleChecklistItem`, `RemoveChecklistItem`
- `AddAttachmentUrl`, `RemoveAttachmentUrl`
- `SetEndDate`, `SetEndTime`

#### `TaskDraft` / `TaskCreateUiState` — new fields

Extended `TaskDraft` with:
- `isPinned: Boolean = false`
- `recurrence: RecurrenceSpec? = null`
- `checklist: List<DraftChecklistItem>` (new draft-only serializable type)
- `attachments: List<DraftAttachment>` (new draft-only serializable type)

`DraftChecklistItem(id, text, isChecked)` and `DraftAttachment(url, title)` are used instead of domain entities to avoid domain object serialization complexity (those entities have cross-repo dependencies, UUIDs, and timestamps that can't be resolved during creation).

#### `TaskCreateViewModel` — intent handlers

All new intents have handlers that update the draft. `AddChecklistItem` generates a UUID via `nextId()`. `ToggleChecklistItem` and `RemoveChecklistItem` use string IDs.

#### `TaskEditorContent` — new rows

Added explicit composable parameters and rendered rows for:
- `startDate` (with `StartDateRow` composable)
- `project` (via `TaskAttributeCard`)
- `tags` (via `TaskAttributeCard`)
- `recurrence` (via `TaskAttributeCard`)
- `pin` (via `TaskAttributeCard`)

Also wired `startDate` to the full parameter overload (previously `null` in both `TaskEditorContent` call sites).

#### `TaskCreateScreen` — full wiring

All callbacks now pass through to the VM:
- `startDate` → `SetStartDate` / `SetStartTime` intents
- `project` → `SetProject` (project row opens `ProjectPickerSheet`)
- `tags` → `SetTags` (tags row opens `TagsPickerSheet`)
- `recurrence` → `SetRecurrence` (recurrence row opens `RecurrencePickerSheet`)
- `pin` → `PinToggled`
- `checklist` → `AddChecklistItem` / `ToggleChecklistItem` / `RemoveChecklistItem`
- `attachments` → `AddAttachmentUrl` / `RemoveAttachmentUrl`

#### `TestTags` — new constants

Added to `TestTags.kt`:
- `TASK_EDITOR_START_DATE_ROW`
- `TASK_EDITOR_PROJECT_ROW`
- `TASK_EDITOR_TAGS_ROW`
- `TASK_EDITOR_RECURRENCE_ROW`
- `TASK_EDITOR_PIN_ROW`

Applied to the new rows in `TaskEditorContent`. `TestTagsCatalog` golden updated via `-PupdateGoldens=true`.

### Fixed

- `TaskCreateScreen-null-params` — all callback/parameter pairs were `null`/`false` instead of wired
- `TaskDraft-missing-fields` — `isPinned`, `recurrence`, `checklist`, `attachments` absent
- `TaskCreateIntent-incomplete` — no intents for project/tags/recurrence/pin/checklist/attachments

### Deferred

| Issue | Reason |
|---|---|
| File attachment (`onAttachFile`) | Platform-specific (Android file picker, desktop file dialog); separate product decision |
| `CreateTaskFromDraftUseCase` persistence of checklist + attachments | Requires repo support for creating sub-entities with placeholder task IDs, then resolving on task ID generation; tracked separately |

### Verification

```bash
./gradlew :shared:jvmTest                        # green
./gradlew :shared:detekt                          # green
./gradlew :shared:jvmTest -PupdateGoldens=true  # TAGS.md updated
```

### Related

- `feature/tasks/presentation/state/TaskCreateIntent.kt` — all new intents
- `feature/tasks/presentation/state/TaskCreateUiState.kt` — `DraftChecklistItem`, `DraftAttachment`
- `feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt` — intent handlers
- `feature/tasks/presentation/screen/TaskCreateScreen.kt` — full callback wiring
- `feature/tasks/presentation/components/detail/TaskEditorContent.kt` — new rows
- `core/ui/TestTags.kt` — new tag constants
