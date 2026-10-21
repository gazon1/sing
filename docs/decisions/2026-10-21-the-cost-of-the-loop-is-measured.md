---
title: The cost of the loop is measured, not guessed
date: 2026-10-21
status: accepted
tags: [gates, measurement, developer-experience]
---

# The cost of the loop is measured, not guessed

## Context

The local verification loop grew by minutes at a time, and nobody could say so. A wave
added an Android host test; another added a module to the detekt sweep; a third added a
gate to the static suite. Each of those is a few minutes on a machine that was already
slow, and the sum of them was invisible: there was no number for "what does the loop
cost now", so there was no number for "what did that change cost", and so the next wave
was priced at nothing.

The infrastructure for measuring it had already been written and never connected:
`config/docs/gate-benchmarks.json` existed, empty, and `sgate check-benchmarks` existed
to read it. A command whose baseline is empty cannot pass for any gate name, so it could
only ever fail — and it ran no check script, so nothing ran it either.

## Decision

**The measurement comes from the runs that happened anyway, and the cost is a fact in
the repository.**

- `sgate run-all` writes the duration of every gate it ran. `check.sh` writes the
  duration of every Gradle step it ran. Nothing is executed twice to be timed.
- `config/docs/gate-benchmarks.json` holds the recorded samples and the *environment*
  they were measured in. It is committed, so the cost of the loop is in the repository
  rather than in someone's memory.
- `docs/testing/gate-costs.md` publishes the baseline as a generated block, and the gate
  **"the published gate costs are current"** says the page and the baseline agree. A
  hand-edited number is a failing gate, not a fact.
- The local report names what got slower than the baseline, and **refuses to compare
  across machines**: a duration measured on one machine says nothing about another, so a
  changed environment is reported as "not comparable" rather than as a regression.

The command replaces `sgate check-benchmarks`, which could not pass.

## Rationale

Measuring by running things twice is the obvious way and the wrong one here: the gates
are the expensive part, and running them again to time them doubles the cost of
answering the question "what does this cost". The runs that measured the loop are the
runs that had to happen anyway, so the measurement is free and always describes the loop
as it actually ran.

The environment is recorded because an absolute duration is not a fact about the loop —
it is a fact about one machine's loop. A laptop with a cold Gradle home and a CI runner
are not the same machine, and a committed baseline from one of them compared against the
other would be a confident, wrong answer. So the comparison refuses to draw a
conclusion it cannot support, and says which case it is in.

The gate that runs in CI compares two *committed* files. That is the question a CI
runner can answer honestly: not "is the loop slow here", but "does the published cost
still match the recorded cost". The comparison with a local measurement stays local,
because a CI runner has no local measurement to compare.

The threshold is three times the recorded median, and it is a multiplier rather than a
percentage because a gate that takes 40 ms and one that takes 40 s need different
tolerances. It is deliberately loose: this reports "look at this", not "the build is
broken".

## Consequences

- The loop's cost is published, and a change to it shows up as a number rather than as
  a feeling.
- `check.sh` times its steps; the timings are written to an untracked file and read by
  `sgate gate-costs`.
- The baseline carries the machine it was measured on. Change machines and the report
  says "not comparable" until somebody records a new baseline — which is the honest
  answer, and cheaper than a number nobody believes.
- `sgate check-benchmarks` is gone. It could not pass, and a command that cannot pass
  teaches its users to ignore it.
- The published page and the baseline are checked against each other, so a number
  cannot drift away from the record it came from.

## Links

- `scripts/src/sgate/commands/gate_costs.py` — the owner
- `config/docs/gate-benchmarks.json` — the recorded baseline
- `docs/testing/gate-costs.md` — the published cost
- `check.sh` — the timer
- ADR `2026-10-04-measurement-integrity` — the rule that a gate is a measurement
