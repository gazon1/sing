---
title: "A control nobody executes is not evidence, and it is the copy that rots first"
date: 2026-10-16
status: accepted
tags: [tooling, testing, process, ci]
---

# A control nobody executes is not evidence, and it is the copy that rots first

## Context

`GATE_CONTROLS['build-output-readers']` in
`scripts/src/sgate/commands/_registry.py` declared this mutation:

```python
Edit(kind='append',
     new="\n      - name: late coverage pass\n        run: ./gradlew :koverVerify -Ptest.tags=fast,slow\n",
     count=1)
```

Applied to `.github/workflows/ci.yml`, it lands in `nightly-alert` — the last job
in the file, which has no reader of build output and, at the time, one
test-capable invocation. The rule skips any job with fewer than two test-capable
invocations, by construction. So the probe exercised the *ignore branch*: it
passed on a deliberately sabotaged input and proved nothing about the rule.

It was never the control that ran. `check-gate-wiring.py` held its own
hand-written `ScriptGate` for the same gate, with a different mutation — one that
inserts a second Gradle invocation above the readers — and that one worked. The
registry contributed `proves` (the script is listed as controlled) and nothing
else; its `edit` was read by no code path.

Run 38961950540 reported the consequence, and the report named the registry copy:

```
ERROR: gate 'build-output-readers' PASSED a deliberately sabotaged input
       (.github/workflows/ci.yml).
```

Two copies of one control, only one of them executed, and the executed one was
the one nobody edited — so the copy that read as the evidence rotted first.

## Idea

- **A.** Fix the registry entry's mutation and stop.
- **B.** Fix the mutation, and make `check-gate-wiring.py` execute the registry's
  control instead of a hand-written twin, so there is one control to keep true.
- **C.** Convert every hand-written `ScriptGate` to registry data in one change.

## Decision

**B**, and deliberately not C.

`control_from_edit` builds a sabotage callable from a registry `Edit` for the
five text-mutation kinds (`replace`, `append`, `drop_lines`, `regex`, `age_dir`),
and `_prefer_registry_controls` fills in any `ScriptGate` whose `sabotage` is
`None` from the matching `GATE_CONTROLS` entry. An unrecognised edit kind raises
instead of being skipped: a control that silently vanished is the exact failure
this file exists to report, and it would report it as green.

The registry's mutation becomes the real defect in the real job: a reader of
`shared/build/test-results/jvmTest` placed immediately after `Coverage floors`
(`./gradlew :koverVerify`), which is the ordering the rule forbids. Verified two
ways — the gate exits 1 on that file and names `Coverage floors`, and exits 0 on
the clean tree.

Converting the rest of the table is left for a change that can measure how many of
the remaining twins are also divergent. Doing it blind would either turn CI red on
findings nobody has triaged or quietly skip the ones whose edit kind does not map.

## Rationale

- The failure was not a careless mutation. It was two copies and no comparison, so
  "the gate has a positive control" was true of a control that did not exist. The
  repair that matters is removing the second copy, not improving the first.
- A gate script that reads its controls as data is what ADR
  `2026-10-15-gate-registry-carries-controls-as-data` already decided; this is the
  same decision applied to the one table the ADR did not reach.
- The negative control is the deliverable: the mutation is chosen so the gate
  fails on it, and the previous mutation is documented as the one that passed.

## Consequences

- `SCRIPT_GATES` and `SABOTAGE_ONLY_GATES` may now carry `sabotage=None` for a
  gate whose control is declared in the registry. An entry that keeps its own
  copy is untouched — the status quo, not a new rule.
- The registry is load-bearing for every gate it covers, so an edit anchored to
  text that moved now raises (`_replace` refuses a no-op) instead of quietly
  certifying the unchanged file.
- Part F's `why` for this gate is now the registry's sentence, which is longer
  than a one-liner because it carries the history. That is the intended cost: the
  error message quotes the reason, so a vague one cannot hide a vacuous control.

## Links

- `scripts/src/sgate/commands/_registry.py` — `GATE_CONTROLS['build-output-readers']`
- `scripts/check-gate-wiring.py` — `control_from_edit`, `_prefer_registry_controls`
- Runs: <https://github.com/gazon1/sing/actions/runs/38961950540> (the control
  passing a sabotage), <https://github.com/gazon1/sing/actions/runs/38970523791>
- [2026-10-15-gate-registry-carries-controls-as-data.md](2026-10-15-gate-registry-carries-controls-as-data.md)
- [2026-10-16-readers-first-and-nothing-ran.md](2026-10-16-readers-first-and-nothing-ran.md) — the ADR that added the gate this control was supposed to prove