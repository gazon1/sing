# enqueue-failure-is-reported-at-the-seam

Backlog entry: `enqueue-failure-is-silent`. Found by the dropped-`Result`
audit of 2026-10-07; no issue number yet.

## What

`SyncEngine.enqueue` returns `Result<Unit>` and 18 call sites discard it. The
failure is therefore visible to nobody: no log, no crash report, no UI.

The fix is inside `enqueue`: it logs and reports its own failure. The 18 call
sites stay as they are, and the `Result` return type stays.

## Why

The write pipeline is `assertCanWrite` → `dao.upsert` → `syncRepository.enqueue`,
and the local row is committed **inside** `unitOfWork.write` — before `enqueue`
is called. So a failed enqueue is not a failed write. It is a write that
succeeded locally and will never reach the server: the patch row was not
inserted, so nothing will ever push it, and the device has no record that it
owed the server anything.

That is a silent divergence, and it is the worst kind, because every other layer
reports the same event. The user's edit is in the database, the sync screen says
"up to date", and the next cycle reads an outbox with no row in it. There is
nothing for any later reconciliation to notice.

This has already cost an outage. `2026-09-27-write-layer-soundness.md` MR-4
found that `Note` lacked `@Serializable`, so `toJson()` threw for **every note
enqueue** and the exception was swallowed: notes stopped syncing entirely, for
every user, with no error anywhere. MR-5 found the same defect in `Project` and
`Tag`. The silence did not merely hide the bug, it hid the discovery of it.

## How

Report at the seam, once, rather than at 18 call sites. `enqueue` is the one
place that knows a patch failed to be queued, and it already holds both a
`Logger` and a `CrashReportingPort`. Eighteen log statements would put the same
sentence in six data-layer classes and still say nothing at the seventeenth call
site someone writes next year.

The `Result` return is deliberately **not** removed. A caller that wants to react
still can, and the contract a test asserts on is unchanged — the control below
checks both halves, because "it returns failure" and "someone finds out" are
different claims.

A `CancellationException` is not reported. `runCatchingResult` rethrows
cancellation before it can become a `Result`, so by the time the failure is
visible it is always a real `AppError`. A cancelled enqueue is not an outage and
must not page anyone.

**Invariant:** the control drives `outboxDao.insert` into throwing and asserts
that `crashReporter` received the original throwable under a stable issue key.
Without it, "enqueue reports its own failure" and "nothing in this file was ever
exercised against a failing outbox" produce identical test output.