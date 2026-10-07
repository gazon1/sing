# a-dropped-result-is-reported-where-it-happens

Backlog entry: `dropped-result-guard`. Follows the dropped-`Result` sweep of
2026-10-07, which found 32 sites and could not prevent the thirty-first.

## What

An arch test, `DroppedResultIsReportedTest`, plus `SourceScan` — the shared
plumbing the other source-reading rules should use — and the two defects the new
rule found the moment it could tell a real finding from a false one:

- `DecomposeAndCreateTool.kt:107` — N subtask creates, each `Result` discarded,
  every id returned to the model as if created.
- `BackupImporter.kt:140` — an attachment write inside a `try`/`catch` written
  against a throwing contract, on a method that returns `Result`, so a failed
  restore incremented `restoredCount` and the catch was never entered.

## Why a rule and not a habit

`kotlin.Result` declares exactly one annotation, `@SinceKotlin("1.3")`. There is
no `@MustUseReturnValue`, so the compiler cannot warn — verified against the
stdlib class file, not assumed. Every one of the 32 sites was invisible to every
tool in the build, and the sweep that found them was a one-off reading of
grep output.

## The three things this cost, measured

The rule was wrong three times before it was right, and each failure is recorded
in the test because each is the failure a reader is most likely to reproduce.

**A name-based rule is 9 for 9 false positives.** Matching a receiver-type suffix
and a write-verb method name reported `SyncStateRepository.setAutoSyncEnabled`,
`setScheduledInterval`, `GoogleCalendarSettingsRepository.setSelectedCalendarId`,
`setImportForeignEvents`, `CalendarSyncRepository.setEnabled`,
`setTargetCalendarId`, `setTargetAppPackage` — all `Unit`, nothing to drop — and
`SyncRepository.syncOnce`, already wrapped in a reporting `try`/`catch`.

**A global name map is the same mistake one level up.** The second version
resolved return types by method name across the whole tree, and reported
`SyncDocumentWriter`'s five `repo.upsert(…)` plus `AuthRepository`'s
`sessionStore.save(…)`. All six wrong: `TaskRepository.upsert` returns the entity
and *throws*, so `handleEvent` catches it, reports `sync.apply_failed` and returns
`ApplyOutcome.Failed`. `SessionStore.save` returns `Unit`. The lookup is now keyed
by **declaring type**, which is what distinguishes `TaskRepository.upsert` from
`ChecklistRepository.upsert`.

**Three shapes look dropped and are not**, each of which the first version
reported:

- a worked example in a KDoc (`FeatureSlot.kt` carries exactly this shape);
- a chain written across lines (`repo.startEntry(` … `).onFailure { … }` — five of
  the seven candidates a line-based probe produced were this);
- a call inside a consuming wrapper's lambda (`emitError("Move failed") {
  updateTask.invoke(…) }`), where the call's own receiver is not the wrapper.

All three are controls in the test. A rule that cries wolf gets switched off, and
a switched-off rule protects nothing.

## What is still wrong, and left alone

`selfReportingMethods()` is hand-written: `enqueue` and `refreshStatus`. It
should be derived, the way `SyncedWriteEnqueuesTest` derives its exemption set,
by finding the `Result`-returning method whose body contains
`crashReporter.report(`. Both obvious implementations were measured and both are
wrong — the first balanced brace misses `enqueue` and `refreshStatus`, whose
reporting `.also { }` sits outside the lambda, and a window to the next `fun` at
the same indent invents `save` because it runs past `override suspend fun
getItem`. Correct derivation needs statement-level extraction. That is the next
piece of work, not something to smuggle in here.

## What this does not prove

Three blind spots, written into the test rather than left for a reader to
discover:

- a receiver whose type is inferred rather than declared is not resolved;
- **a failure dropped in a loop where the value itself is used** —
  `DecomposeAndCreateTool` was exactly that, and there is no `Result` left to
  unwrap, so a rule about dropped `Result`s cannot see it. It needs its own rule;
- a `Result` consumed through reflection or a generic helper.