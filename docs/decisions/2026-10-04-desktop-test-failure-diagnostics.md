---
title: Desktop test failure diagnostics — step recording and bundle enrichment
date: 2026-10-04
status: accepted
tags: [testing, desktop-compose, ui-tests, failure-bundle]
---

## Context

A failing desktop flow test currently requires re-running with diagnostic flags to understand what happened. The FailureBundle (screenshot.png + db-state.txt + kermit.log) is incomplete:
- No step history: which actions preceded the failure is invisible from the bundle
- Semantics tree only as suppressed exception in JUnit XML (not a file in the bundle)
- `awaitTag` timeout message does not distinguish "element never appeared" from "element appeared but check kept failing"
- Concurrent desktop tests share a JVM and corrupt each other's Kermit logs and bundle directories
- `checkA11y` parameter on `runDesktopAppTest` is declared and documented but never wired
- `HarnessConventionTest` scans only `feature/flows` but one flow test lives in `feature/agenda/`

Five ideas from the Ultron talk (step as report unit, unified interception point, retry-until-timeout, visual element binding, soft assertions) were evaluated in `2026-09-30-ultron-ideas-evaluation.md`.

## Idea

Enrich the FailureBundle so every failure is diagnosed from the bundle alone, without re-running. Add step recording, write the semantics tree as a file, improve timeout diagnostics, and isolate test runtime. Wire the dead `checkA11y` parameter.

## Decision

**Taken:**
- И1 (step as report unit) — `StepRecorder` with `step(name, detail, block)` wrapper around all helpers
- И2 (unified interception point) — `step()` as the single interception point; hooks for "before/after/on-error" are implicit in the try/catch/finally structure
- И3 (timeout diagnostics) — poll counter + elapsed time + preserved original exception in `awaitTag` and analogues
- И4 (visual element binding) — annotated screenshot (`screenshot-annotated.png`) with bounding-box overlays
- Runtime isolation via `same_thread` for both modes (forkEvery=1 dropped: +93s overhead for 22 tests)
- `checkA11y` warn-wiring: violations written to stdout and `a11y.txt`; fatal only with `-Dsingularity.test.a11y=fail`
- Raw selector guard in `HarnessConventionTest`: scans all `*FlowTest.kt` under `src/jvmTest`; raw calls must be in `test/helpers/` or declared in `EXEMPT_RAW_TAGS` with reason; two-way validation (stale exemptions caught)
- `HarnessConventionTest` scan root expanded from `feature/flows/` to full `src/jvmTest/`

**Not taken:**
- Soft assertions (`assertAll`) — low value; defer to later
- Allure — no JVM target (`2026-09-30-ultron-ideas-evaluation.md`); revisit if ultron issue #94 closes with a JVM target

## Consequences

### Added artifacts in FailureBundle

| Artifact | When | Format |
|---|---|---|
| `steps.txt` | always on failure | `+offset name(detail) OK/FAIL duration` |
| `tree.txt` | always on failure | raw `onRoot().printToString()` |
| `nodes.txt` | always on failure | tag/text/contentDescription/Selected/bounds per node |
| `nodes-diff.txt` | on failure, when a baseline exists | tag inventory appeared/disappeared vs baseline snapshot |
| `screenshot-annotated.png` | when screenshot enabled | image with bounding boxes and tag labels |
| `a11y.txt` | when `checkA11y = true` and violations found | one line per violation |

### Runtime isolation configuration

```
junit.jupiter.execution.parallel.mode.default = same_thread
junit.jupiter.execution.parallel.mode.classes.default = same_thread
# forkEvery = 1 NOT used: 22 tests × JVM fork = +93s overhead (2m53s vs 80s)
# maxParallelForks not set (governs concurrent forks, irrelevant without forkEvery)
```

**Performance:** measured with warm config cache. Baseline 34s → with same_thread 80s (~2.4×). `forkEvery=1` alone adds ~93s overhead (173s total). Decision: keep same_thread (intra-class safety), drop forkEvery (cost too high).

