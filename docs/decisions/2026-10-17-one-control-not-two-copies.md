---
title: "One control, not two copies of one"
date: 2026-10-17
status: accepted
tags: [tooling, testing, process, ci]
---

# One control, not two copies of one

## Context

`build-output-readers` had two definitions of one positive control:

- `scripts/src/sgate/commands/_registry.py` — `GATE_CONTROLS['build-output-readers']`,
  an `Edit` the registry contributes `proves` from and nothing else executes;
- `scripts/check-gate-wiring.py` — a `ScriptGate` in `SABOTAGE_ONLY_GATES` with
  its own hand-written mutation, which is the one Part F actually runs.

They diverged, and the copy that diverged was the one nobody executed.
The registry's edit appended a bare `./gradlew :koverVerify` to the end of
`ci.yml`, which lands in `nightly-alert` — a job with no build-output reader and
one test-capable invocation, which the rule skips by construction. The probe
exercised the ignore branch and passed a deliberately sabotaged input. Run
38961950540 reported it:

```
ERROR: gate 'build-output-readers' PASSED a deliberately sabotaged input
       (.github/workflows/ci.yml).
```

The mutation itself was fixed upstream in #698 — the registry now inserts a
reader of `shared/build/test-results/jvmTest` immediately after `Coverage floors`
(`./gradlew :koverVerify`), which is the ordering the rule forbids, and
`check-gate-wiring.py` proves it. What #698 did not remove is the second copy: the
gate file still carries its own mutation, so the two can drift apart again with
nothing comparing them.

## Idea

- **A.** Leave both. The registry entry is correct now; the duplicate is untidy.
- **B.** Execute the registry's control from the gate file, so the entry with
  `sabotage=None` has exactly one definition.
- **C.** Convert every `ScriptGate` to registry data in one change.

## Decision

**B**, and deliberately not C.

`control_from_edit` builds a sabotage callable from a registry `Edit` for the five
text-mutation kinds (`replace`, `append`, `drop_lines`, `regex`, `age_dir`).
`_prefer_registry_controls` fills any `ScriptGate` whose `sabotage` is `None` from
the `GATE_CONTROLS` entry of the same name, and runs at the bottom of the module —
`_load_registry` is defined further down, and the builders must exist before a
control can be built from one.

An unrecognised edit kind raises. Skipping it would leave a gate with no control
at all, which is the failure this file exists to report, and it would report it as
green.

Entries that still carry their own `sabotage` are untouched. Converting the rest
of the table is a separate change that should measure how many remaining twins are
also divergent; doing it blind would either redden CI on untriaged findings or
silently skip the ones whose edit kind does not map.

## Rationale

- The original defect was not a careless mutation — the mutation is now careful.
  It was two definitions and no comparison, so "this gate has a positive control"
  was true of a control that could not fail. Fixing one copy leaves the structure
  that produced it.
- ADR `2026-10-15-gate-registry-carries-controls-as-data` already decided that
  controls live as data. This is that decision applied to the one table it did not
  reach.
- The proof is the mutation, not the refactor: with the registry's edit applied to
  `ci.yml`, `check-build-output-readers.py` exits 1 and names `Coverage floors`;
  on the clean tree it exits 0; `check-gate-wiring.py` reports
  `ok build-output-readers: fails on sabotage`.

## Consequences

- A `ScriptGate` may now carry `sabotage=None` when its control is declared in the
  registry. The registry becomes load-bearing for those gates: an edit anchored to
  text that moved now raises (`_replace` refuses a no-op) instead of quietly
  certifying an unchanged file.
- `build-output-readers`'s Part F `why` is now the registry's sentence, which is
  longer than a one-liner. That is the intended cost — the error message quotes the
  reason, so a vague one cannot hide a vacuous control.

## Links

- `scripts/check-gate-wiring.py` — `control_from_edit`, `_prefer_registry_controls`
- `scripts/src/sgate/commands/_registry.py` — `GATE_CONTROLS['build-output-readers']`
- Runs: <https://github.com/gazon1/sing/actions/runs/38961950540> (the control
  passing a sabotage), <https://github.com/gazon1/sing/pull/698> (the mutation fixed)
- [2026-10-15-gate-registry-carries-controls-as-data.md](2026-10-15-gate-registry-carries-controls-as-data.md)
- [2026-10-16-readers-first-and-nothing-ran.md](2026-10-16-readers-first-and-nothing-ran.md) — the ADR that added the gate this control proves