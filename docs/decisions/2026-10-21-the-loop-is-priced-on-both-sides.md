---
title: The loop is priced on both sides, and its overlap is a defect
date: 2026-10-21
status: accepted
tags: [gates, measurement, developer-experience]
---

# The loop is priced on both sides, and its overlap is a defect

## Context

The fourth wave priced the local loop: `sgate run-all` times every gate it runs,
`check.sh` times every Gradle step, and the baseline is committed with the machine it
was measured on. Two things were still missing, and both were visible in the numbers
that had just been published.

**The CI loop was unpriced.** The local loop was measured; the loop that *blocks
merges* was not. It is the one whose cost delays every change, and nobody could say
what it cost — the same defect, one level up.

**The measurement said the loop checks itself twice.** The published table said
`gate wiring` costs 2 min 27 s and `gate liveness` 1 min 10 s, while `static gates` —
the same thirty-four gates — cost 38 s. The three steps are not three checks over
disjoint work: they are three passes over the same corpus. `find-unwired-surfaces.py`
alone is executed **seven** times per loop, 26.1 s each, 182.7 s in total.

Two concrete defects sat inside that overlap. `script-conventions` was declared
**twice** in `SABOTAGE_ONLY_GATES`, and both declarations were filled from the same
registry entry — so the gate ran four times with one probe, and the two runs were
indistinguishable in the report. And `GATE_CONTROLS` declared the same key **twice**:
a dict literal drops the earlier one without a word, so the probe that ran was not the
probe anybody was reading.

## Decision

**The CI loop is priced too, the overlap is fixed where it is a defect, and the
measurement is used for a decision.**

- The published page carries a second section: the last CI run's jobs and what each
  took, read from the runner's own record. Not a median and not a baseline — a hosted
  runner is not this machine, and the local baseline refuses to compare across
  machines for the same reason. A record, not a threshold.
- That record is the **branch's** last **finished** run, and it carries the run's
  verdict. A run that is still going has no duration for the jobs that have not
  finished, so recording it would publish the price of half a loop as the price of
  the loop. A pull request's run is the price of that pull request, not of the loop
  that blocks every merge — and a reader who finds a price written next to a foreign
  branch's failed run reads a price for something that was never measured. The
  verdict travels with the durations: a price next to a failed run, without the
  verdict, reads as the price of a green one.
- The two duplicate declarations are gone, and a test says a control may not be
  written twice. The old guard could not see it: it compared the control lists with
  the *fixture* list, and a list that duplicates itself is invisible to a comparison
  with something else.
- The JSON-contract gate probes its gates **concurrently**. It used to run them one
  after another, so its cost was their sum — and `run-all`, which runs the same gates
  in parallel, was waiting for it. Measured on the same machine: 58.9 s serial,
  33.6 s concurrent. The gate is invoked three times per loop, so the saving is
  paid three times over.
- `just tests::affected` runs only the checks a change makes worth running, computed
  from the module graph the build files declare: a change in `:core-base` reaches
  `:adr-core`, then `:shared`, then the four modules that compile against it.

## Rationale

A measurement that is not used is a curiosity. The published table said the loop
spends five minutes checking its own checks; that is a fact about the loop, and the
fact changes what one would do next. Two of the three most expensive steps in the
local loop are not about the application at all.

The duplicates were not a design. A control that is declared twice is a process run
that proves nothing new, and — worse — a report that cannot tell the reader which run
was the real one. The registry's shadowed declaration is the worse of the two: the
probe that ran was the one nobody was reading, and nothing said so.

The CI numbers are published rather than compared. A hosted runner's duration says
what the CI loop cost *on that runner*, and the honest way to say it is a record of
one run with the run it came from. Turning it into a median would invent a stability
that a pool of ephemeral runners does not have.

The fast lane is a lane and not a gate: it runs a subset of the checks, and CI still
runs all of them. It exists because the measurement made the question answerable —
"what does this change require?" is a question about the module graph, and the module
graph is declared in the build files.

## Consequences

- The published page prices both loops: the local one with medians and a machine, the
  CI one as a record of the branch's last finished run, with the run's verdict.
- The local loop is measurably cheaper: the JSON-contract gate runs concurrently, and
  one duplicated control is gone.
- A control written twice fails a test, in the registry and in the control lists.
- `just tests::affected` is a way to run less, not a way to pass CI.
- The liveness probe is still in the local loop. It is a full second pass over the
  corpus by construction — the CI job already runs it separately — and removing it
  would change what the local loop rehearses. That is a decision for the owner, and
  it is not taken here.

## Links

- `scripts/src/sgate/commands/gate_costs.py` — the owner of the measurement
- `docs/testing/gate-costs.md` — the published cost, both sides
- `scripts/check-gate-json-contract.py` — the concurrent probes
- `scripts/check-gate-wiring.py`, `scripts/src/sgate/commands/_registry.py` — the
  duplicates, gone
- `scripts/changed-modules.py` — the module graph, read
- ADR `2026-10-21-the-cost-of-the-loop-is-measured` — the measurement itself
