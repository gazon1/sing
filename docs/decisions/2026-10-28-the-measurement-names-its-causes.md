---
title: "The measurement names its causes, and the floor belongs to one environment"
date: 2026-10-28
status: accepted
tags: [testing, maestro, ci, reporting]
---

# The measurement names its causes, and the floor belongs to one environment

## Context

The gate `scripts/ci/check-maestro-results.py` holds a Maestro run to a
number: the floor in `config/docs/maestro-failure-floor.txt` says how many
flows may fail, and a run that fails more fails the gate.

A number says how much debt there is and nothing about what it is made of.
So the debt can change shape entirely while the number stays the same: one
cause fixed, another introduced, and the gate passes. Worse, a cause that
appears *and* a cause that disappears are indistinguishable from each other
in a count, so the only thing the count can tell a reader is how much is
wrong, never what.

The floor had a second, quieter problem: it said nothing about where it was
measured. It was calibrated on an emulator (94 flows, 55 passed, 39 failed,
27 causes, 2026-10-21), and the file recorded neither the device, nor the
API level, nor the build. A run on a phone — or on a different emulator
image — was compared against it as if the two numbers were the same
quantity. They are not, and the gate cannot tell.

The round that found this measured on a physical phone: 94 flows, 39
passed, 55 failed. Thirty-one of those failures were not measurements at
all (see `2026-10-28-a-flow-the-app-was-not-there-for-was-not-measured.md`),
and seven causes were ones the calibrated run had never seen. The gate had
no way to say any of that: the count was over the floor, and that was all
it could say.

## Idea

The debt has names. A measurement that knows the names can be held to the
names, and a floor that knows where it was measured can refuse to judge a
run that was measured somewhere else.

## Decision

**The causes are a registry, and the registry is data.** A new file,
`config/docs/maestro-debt.yaml`, names every cause the floor covers, with
the GitHub issue that owns it, the owner of the cause, and its status. The
gate reads it beside the floor, and it is read the way the floor is read:
never written by a script.

**A cause that the registry does not list fails the gate**, even when the
number of failing flows is within the floor. A new cause is the debt
changing kind, and a count cannot see that.

**A cause the registry marks `fixed` that comes back fails the gate.** That
is a regression, and it is the one case where a floor that only ratchets
downwards would have hidden it.

**A cause the registry lists that did not happen is reported, not failed.**
The debt shrank, and a shrinking debt is somebody's argument to lower the
floor — the gate makes the argument visible instead of leaving it in the
report.

**The floor belongs to an environment, and the run has to name its own.**
The registry records the environment the floor was calibrated in (the kind
of device, its API level, the date, the build). The runner writes the
environment the run happened in to `build/maestro-results/environment.json`.
The gate compares the two and refuses to judge a run it cannot compare: a
missing environment file is a finding, and so is a mismatch. A number
measured on an emulator and a number measured on a phone are two different
quantities, and calling them the same one is how a gate starts lying.

**The cause key keeps the element and the expected text.** The key is the
failure message with the run-specific numbers replaced by `<n>`, so it is
the same in every run — but the id and the text survive, because they are
what identifies the cause. The coarse grouping by message shape is kept as
well: it answers how much of the debt is of one shape, the per-cause table
answers what the debt is.

## Rationale

A gate that counts is a gate that can be satisfied by trading one defect
for another. A gate that names its causes can only be satisfied by removing
a cause, and it says which cause a change has to remove.

The environment check is the same argument one level up. The floor is a
decision somebody made about a suite on a device; comparing another
device's number with it is not a stricter measurement, it is a different
measurement that happens to be expressed in the same unit. The gate that
refuses to compare them is honest, and the cost of being honest is a floor
that has to be re-calibrated when the environment changes — which is a
deliberate edit with a diff that shows it.

The registry is data and not code because the floor already proved the
point: a floor that a script rewrites is a floor that moves by itself, and
the diff that shows the debt shrinking has to be somebody's decision.

## Consequences

- The gate fails on a new cause even when the count is within the floor.
  Every new cause therefore needs an entry, and the entry names the issue
  that owns it.
- A cause that is fixed has to be *marked* fixed, and the marking is what
  turns its return into a regression.
- The floor has to be re-calibrated when the environment changes: a new
  emulator image is a new measurement, and the file has to say so.
- The registry is as long as the debt is. That is the point — the length is
  the count of named things, which is more useful than a single number and
  more honest about what is being carried.
- A run that does not write its environment cannot be judged. The runner
  writes it; a third-party runner that does not will be refused, which is
  the intended behaviour rather than a defect.

## Links

- `config/docs/maestro-debt.yaml` — the registry and the calibration.
- `config/docs/maestro-failure-floor.txt` — the floor.
- `scripts/ci/check-maestro-results.py` — the gate.
- `docs/decisions/2026-10-28-maestro-measurement-gate.md` — the first
  version of the measurement, which counted flows.
- `docs/decisions/2026-10-28-a-flow-the-app-was-not-there-for-was-not-measured.md`
  — the run-side half of the same story.