**Known limitation:** `same_thread` on both axes prevents intra-class races. Inter-class Kermit log mixing remains possible (classes run sequentially in one JVM, but without forkEvery each class reuses the same Kermit writer list). This is documented as a known limitation.

### Debt

**MR-1 findings:**

- Raw selector calls (`onNodeWithTag`, `onNodeWithText`, `onNodeWithContentDescription`) exist throughout flow tests — these are the target of the guard in MR-3. Count: ~40 call sites in `feature/flows/` (confirmed by grep). All must be either migrated to helpers or declared in `EXEMPT_RAW_TAGS` with reasons. The guard in `HarnessConventionTest` will enforce this.
- `DesktopAppBootTest` and `CelebrationTest` also contain raw selectors — these are not flow tests but are in `src/jvmTest`. The guard should cover all of `src/jvmTest`.
- `savedAgendaCreateFlowTest` is outside the current `HarnessConventionTest` scan root (`feature/flows/` only) and passes no `checkA11y` — the MR-3 guard expansion will catch it.
- forkEvery=1 was tried and removed: 22 tests × JVM fork = 93s overhead. Not worth the isolation benefit for the current suite size.

**MR-2 findings:**

- `SemanticsProperties.TestTag` access via `node.config.getOrNull()` requires importing from `androidx.compose.ui.semantics` — importing from `androidx.compose.ui.test` causes silent shadowing and unresolved-reference errors at call sites.
- `Color.toRgba()` in Compose 1.12.0 returns `BigInteger`, not `IntArray` — use `java.awt.Color` directly for AWT rendering (Red/Gray constants also unavailable on the aliased type; use RGB constructors).
- `onAllNodesWithTag("*")` is a literal tag match, NOT a wildcard — returns empty list. The correct way to traverse all nodes is via `onRoot().fetchSemanticsNode()` + recursive `visit()` using `SemanticsNode.children`, as done in `A11yCheck.scan()`.
- `fetchSemanticsNode()` on `Root` requires `useUnmergedTree` parameter; `fetchSemanticsNodes()` on collection returned by `onAllNodesWithTag` takes no parameters.
- Annotated screenshot is written even when no tagged nodes are found (0 is a valid count); the screenshot and nodes.txt are independent artifacts.

**MR-3 findings:**

- `checkA11y` was declared in `runDesktopAppTest` but never wired — added warn-wiring: violations go to stdout + `a11y.txt` always; fatal only with `-Dsingularity.test.a11y=fail`.
- `SavedAgendaCreateFlowTest` was the only flow without `checkA11y = true` — fixed.
- `HarnessConventionTest` scan root expanded from `feature/flows/` to `src/jvmTest/kotlin/com/singularity/todo` — now covers all 16 flow tests including the one in `feature/agenda/`.
- 13 flow tests contain raw selector calls outside helpers — all added to `EXEMPT_RAW_TAGS` with reasons; `HarnessConventionTest` now enforces this.
- Two-way guard: violations require exemption with reason; exemptions without actual raw calls are caught as stale.

**Follow-up refactor (post-MR-3) findings:**

- Raw selector migration completed: 12 flow tests migrated to a 16-helper set (`clickTag`, `assertTagDisplayed`, `awaitText`, `typeIntoTag`, …); `EXEMPT_RAW_TAGS` shrank from 15 entries to composable-level tests only (`runIsolatedComposeTest` users are out of the harness convention by design).
- Guard false positives: unused `import androidx.compose.ui.test.onNode…` lines match the raw-selector regex; the guard strips import lines before matching. The guard file excludes itself (it quotes selector names in its own pattern/KDoc).
- Flag wiring gap: `-Dsingularity.test.a11y` was never propagated to the test JVM (missing from the `providers.systemProperty` whitelist in `build.gradle.kts`) — the fatal-a11y mode was unreachable until fixed alongside `steps`/`baseline`.
- Timing profile via `scripts/step-duration-report.py`: `tapTab` dominates (drawer animation, ~0.35s median); `awaitTag` p95 ≈ 0.1s — the 5s `TIMEOUT_MS` has ample headroom, no flaky-step anomalies.

## Remaining Debt

The following items were identified during implementation but deferred because they require more than minor fixes:

