# the-bulk-archive-lives-in-the-overflow

**Status:** proposed · **Issue:** #2744

## Why

The Archive screen's "archive completed tasks" sweep is a full-width primary
button, so a secondary bulk write carries the visual weight of the screen's
main job. The write can be undone one task at a time by restoring it, but there
is no bulk undo, and a control that starts the whole sweep on one tap is easy to
press by accident.

## What Changes

- Remove the full-width bulk-archive button from the Archive screen and its
  preview.
- Add a top-bar overflow action that opens a confirmation naming the number of
  eligible completed tasks.
- Disable the action when no completed live task is eligible, and while a sweep
  is running.
- Report the repository's archived count on success, say when a confirmed sweep
  archived nothing, and report a failed sweep to the user instead of leaving it
  silent.
- Remove the destructive pull-to-refresh path from the Archive screen: the
  confirmed overflow item is the only bulk path there.
- Close the confirmation when the eligible count reaches zero; it must not
  reopen when eligibility returns.
- Update the empty-state copy, which currently points at the removed button.
- **No bulk undo or bulk restore.** The confirmation replaces an undo offer the
  system cannot honour today; a snackbar claiming the batch can be undone would
  be false.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `tasks` — the bulk archive becomes a confirmed overflow action with an
  eligible count and a zero-eligible guard. Delta:
  `specs/tasks/spec.md`.

## Impact

- The Archive screen and its ViewModel in the shared feature; two new test tags.
- Desktop flow coverage and one Maestro archive flow.
- The Archive screen no longer uses the shared refresh container, whose first
  step is the unconfirmed bulk archive. The other screens' refresh behavior is
  unchanged.
- One read-only count query in the repository. No schema, write or sync change.

## References

- ADR `2026-11-11-the-bulk-archive-lives-in-the-overflow.md`
- ADR `2026-10-25-the-refresh-is-one-run.md` — the refresh also archives
- Issue #2744
