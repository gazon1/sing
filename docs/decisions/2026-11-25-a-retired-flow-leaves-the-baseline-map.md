---
title: "A retired flow keeps its name in the frozen set and leaves the baseline map"
date: 2026-11-25
status: accepted
tags: [maestro, testing, qualification, traceability]
issue: 3143
---

## Context

Retiring a Maestro flow touches three qualification artifacts: the catalogue
(`config/docs/maestro-flows.txt`), the frozen set
(`config/docs/maestro-flow-qualification-initial.yaml`) and the baseline map
(`config/docs/maestro-flow-qualification-baseline.yaml`). The frozen set is
pinned by `FROZEN_SET_DIGEST` and the baseline map by `BASELINE_DIGEST`, both
in `scripts/check-maestro-flow-qualification.py`; the gate refuses to run
against an artifact whose digest does not match.

The two artifacts have different jobs. The frozen set is the **record** of the
flows that were in a blocking scope when the rule was introduced; the baseline
map is the **admission table** — a flow is admitted when it is in the frozen set
*and* its current fingerprint still matches the map.

The repository holds two conflicting precedents for what a retirement does to
them:

- #3115, #3120, #3121, #3122 and #3123 removed the retired flow from **both**
  the frozen set and the baseline map and refreshed both pins (ADR
  `2026-11-23-the-unreachable-system-calendar-flow-is-removed-not-replaced`).
- #3119 (ADR `2026-11-23-retire-the-nav-menu-parity-maestro-flow`) and #3142
  kept the retired flow in **both**.

The written policy says the frozen set "only grows: a flow that is retired
stays in it, because it was in a blocking scope when the rule was introduced"
(`docs/testing/maestro-flow-qualification.md`), and the baseline map's own
header says "This map only shrinks". The retirement rule for the pair was
never written down, so each retirement picked one of the two shapes.

The correctness review of PR #3206 (issue #3143) raised it as a finding: the
baseline map kept the fingerprints of three deleted flows, so a flow that came
back with the same content would be admitted without anybody qualifying it.

## Idea

1. **Remove the retired flow from both artifacts** — the #3115/#3123 shape. It
   matches the most retirements, but it contradicts the frozen set's documented
   role as a record and the "only grows" rule the review holds.
2. **Keep everything** — the #3119/#3142 shape. The risk stands: a returning
   flow is admitted by a fingerprint nobody re-ran.
3. **Split the two artifacts along the jobs they already have**: the frozen set
   keeps the name, the baseline map drops the fingerprint, and the rule is
   written down where the artifacts live.

## Decision

**Option 3.**

- A retired flow **keeps its name in the frozen set**. `FROZEN_SET_DIGEST` is
  not touched by a retirement; the name is the record, and a name without a
  file behind it qualifies nothing.
- A retired flow **leaves the baseline map** in the same change that removes it
  from the catalogue, and `BASELINE_DIGEST` is refreshed to match the map.
- A flow that comes back to the catalogue is therefore qualified (or excepted)
  — never admitted by a fingerprint that was taken before the flow was retired.
- The rule applies to the map for **every** retired flow, not only the ones
  retired from here on: the two entries earlier retirements left behind are
  dropped as well, so every entry left in the map points at a flow that is
  still in the catalogue. The map is not the whole catalogue: a flow
  introduced after the rule was introduced has no entry and enters through
  qualification or an exception.

## Rationale

**The two artifacts answer different questions.** "Which flows were in a
blocking scope when the rule was introduced?" is a question about the past, and
the frozen set is the answer to it; removing a name from it would rewrite
history to make the current catalogue look like the one the rule was
introduced against. "Which flow content may enter a blocking scope without
being qualified?" is a question about the present, and the baseline map is the
answer to it; a fingerprint of a flow that no longer exists answers nothing
and admits nothing — except one thing: if the flow comes back with the same
content, that stale fingerprint admits it without anybody re-running it. That
is the one admission the pinned baseline exists to prevent, so the fingerprint
of a retired flow has to go with the flow.

**"Only shrinks" is a constraint on the map, not a promise to keep it
unchanged.** The map's header states the direction it may move in; the frozen
set's header states that it does not move at all. A retirement is exactly the
event the map's shrink is for.

**The gate stays dumb, the review holds the policy.** The gate cannot tell a
retirement from an edit that admits an unqualified flow — it sees one artifact
and one digest — so the pin is refreshed in the same change, and the diff of
the change is what says "this flow is no longer admitted by its old
fingerprint".

## Consequences

- `config/docs/maestro-flow-qualification-baseline.yaml` loses the three
  fingerprints of the flows retired by #3143, and `BASELINE_DIGEST` in
  `scripts/check-maestro-flow-qualification.py` is refreshed to the new map.
  The frozen set and its pin are unchanged.
- The shape of a retirement is now written down in four places instead of
  two: the baseline map's header, `docs/testing/maestro-flow-qualification.md`,
  the gate's own docstring in `scripts/check-maestro-flow-qualification.py`
  and this record.
- The retirements that removed the flow from the frozen set as well (#3115,
  #3120, #3121, #3122, #3123) are not rewritten — a decision record is the
  record of the change it decided, and their artifacts are history too. The
  rule applies from here on; a future retirement that wants the frozen set to
  shrink says so in its own change.
- The two leftovers of earlier retirements — `nav/02-menu-parity.yaml` (#3119)
  and `search/03-clear.yaml` (#3142), both retired without touching the
  artifacts — leave the map in the same change that settled the rule, so the
  map no longer admits a flow that does not exist. Their names stay in the
  frozen set, which is what the frozen set is for.

## Links

- Issue: https://github.com/gazon1/sing/issues/3143
- Qualification policy: `docs/testing/maestro-flow-qualification.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Baseline map: `config/docs/maestro-flow-qualification-baseline.yaml`
- Frozen set: `config/docs/maestro-flow-qualification-initial.yaml`
- Related: `2026-11-23-the-unreachable-system-calendar-flow-is-removed-not-replaced`,
  `2026-11-23-retire-the-nav-menu-parity-maestro-flow`
