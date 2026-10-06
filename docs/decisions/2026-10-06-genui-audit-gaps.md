---
title: "GenUI: gaps found while auditing the layer, and what each one costs"
date: 2026-10-06
tags: [genui, backlog, testing, ci]
status: deferred
---

## Context

A follow-up audit of `feature/genui/` after the A2UI refactor merged (PR #213) looked for defects
in the layer rather than for missing features. This records what was found, what was fixed, and what
was deliberately left — the last group being the ones where the cost of *building* is real and the
cost of *documenting* is not.

The framing matters for what follows: none of these are bugs. The layer has no known correctness
defect. They are places where the layer is thinner than its own documentation implies, or where a
claim made in prose is not yet enforced by a check.

## Findings

### Fixed

**`GenuiUsageCounter.neverUsed()` had no production caller.** The counter's stated purpose is
answering "should the catalog grow?", and the function that answers it was called only by its own
tests. `report()` logged the ranking, which cannot distinguish "the model never saw how to use this
component" from "nobody needed it". Now wired, and the reporting is a pure `summary()` that tests
assert. See ADR `2026-10-06-an-instrument-nobody-reads-is-not-an-instrument`.

**CI jobs could hang for six hours.** Five of six jobs in `ci.yml` and the one in `docs-audit.yml`
had no `timeout-minutes`, against a 360-minute default. See ADR
`2026-10-06-six-of-seven-ci-jobs-could-hang-for-six-hours`.

**`check-gate-wiring.py` misreported two correctly-wired gates.** `GATE_PARITY`'s KDoc described it
as a manifest of expected placements; the code treats it as a table of deviations. Two CI-only gates
were reported as undeclared rather than declared. Same ADR.

**README claimed `schema v38` against a tree at 39**, and the test that should have caught it
asserted a hardcoded `38` — so the mechanism meant to detect drift was itself the thing that went
stale. Both fixed at source.

### Checked and found sound

**The correction loop does not lose a partially-applied surface.** A first answer that applies its
`createSurface` and rejects one component leaves the accepted components on screen; the correction
turn deletes and rebuilds under the caller's identifier, and the corrected surface is what survives.
Probed directly: two attempts, corrected root node intact.

**`SurfaceController.prune()` does drop the oldest.** `Map.plusAssign`/`minusAssign` preserve
insertion order, so `keys.take(excess)` is oldest-first as documented.

**`GenuiDiGraphTest` is correctly `@Tag("fast")`.** `GenuiSession` has no database dependency, and the
mirror's `AppDatabase` binding is never resolved on that path — verified by the user's real database
file keeping its mtime across a targeted run.

## Deferred

### The rendering tests are all `@Tag("slow")`, so the fast loop cannot see a component stop drawing

`GenuiCatalogRenderTest`, `GenuiSurfaceCorpusTest` and `GenuiFirstAttemptReplayTest` are slow because
they cross the process boundary into a Compose harness. The consequence is that `-Ptest.tags=fast`
runs the parser, the catalog, the validator and the counters — every *contract* check — and none of
the *drawing* checks. A renderer registered against the right name that draws nothing is invisible
to the fast loop.

This is a correct classification, not a mistake, and the cheap fix would be wrong: what actually
belongs on the fast path is a structural check that every catalog kind has a registered renderer,
which needs no Compose harness at all. That check does not exist yet, and `find-unwired-surfaces.py`
does not cover it because a renderer *is* wired — it is wired to a name that produces nothing.

**Why deferred:** it is new work with a new failure mode, not a fix. Doing it while auditing would
have meant adding a check whose own correctness is unverified.

### The action seam is designed but not built

ADR `2026-10-06-genui-press-is-a-turn-until-it-mutates` decides that a component declares whether its
action mutates, and that mutating actions execute client-side against the AI tool registry. No part
of that is implemented: there is no `mutates` property on the catalog, and no action registry.

This is deliberate — the ADR exists so the decision is made before three forms ship rather than
after, and the current forms work through the turn path. **It is the first thing to build when the
first genuinely mutating control appears**, and the ADR is the specification for it.

### CI logs are unreadable through the API

Step-level logs return `steps: []`, artifacts return `total_count 0`, and job logs return
`BlobNotFound` on every run tried — including runs that finished minutes earlier, with a token whose
`actions` permission reads `enabled/all`. This is not a code defect and not GenUI's.

Its consequence is real and worth stating: `slow-tests`, `kover-report`, `maestro-smoke`,
`mcp-server-check` and `docs-audit` fail on `main`, and the diagnosis of *why* is currently blocked
on something outside the repository. The workarounds already applied — `tee` with unconditional
upload, `if-no-files-found: warn`, explicit timeouts — are the parts that can be fixed from here.

**Why deferred:** the fix is a repository or organisation permission, not a change to this codebase.

### `openspec validate --strict` fails 32 changes on main, all for the same reason

`--strict` promotes warnings to failures, and the dominant warning is "requirement text is very
long (>500 characters)". There are 62 of them across 32 changes — every one authored recently. The
suggestion in the warning is to move examples into scenarios or split the requirement, and both are
usually right, but the scale means the gate now reports a wall of red that nobody is going to read
one requirement at a time.

One of those 62 was this branch's own: `REQ-GC-001` stated two separable claims (the catalog is
declared once and everything derives from it; and what that declaration must contain), and splitting
it also surfaced three validation scenarios that had none of their own. Fixed. The remaining 61
belong to other changes.

**The generalisable part, and it connects to the CI ADR above:** a gate that has been green and then
goes red on 32 items at once has stopped being a signal, whatever the cause. Either the 32 get fixed
in one pass or the bound moves — but the state in between, where every run reports the same wall and
the number only goes up, trains the reader to ignore it. Which of those to do is a decision for
whoever owns the spec backlog; recorded here so it is a decision rather than a surprise.

### The Kiwi inventory bound moves more slowly than the class count

`test_kiwi_sync.ScanRepositoryTest` asserts the scanned inventory stays between 200 and 320 (now
330). The lower bound catches paths drifting out of `TEST_ROOTS`; the upper catches abstract bases
and helpers being returned to the inventory. It is a deliberate ratchet that must be raised by hand,
and it was raised twice in two days because calendar-sync and GenUI each added classes.

This is working as designed — it forced a verification that all 321 are real test classes rather
than helpers. Recorded because the next person to hit it will wonder whether raising it is allowed.

## Consequences

Nothing here blocks work. The deferred items are recorded so that the next agent finds them written
down rather than rediscovering them, and so that the reasoning behind each is available when the
trade-off is revisited.

## Links

- ADRs: `docs/decisions/2026-10-06-an-instrument-nobody-reads-is-not-an-instrument.md`,
  `docs/decisions/2026-10-06-six-of-seven-ci-jobs-could-hang-for-six-hours.md`,
  `docs/decisions/2026-10-06-genui-press-is-a-turn-until-it-mutates.md`
- Skill: `.agents/skills/singularity-todo-genui-catalog/SKILL.md`