---
title: "One written wait budget, held by a gate"
date: 2026-10-20
status: accepted
tags: [testing, android, maestro, ci, tooling]
---

# One written wait budget, held by a gate

## Context

Two runners wait for the same screen in this repository: the Android instrumented suite
(`androidApp/src/androidTest/.../ShellProbe.kt`, Kotlin) and the Maestro flows
(`Maestro/**/*.yaml`). They are separate runners and separate languages, so they cannot
share a constant — the number that is right for one has to be written down twice, and
nothing held the two copies together.

That was not a hypothetical. The Maestro helper that reaches the agenda shell had already
learned that 8 s is not enough for a cold start on a loaded CI runner and moved to 30 s,
with the reason written next to it. The instrumented suite kept 8 s — and `android-device-tests`
was red on every run of `main` for days, with *"Today tab should be visible after cold
start"*, a message about a screen that was on screen 1.35 s after the activity resumed.

Worse, the Maestro layer had drifted against *itself*: forty-one of its waits for the same
`nav_tab_today` existed with five different timeouts (5 s, 8 s, 10 s, 15 s, 30 s). The one
that had been reasoned about was the only one of the five that was right. Every one of the
others was as brittle as the instrumented suite had been.

## Decision

`config/docs/ui-wait-budgets.txt` is the single written source of the budgets
(`<name> <milliseconds>` per line), and `scripts/check-ui-wait-budgets.py` holds every layer
that waits to it:

- the instrumented suite's wait constants must equal the catalogue;
- a wait constant the catalogue does not carry is a finding — a budget nobody promised is a
  budget nobody keeps in step;
- every Maestro wait for the shell must use the shell budget, in every flow and every helper;
- a wait with no timeout at all is a finding — it waits for Maestro's default rather than for
  the budget the catalogue promises.

All forty-one Maestro waits were brought to the shell budget of 30 s.

The catalogue is a text file, and the gate reads it, rather than the gate *generating* the
constants. The alternative — generating the Kotlin constants and the YAML timeouts from one
source — cannot work: YAML cannot read a Kotlin constant, and a generator for YAML that
rewrites flow files is a much larger machine than the problem deserves.

## Rationale

The gate does not care whether 30 s is the right number. It cares that there is one number,
and that the layers that wait on it agree. A number that is wrong for everybody is a decision
to make once, in the catalogue, where the reasoning can be written down next to it; a number
that is wrong in one place is a flake waiting for a busy runner.

The catalogue file follows the shape the repository already uses for
`config/docs/maestro-flows.txt` — a written promise plus a gate that holds the tree to it —
so the pattern is not invented here.

The gate is registered where every gate in this repository is registered: in
`scripts/src/sgate/commands/_registry.py` (with a positive control), in
`scripts/ci/static-gates.sh`, and in `scripts/check-gate-wiring.py`. Its control sabotages
the catalogue, because the catalogue is what the gate reads.

## Consequences

- A new wait budget is a line in the catalogue plus a constant in the instrumented suite,
  and the gate says so if one exists without the other.
- The Maestro flows carry one timeout for the shell. A flow that waits for the shell with
  5 s is now a gate failure rather than a flake nobody reproduces.
- The pre-push hook runs the gate too, so a drift is caught before the push and not in CI.

## Links

- `config/docs/ui-wait-budgets.txt` — the catalogue
- `scripts/check-ui-wait-budgets.py` — the gate
- `scripts/tests/test_check_ui_wait_budgets.py` — the gate's tests
- `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/ShellProbe.kt` — the constants
- `docs/decisions/2026-10-20-the-instrumented-ui-suite-waits-for-the-shell.md` — why the
  instrumented suite waits the way it does
