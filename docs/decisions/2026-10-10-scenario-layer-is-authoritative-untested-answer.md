# Scenario layer is the authoritative "untested" answer; Kover per-class floor is retired

**Date:** 2026-10-10
**Status:** Decided
**Backlog:** `docs/decisions/deferred-backlog.md#untested-has-two-answers-per-class-and-per-scenario`
**Tracked as:** [#300](https://github.com/gazon1/sing/issues/300)

## Context

Two instruments report what is untested, and both present themselves as authoritative:

1. **`gaps.py`** — scans `Automated/*` Kiwi TCMS plans, reports *never-run per test class*.
   A class with zero runs is a finding. A class that runs against one scenario but not another
   is invisible because the scenario is not in scope.

2. **Scenario matrix** — per `Scenarios` plan, reports *holes*: claimed scenario targets with no
   automation. A target not in a scenario is invisible because scenarios are the only thing
   measured there.

Both are correct about different things. Both are presented as authoritative answers to the same
question: "what is untested?" A reader arriving cold has no rule for which to trust, and the two
will drift in vocabulary ("hole" means never-run in one layer and missing-from-scenario in the other).

## Decision

**The scenario layer is the authoritative source for "what is untested."**

The per-class `Automated/*` floor is retired. Kover continues to exist for code coverage
measurement (which is a different question from "what is untested").

## Rationale

The scenario layer has the right granularity for this codebase. The tests that matter are those
that exercise a user-facing scenario end-to-end. A class that runs against zero scenarios is
either dead code or an implementation detail that the scenario layer's consumers do not exercise —
either way, it is not a gap in the scenario layer's coverage.

The per-class floor measured a different thing: *never-run classes*, which conflates "never run"
with "not exercised by the scenarios that exist." A class that is run by every scenario but has
no scenario claiming it is invisible to both instruments in opposite ways:

- `gaps.py` reports it as green (it ran at least once against some plan).
- The scenario matrix reports it as a hole (it is not claimed by any scenario).

This is not a contradiction — it is two instruments answering different questions. The scenario
matrix's question is the one that matters: a target is tested if a scenario exercises it.

## What is retired

- `Automated/*` plans in Kiwi TCMS are no longer maintained as a coverage floor.
- `gaps.py` as a coverage gate is discontinued.
- The `max-never-run` baseline in `config/docs/kiwi-gaps-baseline.txt` is no longer enforced.

## What is kept

- Kiwi TCMS as a test case management tool. Scenarios live there, and their `Scenarios` plan
  continues to be the authoritative coverage record.
- The scenario matrix itself, including its holes reporting, remains the gate for "what is untested."
- `check-kiwi-gaps.py` is removed from the static gates registry (`check-gate-wiring.py`).

## Consequences

- The `check-kiwi-gaps.py` gate is removed from `scripts/ci/static-gates.sh`.
- The `config/docs/kiwi-gaps-baseline.txt` baseline file is kept (for reference, not enforcement).
- Any `Automated/*` plan maintenance in Kiwi is now voluntary rather than enforced.
- Kover JVM test data continues to be collected; it answers "what code is covered" which is a
  separate question from "what is untested by scenarios."

## Notes

This decision does not change the scenario authoring workflow. Adding a new scenario still requires
claiming its targets in the scenario JSON, and a target with no claim still appears as a hole.
The retiree is the per-class never-run floor, not the scenario authoring practice itself.
