---
title: "Merge regressions: Nav3 rendering contract, property-init-order NPE, test-harness DAOs"
date: 2026-10-03
status: accepted
tags: [nav3, coroutines, testing, merge]
---

## Context

Merging `refactor/time-hub-ai-proposals` (MR-0..MR-9) into `main` exposed three
regressions that made every desktop flow test fail or hang on the feature branch,
plus one pre-existing `main` build break. All four share a theme: **code that
compiles, passes VM-level tests, and only fails when wired into the full graph**.
They were diagnosed with thread dumps, `DebugProbes` coroutine dumps, and a
plain-Koin probe that reproduced a UI hang without Compose.

## Decision

### 1. `Nav3State.getTopLevelRoutesInUse()` — targeted, never "all non-empty"

Every top-level back stack is seeded with its key at composition, so *every* stack is
permanently non-empty. An `isNotEmpty` filter therefore returns all routes in
insertion order and `NavDisplay` renders the last one — the app booted into Settings
while the top bar said "Today". The list must stay targeted:
`[startRoute]` at boot, `[startRoute, topLevelRoute]` otherwise.

*Contract*: `toDecoratedEntries` renders the CURRENT tab; anything that widens the
set of rendered entries must prove the last element is the current one.

### 2. ViewModel properties read from `init` must be declared BEFORE it

`TaskDetailCoordinator` declared `extrasState` **after** the `init` block that
launches a `combine` over it. Kotlin initialises properties in declaration order; an
init block sees a not-yet-assigned property as `null` (no intrinsic check inside the
same class), so the combine coroutine died with
`NullPointerException: parameter f8 is null` before its first emission. No error
state, no event — just a loading spinner forever, and `waitForIdle` never settling
because the spinner animates every frame.

*Prevention*: `TaskDetailCoordinatorGraphTest` builds the coordinator from the real
DI graph (`domainModule()` + `testPlatformModule()`) and asserts the state reaches
`Loaded` in real time. A coroutine that dies before its first emission leaves no
trace without DebugProbes; this test is the trace.

### 3. Test platform modules must bind every `AppDatabase` DAO the graph resolves

`testPlatformModule()` did not bind the new DAOs (`TimeEntryDao`, `ProposalDao`,
`ProposalItemDao`), so Koin threw `NoDefinitionFoundException` inside composition —
which Compose retries every frame, presenting as an endless redraw loop. Production
`platformModule.{jvm,android}.kt` and the test module must stay in lockstep; the
DI graph validation test covers production only.

### 4. `-Dsingularity.*` forwarding must use providers, not `System.getProperty`

The desktop `Test` task forwarded CLI flags by reading `System.getProperties()` at
configuration time. The configuration cache snapshots that read, so a flag passed on
a later CLI invocation silently never reached the forked test JVM. Provider reads
(`providers.systemProperty(...)`) are tracked as configuration inputs — changing the
flag invalidates the cache.

### 5. Failure diagnostics must not hang on a never-idle scene

`FailureBundle.capture`'s `captureToImage` blocks on `EventQueue.invokeAndWait`; a
composition that never reaches idle (infinite redraw, indeterminate progress) never
releases it, so the *diagnostic path* hung and masked the real failure. It now
honours `-Dsingularity.test.screenshot=false`. A coroutine timeout cannot cancel a
blocking EDT wait — skip, don't bound.

## Rationale

Each fix is the cheapest contract statement that makes the failure mode loud:
a targeted list (rendering), a graph-level test (init order), lockstep bindings
(DI), tracked providers (flags), and a skip flag (diagnostics). Alternatives —
detekt rules for init-order, watchdog threads around EDT waits — were rejected as
high-complexity guards for single occurrences.

## Consequences

- Nav3State widening is now reviewable against a written contract.
- The graph test adds ~2s to the desktop suite and fails fast (10s bound) with the
  actual state message on regressions.
- New `AppDatabase` DAOs must be added to `testPlatformModule()` in the same change;
  the graph test catches a miss in one run.
- Opt-in flags (`-Dsingularity.test.log`, `-Dsingularity.test.screenshot`,
  `-Dsingularity.ui.dumpTree`, `retry.*`) now work reliably with the
  configuration cache enabled.

## Links

- Retro: `PROGRESS.md`, section "Merge into main — three regressions fixed".
- Supersedes nothing; complements `2026-09-16-nav3-desktop-in-memory-no-savedstate.md`.