### Raw selector migration (15 EXEMPT_RAW_TAGS entries)

13 flow tests and 2 non-flow tests (`DesktopAppBootTest`, `CelebrationTest`) contain raw `onNodeWithTag`/`onNodeWithText`/`onNodeWithContentDescription` calls outside `test/helpers/`. All are currently exempted with "predates helper migration" reasons. The exemptions are a snapshot of technical debt, not a design decision.

**What to do:** Create helpers for the missing selectors (`awaitTagByText`, `awaitTagByContentDescription`, `awaitTagByRole`) and migrate call sites one file at a time. Each migration removes one exemption and tightens the guard. This is a pure refactor with no behavioral change.

**Why deferred:** Each file has 1–17 raw calls; doing them all in one MR would be a high-risk change. The two-way guard ensures exemptions don't go stale while migration proceeds incrementally.

### Koin KOIN-W003: dynamically-computed module set — ACCEPTED TRADEOFF (reclassified)

`coreLoggingModule()` is loaded with a conditional/spread that the Koin compiler cannot verify at compile time:

```
w: [Koin][KOIN-W003] Graph not verifiable at compile time: the entry point loading
coreLoggingModule is loaded with a dynamically-computed module set (a conditional,
spread, or variable), so the assembled graph is unknowable here.
  at: TaskDetailCoordinatorGraphTest.kt:47
```

**Investigation result (this MR):** NOT a defect. The warning fires identically at the production entry point (`main.kt:33`, same list-composition shape) and in `DesktopAppHarness`. It is the unavoidable consequence of the **accepted** aggregator pattern from ADR `2026-09-27-di-module-aggregator-narrative.md`: `domainModule(): List<Module>` exists precisely to avoid `includes()`'s Koin 4 scope-isolation bug (child-scope bindings invisible to sibling modules). The two "fixes" are both worse:

- `includes()` — reintroduces the isolation bug the aggregator was built to kill;
- inlining all per-domain `*Module()` calls at every entry point — duplicates the facade and drifts from it.

**Mitigations already in place:** the compiler plugin still verifies every entry point that does pass a static module set, and `TaskDetailCoordinatorGraphTest` exercises the real graph end-to-end at runtime (a broken binding fails the test with a timeout + error state, not silently).

**What to do:** nothing, unless the Koin compiler plugin gains support for tracing list-returning aggregator functions. No refactor scheduled.

### Inter-class Kermit log mixing (same_thread, no forkEvery)

With `same_thread` on both axes, all test classes run sequentially in one JVM. The Kermit ring-buffer writer is shared across classes, so `kermit.log` in a failure bundle may contain log lines from a different test class that ran before or after.

**Current mitigation:** `resetKermitWriters()` is called before each test to clear and re-initialize the writer list.

**What to do:** Either (a) accept the limitation — the mix is harmless for debugging since the ring buffer is small and recent entries dominate, or (b) investigate whether `forkEvery=1` with a reduced suite (e.g. only flow tests) is acceptable if the isolation gate is recalibrated.

**Why deferred:** Requires performance measurement with a realistic subset of tests to determine if isolation cost is justified.

### `CelebrationTest` uses raw `onNodeWithText` for decorative UI

`CelebrationTest` and `DesktopAppBootTest` are not flow tests but contain raw selectors. They are exempt from `EXEMPT_RAW_TAGS` but not covered by `HarnessConventionTest` (which only scans `*FlowTest.kt`). A separate convention test or inclusion criteria should cover them.

**What to do:** Either extend `HarnessConventionTest` to also scan non-flow tests, or create a `NonFlowConventionTest` for `DesktopAppBootTest` and `CelebrationTest`.

**Why deferred:** These tests are structurally different (don't use `runDesktopAppTest` harness) — requires separate guard design.

## Links

- `2026-09-30-ultron-ideas-evaluation.md` — Ultron ideas evaluation
- `2026-09-30-desktop-test-diagnostics.md` — FailureBundle rationale (original)
- `2026-09-30-test-infra-known-gaps.md` — known gaps (tree not a file — now closed)
