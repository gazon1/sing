---
title: "Cancellation integrity — migrate 199 runCatching sites to runCatchingCancellable"
date: 2026-10-04
status: accepted
tags: [coroutines, cancellation, detekt, migration]
---

# Cancellation integrity — migrate 199 `runCatching` sites to `runCatchingCancellable`

## Context

`NoRunCatchingInSuspend` had been registered, configured, tested, and switched
**off** (`active: false`) with a note that the migration had to come first. An audit
listed it among the vacuous gates; that was wrong, and acting on it — flipping the
rule on before migrating — would have turned a deliberate choice into 188 build
failures with no path to green.

The defect it guards against is real and is the one
`2026-10-03-kotlinx-coroutines-debug` exists to diagnose. `kotlin.runCatching`
catches `Throwable`, so inside a suspend function it converts a
`CancellationException` into `Result.failure`. Nothing upstream sees the
cancellation: the job is never cancelled, the flow never completes, and the
coroutine dies silently before its first emission.

`core/error/RunCatching.kt` has provided `runCatchingCancellable` — which re-throws
`CancellationException`, returns `Result.failure` for everything else, and lets
`Error` propagate — since before the rule was written. The gap was adoption, not
capability.

## Decision

**1. Activate the rule, then let it name the sites.** The plan estimated ~195. A raw
count of `runCatching` in `commonMain` gives 239, which is also wrong: most of those
are in non-suspend contexts where `runCatching` is correct. The rule reported
**188 sites in `shared` plus 11 in `desktopApp`** — 199 total, across 44 files. The
migration was driven from the detekt report's own coordinates, not from a search, so
no valid non-suspend call site was touched.

**2. Migrate the test fakes too.** 60 of the 188 are in
`test/fakes/FakeRepositories.kt`, which lives in `commonMain`. They are suspend
functions, and a fake that swallows cancellation will make a cancellation bug
*reproduce differently under test than in production* — the worst possible outcome
for a double.

**3. Format the consequence rather than baseline it.** `runCatchingCancellable` is
ten characters longer, which pushed 16 lines past the line-length limit. Those were
rewrapped (`scripts/wrap-long-signatures.py`) rather than suppressed. Baselining
formatting debt created by a fix in the same change is the same move that produced
the 413-entry baseline.

**4. Pin the helper's contract.** `RunCatchingCancellableTest` now asserts that
`CancellationException` propagates, that `Error` is not captured, and — as a
deliberate counter-example — that plain `runCatching` *does* swallow it. The helper
went from correct-but-unused to load-bearing for 199 call sites; its contract needed
to be load-bearing too.

## Rationale

**Why the rule had to stay off until this point.** The plan's own warning was right:
"enabling a rule reddens the build". The ordering was rule-off → migrate → rule-on,
and the migration could only be scoped correctly by first switching the rule on and
reading its report. Doing it in that order meant the build was never red for longer
than one commit.

**Why not a regex sweep.** A 239-site find-and-replace would also have converted
non-suspend `runCatching` calls, importing a cancellation-aware helper where no
cancellation exists. It would also have touched `kotlin.runCatching`-style qualified
calls. Using the rule's coordinates keeps the change to exactly the set the project
already agreed was wrong.

**Why rewrap instead of relax the limit.** See Consequences — the limit is a
separate, unresolved inconsistency and this change did not want to bundle a
gate-relaxation decision into a correctness fix.

## Consequences

- 199 call sites now propagate cancellation. Any caller that was relying on
  `Result.failure(CancellationException)` will now see the exception instead — that
  is the intended fix, but it is a behaviour change and the full suite was the
  check for it.
- The 8 hand-rolled `catch (e: CancellationException)` blocks in `BackupImporter`,
  `SyncEngine`, `SyncBootstrapper`, `ClusterNotesTool`, `ClusterTasksTool` and
  `DraftMviViewModel` are now redundant with the helper. They were left in place
  because removing them is a separate, smaller cleanup — see
  `deferred-backlog.md`.
- **`NoRunCatchingInSuspend` is now `active: true` and the baseline holds zero
  entries for it.** Any new bare `runCatching` in a suspend function fails the build
  immediately, and the ratchet will not let the count come back.
- **Unresolved: two line-length authorities.** `.editorconfig` sets
  `max_line_length = 140` and a comment in `detekt.yml` says "ktlint owns line
  length", but detekt's `style:MaximumLineLength` is not configured, so it uses its
  built-in default of **120** — the stricter value silently wins. This change
  rewrapped to 120 rather than relaxing the gate, but the mismatch is a decision
  somebody has to make deliberately. Recorded in `deferred-backlog.md`.

## Links

- `core/error/RunCatching.kt` — the helper.
- `shared/src/commonTest/.../RunCatchingCancellableTest.kt` — the contract.
- `2026-10-04-rule-verifiability-inventory.md` — why this rule was reclassified from
  "vacuous" to "deliberately off".
- `2026-10-03-kotlinx-coroutines-debug.md` — the failure mode this prevents.
- `scripts/migrate-run-catching.py` — the coordinate-driven migration.
