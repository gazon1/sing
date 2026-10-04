---
title: "Post-MR-10 findings — TaskEditor refactor + agenda test ratchet"
date: 2026-10-01
tags: [task-editor, agenda, maestro, mr-10]
status: accepted
---

# Post-MR-10 audit findings

## MR-10: TaskEditor lint refactor + agenda test ratchet

**Decision**: Extracted inline composables from `TaskEditorContent`; fixed dormant Maestro flow; extended `TasksRobot`.

### What was done

#### `TaskEditorContent` refactor

Extracted two inline rows into dedicated composables following the existing `TaskTitleRow`/`TaskDescriptionField` pattern:

- **`TaskEditorPriorityRow`** (`feature/tasks/presentation/components/detail/TaskEditorPriorityRow.kt`) — priority attribute row with `Icons.Filled.Flag` and clear button. Has `testTag(TestTags.TASK_EDITOR_PRIORITY_ROW)` already applied.
- **`TaskEditorDueDateRow`** (`feature/tasks/presentation/components/detail/TaskEditorDueDateRow.kt`) — due date attribute row with `Icons.Outlined.CalendarToday` and clear button. Has `testTag(TestTags.TASK_EDITOR_DUE_ROW)` already applied.

`TaskEditorContent` reduced from 498 LOC to ~277 LOC. Both components are stateless, receive callbacks, use `TaskSpacing` tokens.

#### Dormant Maestro flow fix

`Maestro/flows/agenda/03-saved-views-crud.yaml` had a step waiting for `id: snackbar_saved` which never renders. Root cause: `Notification.Text("Saved", null)` → `ResultDialog` → early-exit when `text == null`. The dialog is a no-op for this notification variant.

**Fix**: Removed the `extendedWaitUntil` step for `snackbar_saved`. The flow still validates that the saved view appears in the list after creation.

**`deferred-backlog.md` entry closed**: `saved-views-crud-flow-selects-a-snackbar-that-does-not-exist`.

#### `TasksRobot.given()` extension

Extended `given()` with optional parameters using neutral defaults so existing call sites compile unchanged:

| Parameter | Type | Default |
|---|---|---|
| `projectId` | `ProjectId?` | `null` |
| `tagIds` | `List<TagId>` | `emptyList()` |
| `isPinned` | `Boolean` | `false` |
| `priority` | `TaskPriority` | `TaskPriority.None` |
| `recurrence` | `RecurrenceSpec?` | `null` |

`checklist` was removed — `ChecklistItem` is a separate entity keyed by `taskId`, not embedded on `Task`.

### Fixed

- `TaskEditorContent.kt` long-method smell (498 LOC, 2.8× the 180-LOC threshold)
- `saved-views-crud-flow-selects-a-snackbar-that-does-not-exist` (deferred-backlog)
- `TasksRobot.given()` neutral extension (supports MR-11/MR-12 seed scenarios)

### Future (product decisions needed)

1. **`NotificationHost` routing**: `Notification.Text` with `null` detail is a no-op. If a "Saved" toast/snackbar is the intent for save confirmations, `NotificationHost` needs a text-notification variant that routes to `SnackbarHost` instead of `ResultDialog`. That is a production change and needs its own ADR.

2. **`ResultDialog` tagging**: `ResultDialog`'s OK button does not currently carry a `testTag` (see `dialog-buttons-untagged` in maestro skill). If dialog-based confirmations are the intent for saved-view create/discard, the `CONFIRM` and `CANCEL` dialog buttons need `testTag` applied.

3. **Maestro tag rot sweep**: The check that found the dormant flow is cheap — resolve every `id:` in `Maestro/flows/**` and assert each one is produced by some `Modifier.testTag`. A `Maestro/scripts/check-tag-existence.sh` (validating spelling AND existence, not just spelling) should become a script next to `Maestro/scripts/check-tags.sh`. Every regression-tagged flow is a candidate for the same class of rot.

### Deferred

| Issue | Reason |
|---|---|
| `bulk-task-operations-have-no-ui` | Separate product decision |
| `core-auth-oauth-is-entirely-unwired` | Separate product decision |
| `find-unwired-surfaces-has-no-baseline` | Needs script baseline first |
| `Recurring` badge gap in `TaskRowContent` | MR-14 (badge contract) |
| `Section.baseFilter` SQL narrowing | Deferred to post-MR-14 per ADR |

### Verification

```
./gradlew :shared:jvmTest                        # green
./gradlew :desktopApp:test --tests "**/CreateTaskFlowTest"  # green
./gradlew :shared:detekt                          # green
```

### Related

- `docs/decisions/2026-09-29-deferred-backlog.md` — entry `saved-views-crud-flow-selects-a-snackbar-that-does-not-exist` CLOSED
- `feature/tasks/presentation/components/detail/TaskEditorContent.kt` — 498 → 277 LOC
- `Maestro/flows/agenda/03-saved-views-crud.yaml` — dormant flow fixed
