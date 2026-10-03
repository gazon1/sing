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
- Runtime isolation via `forkEvery = 1` + `same_thread` for both modes
- `checkA11y` warn-wiring with opt-in fail mode

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

## Links

- `2026-09-30-ultron-ideas-evaluation.md` — Ultron ideas evaluation
- `2026-09-30-desktop-test-diagnostics.md` — FailureBundle rationale (original)
- `2026-09-30-test-infra-known-gaps.md` — known gaps (tree not a file — now closed)
