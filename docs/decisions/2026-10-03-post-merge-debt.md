---
title: "Post-merge debt: deferred fixes and accepted risks from the time-hub merge"
date: 2026-10-03
status: accepted
tags: [debt, tasks, testing, profiles]
---

## Context

The follow-up sweep after `2026-10-03-merge-regression-fixes.md` fixed the items that
blocked or misled users directly (a transient `Error("Not found")` flash on every
task-detail open; failure diagnostics that could hang on a never-idle composition; a
stale hand-mirrored `desktopPlatformModule()` inside `KoinGraphValidationTest`). This
ADR records what was deliberately NOT fixed now, so the next audit starts from facts
instead of re-deriving them.

## Decided now (for the record, no action needed)

- **Task-detail loading vs missing** — the top-level combine previously emitted
  `Error("Not found")` while `taskFlow` was still on its seeded null, so every detail
  open flashed the error screen. Fixed with a private `TaskLoad` partition
  (`Pending` / `Found` / `Missing`) fed by the same collect that feeds `taskFlow`;
  slots keep consuming `StateFlow<Task?>` unchanged. `retry()` resets to `Pending`.
- **Failure diagnostics are default-safe** — `FailureBundle.capture` freezes the frame
  clock (`mainClock.autoAdvance = false`) before `captureToImage`. Both known hang
  modes (recomposition retries and endless animations) are delivered as frame-clock
  callbacks, so a frozen clock lets the composition reach idle immediately. The
  screenshot shows the last composed frame — mid-transition is acceptable for
  diagnosis. `-Dsingularity.test.screenshot=false` still skips entirely.
- **DAO parity is machine-checked** — `TestPlatformModuleParityTest` (desktopApp) lists
  DAO definitions registered by the real `platformModule()` and asserts the test
  module binds them all; nothing is resolved, so no files or ports are touched. The
  hand-mirrored `desktopPlatformModule()` in `KoinGraphValidationTest` had already
  drifted (no `proposalDao`/`proposalItemDao`/`calendarSyncTaskMapDao`) — brought back
  in lockstep; it validates resolution, the parity test validates registration.

## Deferred

### 1. `scopedUserId.value` snapshots at construction time

`TaskProposalsCollector` receives
`deps.proposals.watchProposalsForTask(taskId, deps.currentUser.scopedUserId.value)` —
the user id is read ONCE when the coordinator is constructed. `ProfileAwareCurrentUser`'s
scoped id can change moments after startup (the real auth resolves `anonymous` → a
generated ULID), and anything that snapshots too early watches the wrong partition
until the VM is recreated. The flow harness even documents this pattern for writes.

Impact: low — the window is short and proposals are local-only. Fix direction: pass
the `scopedUserId` flow into the collector and `flatMapLatest` inside it (the same
shape `observeForCurrentUser` already uses). Same review applies to any future slot
that takes a user id eagerly.

### 2. DIGEST over the 1550-line budget

`DIGEST.md` is auto-generated (400 entries) and crossed the documented limit at 1580
lines. Pruning is the monthly doc audit's job (`singularity-todo-monthly-doc-audit`);
the generator itself needs no change.

### 3. Frozen-frame screenshots are not frozen-input screenshots

With `autoAdvance = false` the captured frame can be mid-transition (sheet animating,
partial composition). That is what diagnosis needs — the state AT failure — but it is
not pixel-identical to what a user would have seen next frame. Do not use
`FailureBundle` screenshots as visual-regression baselines; snapshot tests own that.

### 4. Test-graph validation splits across two tests by design

Resolution bugs (Koin can build every singleton) belong to `KoinGraphValidationTest`;
registration parity (production vs test module) belongs to `TestPlatformModuleParityTest`.
Merging them would force the resolution test to resolve the real `platformModule()`,
which eagerly runs the settings migration against the real user directory — the
hand-mirror exists for that reason, and the parity test removes its drift risk.

## Links

- Previous: `2026-10-03-merge-regression-fixes.md` (the three merge regressions and
  their prevention tests)
- Skills carrying the operational lessons: `debugging-investigation` (failing-test
  playbook), `singularity-todo-desktop-compose-ui-tests` (harness switches,
  parity test), `singularity-todo-testable-vm` (init-order rule)
