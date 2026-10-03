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
forkEvery = 1
maxParallelForks = 2
```

**Performance gate:** if `:desktopApp:test` duration grows > 25%, degrade to methods-only (`mode.default = same_thread` only). Document the remaining cross-class Kermit log mixing as a known limitation.

### Debt

[TBD — populated from MR retrospectives]

## Links

- `2026-09-30-ultron-ideas-evaluation.md` — Ultron ideas evaluation
- `2026-09-30-desktop-test-diagnostics.md` — FailureBundle rationale (original)
- `2026-09-30-test-infra-known-gaps.md` — known gaps (tree not a file — now closed)
