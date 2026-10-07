---
title: "Desktop background work: one real executor, and the guarantee it does not make"
date: 2026-10-06
status: accepted
tags: [work, sync, desktop, di]
---

## Context

The repository had two background-work ports, and both had a JVM half that did nothing:

- `SyncWorkScheduler` — `NoopSyncWorkScheduler`
- `CalendarSyncWorkScheduler` — `NoopCalendarSyncWorkScheduler`

`SyncEngine` calls `enqueuePush()` on every sign-in. On Desktop that call resolved,
returned `void`, and the sync patches stayed queued in the database forever. The
user saw a signed-in account that silently never synced, with no error anywhere.

This survived a test suite of 2400+ tests, because **the tests assert against
fakes**. `FakeSyncWorkScheduler` records that `enqueuePush()` was called; it cannot
assert that anything resulted from it. A no-op implementation is indistinguishable
from a working one at every seam the test suite can observe.

`scripts/find-unwired-surfaces.py` could not catch it either, and that is the
sharper lesson. That script finds code that nothing calls. This code *was* called —
from `SyncEngine`, on every sign-in, forever. It was bound, injected, and executed.
It was inert in the way that matters, not absent.

## Idea

Unify the two ports into one `BackgroundWorkScheduler` and give Desktop a real
implementation, rather than adding a third wrapper.

A third wrapper was the cheaper move: implement `DesktopSyncWorkScheduler` next to
the two existing no-ops and leave the shape alone. It would have taken an afternoon
and left exactly the same hole one level over — a fourth port, a fourth no-op the
next time someone targets a platform nobody ships to.

## Decision

1. **One port, not three.** `SyncWorkScheduler` and `CalendarSyncWorkScheduler` are
   replaced by `BackgroundWorkScheduler`, with a `BackgroundJobCatalog` as the
   registry of valid job ids. `schedule(jobId, schedule)` / `cancel(jobId)` /
   `runNow(jobId)` operate on ids, never on lambdas the caller closes over.

2. **`requireJob` throws on an unknown id.** A scheduler call that resolves to
   nothing is the exact failure this package exists to prevent. An unknown id is a
   programming error and is reported as one, at the callsite, rather than becoming a
   silent no-op — which is the bug this ADR is about.

3. **Desktop runs work in-process, and the KDoc says so out loud.**
   `JvmBackgroundWorkScheduler` holds a `CoroutineScope` and a map of job id to
   running loop. Work does **not** survive the app exiting, and a daily job whose
   window passed while the app was closed runs at the next start, not at 03:00.
   That is a weaker guarantee than Android's WorkManager, and callers that need a
   missed job replayed must re-arm on start.

4. **`runGuarded` wraps every run.** A throw from a job body must not kill the loop;
   it would leave the job dead for the rest of the session with no trace. The
   exception is logged, reported to `CrashReportingPort`, and recorded as
   `JobOutcome.Failed`. This is not hypothetical — an unguarded body in
   `DelayLoopSyncPeriodicTrigger` is why desktop auto-sync stopped working until
   someone read a log file.

5. **`JobSchedule` has wall-clock variants.** `Daily`/`Weekly` re-derive their next
   boundary after every run, so a machine that slept for a day wakes up and lands on
   the next occurrence instead of firing a burst of catch-ups. `Periodic(24h)` is
   wrong for "nightly" precisely because it is that burst.

6. **Idempotency mirrors `ExistingWorkPolicy.KEEP`.** A second `schedule` while a
   loop is alive keeps the existing loop — re-arming would restart the clock and
   silently move a daily job's 03:00. A second `runNow` while a run is in flight is
   satisfied by the run already happening.

7. **Platform behaviour is deliberately asymmetric, and the difference is documented
   rather than papered over.** Android survives process death; Desktop does not.
   Callers schedule by intent and let each platform do what it can.

## Rationale

Point 2 and the catalog exist for one reason: **the failure mode being fixed here is
a call that resolves to nothing.** After this change there is no code path that can
schedule work into a void — either the id is in the catalogue and something runs, or
it throws at the callsite. That is what turns "Desktop silently does nothing" from a
property of the architecture into a loud, local, immediately reproducible error.

Point 7 is the one most likely to be re-litigated. It would have been possible to
build a Desktop executor that persists across restarts — a systemd timer, a cron
line, a file-backed due-time queue. That was not done, because the honest
in-process executor plus an explicit statement of what it cannot promise is worth
more than a persistent one whose gap nobody wrote down. A caller that needs
durability reads the KDoc and re-arms. A caller that guesses will ship a bug.

## Consequences

- Desktop sync push works, and the scheduler it goes through has no inert
  implementation left anywhere in the codebase.
- `runNow` exists as a first-class operation, so "something changed locally,
  deliver it now" no longer has to be modelled as a schedule.
- Jobs missed while Desktop was closed are not replayed. This is a real limitation
  and every caller of a `Daily`/`Weekly` job on Desktop inherits it.
- Because `requireJob` throws, a typo in a job id is a crash rather than a silent
  no-op — deliberately, and loudly.
- `JobOutcome.Skipped` exists so "nothing happened" and "nothing happened *on
  purpose*" are distinguishable in the logs. That is what keeps it from decaying
  into an unread sealed hierarchy.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/work/BackgroundWorkScheduler.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/work/JvmBackgroundWorkScheduler.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/core/work/AndroidBackgroundWorkScheduler.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/work/JvmBackgroundWorkSchedulerTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/DelayLoopSyncPeriodicTrigger.kt`
- `shared/src/jvmTest/resources/platform-seams.tsv` (the two no-op rows this replaced)
- Follows: `2026-10-07-two-of-three-background-jobs-were-not-buildable-yet.md`
- Prior: `2026-09-30-testscope-background-work-semantics.md`