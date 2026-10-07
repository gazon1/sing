---
date: 2026-10-07
status: accepted
slug: two-of-three-background-jobs-were-not-buildable-yet
---

# WS3 asked for three jobs; only one of them existed

## Context

WS2 built the machinery — `BackgroundWorkScheduler`, `JobSchedule`, `BackgroundJobCatalog`,
and a real executor on both platforms. WS3 was "backup, archive, prune" as background
jobs, on the assumption the machinery was all that was left.

The machinery was not the constraint. Two of the three jobs had no policy to encode.

**Auto-backup** has no configuration anywhere in the codebase: no `autoBackup`
preference, no interval, no destination policy. `StubRemoteBackupService` returns the
local path as a pseudo-remote URL and an empty list for remote contents
(`core/backup/RemoteBackupService.kt:9-14`). Writing an auto-backup job would mean
inventing the retention question, the destination question and the failure question
inside a background worker — and shipping a job that silently does nothing, because the
destination is a stub. That is precisely the inert-seam defect this package was built to
end, reintroduced one layer over.

**Archive/purge** of soft-deleted rows has no cutoff anywhere: no retention setting, no
`TaskDao` method that removes rows. The archive screen is the only surface that shows
them. A "prune the archive" job would have to invent its own cutoff.

**Prune of `llm_usage` is different.** `RoomUsageRecorder.prune(olderThanDays = 90)`
exists, `LlmUsageDao.pruneOlderThan(cutoff)` exists, the clock is injected, and **nothing
calls it**. Every AI call appends a row and no code path anywhere deletes one, so the
table grows for the life of the install. That is a defect with a fix that needs no
product decision.

## Decision

Ship prune alone.

`PruneLlmUsageJob` runs the existing arithmetic, `BackgroundWorkBootstrapper` arms it
once per launch on both platforms, and the other two jobs are recorded rather than
faked. The bootstrapper's KDoc says why they are absent, so the next person does not read
the gap as an oversight.

The bootstrapper itself follows the `ProfileBootstrapper` precedent — one class in
common code, one call from each entry point — rather than scheduling from `main.kt` and
`SingularityApp.onCreate` separately. Both entry points already reach into the graph at
launch; putting the policy in both would let the two retention windows drift apart, and
the divergence would be invisible until the tables grew differently per platform.

The schedule is `Daily(4, 20)` rather than `Periodic(24h)`: an interval means "24h from
enqueue", so a user who opens the app at 16:00 gets pruning at 16:00 daily forever.

## Rationale

**A job without a policy is an inert job.** The rule the codebase learned twice now —
`NotificationPort` and `JvmReminderScheduler` — is that a bound-and-called seam doing
nothing looks identical to one that works. A "backup job" pointed at a stub service is
the same failure with a friendlier name.

**Adding a job is a two-line change on purpose.** The job plus one `arm` call. If a
future job ships without an arm, that is a missing line a reviewer can see. The inverse —
a job in the catalogue that nothing schedules — is the failure this whole package exists
to prevent.

**04:20, not 03:00.** Both platforms reach a wall-clock job eventually, and the sync poll
already sits near 03:00. A minute with no competition in it costs nothing and avoids two
maintenance jobs landing together.

## Consequences

- `llm_usage` stops growing without bound, on both platforms, from the first launch.
- The retention figure is 90 days — the number `RoomUsageRecorder.prune` already
  defaulted to — not a new invention. It is a constructor parameter for whoever sets it
  deliberately.
- Auto-backup and archive/purge remain unwritten. They need product decisions
  (destination, cadence, retention), not scheduler code, and are recorded in issue #214.
- The bootstrapper is deliberately boring: no settings, no enable toggle, no UI. Adding
  one would reintroduce a configuration nobody has agreed on.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/work/PruneLlmUsageJob.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/work/BackgroundWorkBootstrapper.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/work/BackgroundWorkDiModule.kt`
- `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`, `androidApp/.../SingularityApp.kt`
- `shared/src/jvmTest/.../core/work/BackgroundWorkBootstrapperTest.kt`
- `shared/src/commonMain/.../core/observability/RoomUsageRecorder.kt` (`prune`, :66)
- `shared/src/commonMain/.../core/backup/RemoteBackupService.kt` (the stub, :9)
- Prior: `2026-10-06-desktop-background-work-executor.md`, `2026-09-30-project-reminder-own-table.md`
