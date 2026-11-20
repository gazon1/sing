---
title: "Maestro flow qualification — the positive control proves the admission rule"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, sgate]
issue: 3052
---

## Context

The qualification gate carries a positive control: the sgate registry declares
the one input change that must make the gate fail, and the wiring harness
applies it and expects a refusal.

The control edited the baseline:

```
config/docs/maestro-flow-qualification-baseline.yaml
  Maestro/flows/smoke/01-launch-today.yaml: sha256:...   → deleted
```

and the review round found what that proves. The baseline is pinned: the gate
holds `BASELINE_DIGEST` and refuses to run when the artifact does not match it.
So the sabotage did not reach the admission rule at all — the gate stopped at
the pin and refused before it looked at a single flow. The control demonstrated
that the pin holds. The pin has its own unit tests
(`BaselinePinTest`), so the control was proving a property that was already
covered, and the property the gate actually exists to enforce — that a flow
enters a blocking scope only on the strength of a qualification, an exception,
or the baseline — had no control at all.

## Idea

1. **Keep the baseline sabotage.** The pin is what makes the baseline
   trustworthy; a control that breaks it is worth having.
2. **Sabotage the admission instead.** Change the input that decides whether a
   flow is admitted at all, and let the gate refuse on that ground.
3. **Both.** Keep the pin covered by its unit tests, and spend the control on
   the admission rule.

## Decision

**Option 3.**

The baseline pin keeps its unit tests, and the control now drops the `manual`
tag from `Maestro/flows/capture/01-today.yaml`:

```
Maestro/flows/capture/01-today.yaml:  "  - manual"  → deleted
```

The flow then leaves the manual scope and enters a blocking automated scope
with nothing qualified it and nothing baselined it, and the gate refuses it as
`unqualified` — "new or unadmitted flow in a blocking automated scope". The
control's `why` says which property it proves.

## Rationale

- **A control has to exercise the rule, not the furniture.** The gate's job is
  admission. A control that trips the pin proves the pin works, and the pin is
  a guard in front of the gate, not the gate.
- **The sabotage has to be plausible.** Dropping the `manual` tag is the way a
  flow actually enters a blocking scope by accident — somebody tags a flow, or
  removes a tag, and the nightly suite starts running a flow nobody qualified.
- **One control, one property.** With the pin covered by unit tests, the
  control is free to cover the property that had none.

## Consequences

- The wiring harness now edits a file under `Maestro/` instead of a config
  file. The harness restores the file after the control run, as it does for
  every other control.
- The gate's refusal under the control is about admission, so the message names
  the flow and the reason a reader can act on.
- A unit test (`test_sgate_controls.py`) applies the registered control to the
  real flow and asserts that the gate refuses the result, so the control cannot
  quietly become a no-op again.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Registry: `scripts/src/sgate/commands/_registry.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-baseline-pin.md`,
  `docs/decisions/2026-11-20-maestro-qualification-admission-precedence.md`
