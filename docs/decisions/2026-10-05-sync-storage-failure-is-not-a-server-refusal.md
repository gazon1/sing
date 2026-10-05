---
date: 2026-10-05
status: accepted
---

# A database that could not be read was reported as a server that refused

## Context

`SyncEngine.push()` read the outbox on the one line between setting the status to
`Pushing` and entering its `try`:

```kotlin
_status.value = SyncEngineStatus.Pushing
val now = System.currentTimeMillis()
val pending = outboxDao.getPending(now)   // ← not guarded
if (pending.isEmpty()) { … }
…
return try { … }
```

Three consequences, none of them the one it was reported as.

**The status was stranded.** Nothing between the assignment and the `try` could reset
it, so a storage failure left the engine advertising `Pushing` indefinitely.
`SyncEngineStatus.isRunning()` is what `SyncViewModel.refresh()` checks before starting
another cycle, so a running state that outlives its cycle is a permanent refusal: one
unlucky storage failure and manual sync is dead until the process restarts. The status
flow is also what drives the on-screen spinner, so the UI showed a push in progress that
had never been attempted.

**Both phases were reported as failed.** The throw escaped `push()` and `syncOnce()`
into `SyncCoordinator.runCycleCatching()`, which filled *both* slots of
`SyncOutcome.Success` with the same error — a failed push and a failed pull, neither of
which had run. The WorkManager worker reads exactly those two fields to decide whether
to retry, so it was reasoning about work that did not exist.

**The two failures were the same failure.** The escape path built
`AppError.Unknown(e.message ?: "")`, and so did the two catch blocks in the engine. The
transport, meanwhile, names its own failures as `AppError.Network`. A user whose
database was closed and a user whose server refused were therefore shown the same
words, and the words were the driver's — `SQLiteException`'s "attempt to re-open an
already-closed object", which describes neither the cause nor the remedy.

`SyncCoordinatorCoalescingTest` asserted the two-phase shape, so the dishonest report
was pinned by a test whose stated subject was something else entirely: that a throwing
cycle does not kill the coordinator.

## Decision

**Name the failure at the call site, and never leave the status mid-gesture.**

1. `SyncPhaseReporter` owns every way a phase ends badly: `localStorage(what) { … }`
   reports each local read and write as `AppError.Persistence` with a message naming the
   operation, and `pushFailed` / `pullFailed` / `cycleFailed` are the only ways out of a
   bad phase, each landing the status on a terminal value.
2. `SyncOutcome.Failed(error)` for a cycle that never started, replacing the coordinator's
   "one error in both phase slots".
3. The three copies of throwable-to-`AppError` flattening collapse into
   `Throwable.toAppError()`, and `runCatchingResult` now calls it too.

It was four methods on `SyncEngine` first, and detekt's `TooManyFunctions` was right to
object: the engine decides *what* to send, and a class that also owns how a phase ended
had grown past the point where the second responsibility was visible. `push()` was over
its `ReturnCount` limit for the same reason, and the fix — `planPush()` returning a
`PushPlan?`, where `null` is "nothing queued" and a failure is a failure — made the
difference between a quiet cycle and a broken one explicit instead of inferred from
which branch was taken.

## Rationale

**Classification belongs at the call site.** A `SQLiteException`, an
`IllegalStateException` from a closed Room database and a `RuntimeException` from a
missing DAO all arrive as an unlabelled `Throwable`. Deciding from the class is what
made storage indistinguishable from the network in the first place, and it is also why
the four unguarded call sites each had to catch separately and none of them recorded
what it was reading. The engine is the only place that knows the answer.

**The message names the operation; the cause keeps the driver's text.** "Could not read
the pending changes" is something a user can act on. "attempt to re-open an
already-closed object" is a line number. Both are worth having, in different places, and
they are not the same field.

**`Failed` rather than a two-phase failure.** `SyncOutcome.Success` has one slot per
phase, and a failure before either phase has nowhere honest to go. Filling both asserts
work that may never have started. Adding the case is a contract change, and the compile
error it caused in `SyncOutboxWorker`'s exhaustive `when` was the right outcome: the
worker has to decide what a cycle that could not start means, and the answer — retry,
because the patches are still queued and nothing is lost — is the same as for a thrown
exception.

**The engine's two catch blocks were worse than the shared helper they duplicated.**
Both built `AppError.Unknown(e.message ?: "")` with no cause, so every push and pull
failure reached the crash reporter as a wrapper whose own stack ended inside the catch
block, with the frames that would have said where it came from discarded. `AppError`'s
own KDoc calls losing the cause at construction "the single worst time to lose it".

## Consequences

- **The error text a user sees changes.** Storage failures now read "Could not read the
  pending changes" / "…the download cursor" / "…the active sync scope" instead of a
  SQLite message. That is the intended change and the reason for it.
- **`SyncViewModel`'s catch arm is now nearly dead.** Storage failures no longer escape
  `syncOnce()`; they arrive through the status flow, which is the path the screen
  already renders. The arm stays for a throw the engine does not expect, and it now
  builds its `AppError` with the same helper.
- **`enqueue()` is still unlabelled.** A storage failure there is `AppError.Unknown`
  rather than `AppError.Persistence`, because it goes through `runCatchingResult`. Every
  operation inside it is local storage, so the classification is knowable; it is left
  alone because nothing yet branches on the variant, and changing it would be a second
  change to a path no report has reached.
- **One test asserted the old contract.** `SyncCoordinatorCoalescingTest` pinned
  `Success` with a failed push for a throwing cycle. It now asserts `Failed`, and its
  KDoc says that the survival of the coordinator — its actual subject — is unchanged.
  A test that pins a lie is worth renaming as loudly as the lie.
- **`SyncEngineStorageFailureTest` is new**, and asserts the three consequences
  separately, because they fail separately: the variant, the stranded status, and the
  pair. It also asserts the queued work survives a read failure, which is the part that
  makes recovery possible at all.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncPhaseReporter.kt`
  — where "a phase must not leave the status running" now has one implementation
- `shared/src/commonMain/kotlin/com/singularity/todo/core/error/AppError.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncEngineStorageFailureTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SupabaseSyncApiClient.kt`
  — the transport that already names its failures `AppError.Network`
- `docs/decisions/2026-10-04-per-field-lww-conflict-policy.md` — same engine, same week
