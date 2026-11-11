---
title: "The bulk archive lives in the overflow, behind a confirmation"
date: 2026-11-11
tags: [ui, tasks, archive]
status: accepted
---

## Context

The Archive screen offered one bulk action as a full-width primary button:
"Archive completed tasks". It was the most prominent control on a screen whose
main job is browsing and restoring archived work, and one tap moved every
completed task out of every active list.

There is no bulk restore. A task can be restored individually from its detail
screen, but the confirmation cannot promise that the whole batch is one tap away
from coming back.

The refresh gesture already runs the same sweep: every screen wrapped in
`RefreshableContent` archives completed tasks and then synchronises
(`2026-10-25-the-refresh-is-one-run.md`). The Archive screen is one of them.

## Idea

1. Keep the full-width button and add a confirmation dialog.
2. Move the action into the top-bar overflow and confirm it there.
3. Replace the confirmation with an undo snackbar.

## Decision

We did (2). The action is a `DropdownMenuItem` in the Archive screen's existing
top-app-bar overflow. Selecting it opens the shared `ConfirmActionDialog`, which
names the number of eligible tasks. The confirm button names the same number.
Cancel writes nothing. The item is unavailable at zero and while a sweep runs,
and the ViewModel refuses a second sweep while one is in flight.

The eligible count is the repository's archivable count: a database `COUNT(*)`
with the same predicate as the bulk `UPDATE`, observed as one number. It does
not load the task list or its tags and edges. The dialog's number is a snapshot;
the count in the success report is the repository's own answer, because another
device can change eligibility between the dialog and the confirmation. A sweep
that moved nothing says so rather than staying silent.

The confirmation is the shared dialog, not a bespoke one, so it carries the
`dialog_confirm` / `dialog_dismiss` tags the Android flows already address
(`2026-09-29-maestro-dialog-buttons-no-testtag.md`).

## Rationale

(1) puts a confirmation in front of an action that still looks like the screen's
primary job. (3) was rejected: the archive is individually restorable, but there
is no bulk restore. A snackbar that says "Undo" and cannot put the batch back
would be a lie, and a false undo is worse than no undo.

The overflow is the platform's established place for a secondary action on both
Android and desktop. The screen's bar already publishes its action row to the
Android shell (`TopAppBar` → `ScreenActionsHolder`), so one composable serves
both platforms.

A count in the repository rather than a filter over the task list is what lets
the action be unavailable at zero before any dialog opens without paying for
rows the screen never shows. The count is a projection with the write's own
predicate, so the number and the sweep cannot disagree.

## Consequences

- Two test tags move into the registry: the overflow button and the menu item.
  The dialog reuses the shared tags.
- The Archive screen observes the archivable count as well as the archive list. A
  failure of either observation lands on the screen's existing error state.
- The success message keeps the repository's count, and says "Nothing to archive"
  when the count is zero — another device may have moved the same work.
- The repository gains one read-only count query. It has no write or schema
  impact, and its predicate is the same one the bulk `UPDATE` uses.
- **Open follow-up:** pull-to-refresh on the Archive screen runs the same sweep
  without a confirmation. This decision does not change that path; if bulk
  archiving needs confirmation as a safety rule rather than as a placement rule,
  the refresh gesture has to be decided separately.
- `autoArchiveRetentionDays` is honoured by the background job (`ArchiveJob`),
  while the manual sweep and the refresh sweep take every completed task. The
  new copy says "completed tasks" and does not imply the retention window
  applies.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/ArchiveScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/ArchiveViewModel.kt`
- `openspec/changes/the-bulk-archive-lives-in-the-overflow/`
- Issue #2744
- ADR `2026-10-25-the-refresh-is-one-run.md`
- ADR `2026-09-29-maestro-dialog-buttons-no-testtag.md`
