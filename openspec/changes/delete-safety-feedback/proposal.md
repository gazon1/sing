# delete-safety-feedback

## What

Make destructive actions on user data either recoverable or explicitly
confirmed, and make the recovery path itself reliable.

Three parts:

1. **Coverage.** Ten delete paths remove user data immediately and silently,
   with no confirmation and no recovery. Two of the ten — a project and a tag
   group — cascade, so recovery is not possible at all once they are gone.
2. **Reliability.** When the user takes the recovery path and the restore fails,
   nothing is said. The recovery option is already gone and the data is gone.
3. **Visibility.** The recovery offer shows no indication of how long it lasts.

## Why

The application's own notification documentation prescribes a recoverable
deletion for row-level deletes. It is implemented in exactly one place. The gap
is not a missing feature — it is nine unwritten instances of a rule that is
already written down, plus two cascading deletes that no undo can rescue.

Part 2 is the one that is not a gap at all. The code checks the failure and does
nothing with it. A user who pressed recovery, saw the offer disappear, and
assumed success has lost work with no signal — which is a worse outcome than
never offering recovery, because it also removes the user's expectation that
anything happened.

## Scope

### In scope

- Row-level deletes: tasks, notes, tags, saved views, saved searches,
  attachments, and calendar entries.
- Cascading and irreversible deletes: a project, a tag group, and a stored
  backup file.
- Recovery failure reporting.
- A visible indication of how long a recovery offer remains.

### Out of scope

- Trash or archive-based recovery for deletions outside the current session.
- Undo for a delete that has already left the device.
- Per-field undo. Recovery is whole-item or absent.
- Any change to what a delete does to synced state.

## Why a spec and not a change

This is a behavior change to roughly a dozen user-facing paths, and the rule that
governs them is the interesting part: the classification (recoverable versus
confirmed) is what determines every site's implementation. Writing the
classification into a spec is what stops the next delete path from being decided
ad hoc.

## References

- `docs/decisions/deferred-backlog.md#delete-without-confirm-or-undo`
- `docs/decisions/deferred-backlog.md#undo-restore-failure-notify`
- `docs/decisions/deferred-backlog.md#countdown-snackbar`
- `docs/decisions/deferred-backlog.md#task-detail-scaffold-refactor`
- Issues #26 (umbrella), #78 (reversal failure), #79 (screen blocked),
  #80 (countdown)

## Status

**Proposed.**
