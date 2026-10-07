# a-status-that-was-never-recomputed-was-reported-with-a-stale-value

Backlog entry: `refresh-status-failure-is-invisible`. Found by the dropped-`Result`
audit of 2026-10-07; no issue number yet.

## What

`ApplyProposalItemUseCase` calls `proposals.refreshStatus(...)` after every confirm,
reject and batch confirm — three sites — and discards the `Result` at all three. The
fix is for `refreshStatus` to log and report its own failure.

## Why

`refreshStatus` is the only thing that recomputes a proposal's aggregate status from
its items. When it fails, the items are correct in the database and the proposal keeps
the status it had **before** them: the user confirmed three items and the proposal still
reads "pending".

The consequence is that a stale summary and a genuinely pending proposal are
indistinguishable in the UI, and only one of those is true. Nothing else recomputes it
on a read — `watchProposal` serves the stored column — so the stale value is not a
blip that a later read fixes; it persists until the next confirm or reject happens to
re-run the reduction.

## Why the seam, and why reported rather than thrown

Both decisions follow the one already made for `SyncEngine.enqueue` (REQ-OS-028), for
the same reason: three identical calls in one class, each immediately after a write
whose `Result` the caller already handles. Three error paths would repeat the sentence
and still be silent at the next call site someone writes.

**Reported, not thrown.** The items are already decided correctly. Only the derived
summary row failed. Throwing would fail a confirm that genuinely succeeded, over a
value the user did not ask for — turning a display bug into a data-operation failure.
The next confirm or reject retries the recomputation.

That also means this does **not** promise convergence while the failure persists. The
requirement is that the failure becomes visible, not that the status self-heals.

## The correction this change carries

The audit listed a further 9 sites in `CalendarSyncViewModel` and `SyncViewModel`, and
this change is what proved them wrong. All nine were read and none is a dropped
`Result`:

- `SyncStateRepository.setAutoSyncEnabled` / `setScheduledInterval` return `Unit`.
- `GoogleCalendarSettingsRepository.setSelectedCalendarId` / `setImportForeignEvents`
  return `Unit`.
- `CalendarSyncRepository.setEnabled` / `setTargetCalendarId` / `setTargetAppPackage`
  return `Unit`.
- `SyncRepository.syncOnce` is already wrapped in a `try`/`catch` that reports via
  `crashReporter` and sets `SyncEngineStatus.Failure`.

They were found by matching receiver-type suffixes and write-verb method names, and
every one of the nine was a false positive. That is the evidence for what the guard
must do: resolve the declared return type from the declaring interface, because a name
heuristic on this corpus is 9 for 9 wrong in one direction and would have sent the next
author to "fix" nine methods that have no `Result` to unwrap.