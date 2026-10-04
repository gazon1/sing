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

**What to do:** see the forkEvery-subset experiment in the roadmap below — the question is closed with data, not by argument.

**Resolved since the first draft of this section:** raw selector migration (was 15 EXEMPT entries — now complete, 3 composable-level exemptions remain by design); non-flow guard coverage (`DesktopAppBootTest` migrated, guard widened to all `jvmTest`, composable-level tests exempt with reason).

## Future work (roadmap, priority order)

Recorded 2026-10-04 from the post-implementation retrospective. Each item names its trigger, so a future session can pick any of them without re-deriving context.

### 1. CI integration of the failure bundle

The plan's core promise — "a CI failure is explained from the bundle alone, without a re-run" — is only true locally until CI publishes the bundle.

- CI job runs `:desktopApp:test -Dsingularity.test.steps=true` and uploads `build/diagnostics/**` as artifacts on failure
- `nodes-diff.txt` additionally attached as a suppressed exception to the failure, so it is visible in the JUnit XML report without downloading artifacts
- Suite-duration gate: baseline measured (34–50s); turn the plan's manual "+25% → degrade" gate into an automatic check

### 2. `awaitTextGone` / `awaitContentDescriptionGone` helpers

The helper set covers waiting for *arrival* (tags, text, contentDescription) but for *departure* only the tag-based `awaitTagGone` exists — text/contentDescription have one-shot `assertTextNotExists`/`assertContentDescriptionNotExists`.

Known latent race: `CalendarFlowTest.view_mode_switches_from_month_to_week` checks `assertTextNotExists(monthTitle)` right after `clickText("Week")` — exactly the "disappeared because of what the test just did" case the project's own rules say must wait. The race predates the migration (the migration preserved the original one-shot semantics); fix = two new helpers + one call site (~15 min).

### 3. Flip `checkA11y` to default-on

All flow tests already pass `checkA11y = true`; the opt-out machinery (`EXEMPT_A11Y` set with mandatory reasons) already exists in `HarnessConventionTest`. Change the harness default to `true` and let flows opt *out* with a reason. The a11y backlog then shrinks automatically as flows are written, instead of depending on memory.

### 4. Per-test baselines + staleness marker

The regression baseline is per test class — "last passing test in the class wins" — so a class whose tests show different screens diffs against the wrong snapshot.

- Extract the test *method* name from the stack trace (same technique as `currentTestClassSimpleName`) and key the baseline per method
- Add a generated-at timestamp line to `tags.txt`; `nodes-diff.txt` flags stale baselines instead of reporting noise after a legitimate UI change

### 5. forkEvery=1 subset experiment (closes the Kermit mixing question)

Run only the flow-test subset with `forkEvery=1` and measure with the timing tooling built in this effort. Either the cost is acceptable for the subset (adopt it, kermit.log becomes per-class truthful) or the limitation is closed permanently with data in this ADR.

### 6. Flakiness radar

`scripts/step-duration-report.py` aggregates one run today. Keep a run history (e.g. append to a CSV under `build/` or a checked-in metrics dir) and flag steps whose p95 systematically climbs toward `TIMEOUT_MS` (5s) — the flake candidate detector.

### 7. Split `DesktopNavigation.kt` — DONE (2026-10-04)

The helpers file grew to ~450 lines / 20+ functions, and the name now lies: navigation is the minority of its content. Mechanical, zero-behavior split: `DesktopNavigation` (tapTab/openDrawer/goBack/assertCurrentTab) · `DesktopAssertions` (await*/assert*) · `DesktopInteractions` (click*/type*).

**Landed as measured, not as predicted here.** The file was 510 lines / 29 helpers at split time, not "~450 / 20+". Final: `DesktopNavigation` 135 · `DesktopAssertions` 328 · `DesktopInteractions` 61. `TIMEOUT_MS`, `TAG_PATTERN` and `explainMissingTag` went with the wait/assert helpers rather than navigation, so the navigation file no longer carries the timeout every other file imports from it.

All 29 signatures were diffed before/after (identical), and `:desktopApp:test` ran green — 27 classes / 77 tests. Worth recording *why* this was linted rather than merely compiled: `desktopApp/build.gradle.kts` sets `source.setFrom("src/main/kotlin", "src/jvmTest/kotlin")`, so `jvmTest` is inside detekt's scope. But `TooManyFunctions` excludes `**/jvmTest/**` and `LargeClass` allows 600 lines, so this 510-line file was **not** a violation — detekt staying green does not show the split was needed. It was worth doing for readability, which is the same class of judgment as B1's formatter merge, and neither is catchable by a rule.

### 8. Android parity: explicit decision required

Everything here is desktop-only. Either port `StepRecorder`/`FailureBundle`/the convention guards to `shared`'s androidHostTest, or record the counter-decision ("Android E2E lives in Maestro; the failure bundle is a desktop concept") in an ADR so the question does not resurface every quarter.

## Links

- `2026-09-30-ultron-ideas-evaluation.md` — Ultron ideas evaluation
- `2026-09-30-desktop-test-diagnostics.md` — FailureBundle rationale (original)
- `2026-09-30-test-infra-known-gaps.md` — known gaps (tree not a file — now closed)
