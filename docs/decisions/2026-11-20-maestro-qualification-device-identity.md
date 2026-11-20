---
title: "Maestro flow qualification — the device is part of the identity of a run"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, gate, policy]
issue: 3052
---

## Context

A qualification is three passing attempts "in the same documented
environment". The environment identity the gate compared was `kind`, `api` and
`device` — the *model* of the device, not the device.

Three attempts on three different devices of the same model therefore counted
as one environment. The evidence said the flow passed three times; in fact it
passed three times on three machines, and the flakiness a qualification exists
to catch is often exactly the kind that one machine shows and another does
not. The record also did not name the machine: the environment it carried
stopped at the model, so a reader could not tell which device the run was on.

## Idea

1. **Keep the model as the identity.** Simpler to type, and the model is what
   the CI matrix names.
2. **Name the device.** The environment identity becomes `kind`, `api`,
   `device` and `serial` — the machine, not only its kind.

## Decision

**Option 2.**

- The environment identity includes the **serial** of the device, in the gate
  (`_env_key`) and in the runner (`record_issues`).
- The serial is required, not merely compared: an attempt that does not name
  the device it ran on does not record a documented environment, and the gate
  refuses the record.
- The attempts of one qualification have to agree on it. Attempts that
  disagree are not one environment, so they do not form a qualification: the
  gate refuses the record and the runner refuses to write it.
- The qualification record carries the serial of the device the attempts ran
  on, next to the model, the API level and the build identity.

## Rationale

- **The machine is the environment.** `kind`, `api` and `device` describe a
  class of machines; the serial names one. Only the second one can tell two
  runs of the same flow apart.
- **Flakiness is often device-specific.** A qualification that accepts runs
  from three different machines has not established that the flow is
  repeatable; it has established that it passed three times somewhere.
- **The record should name what ran.** An environment recorded down to the
  model cannot be told apart from another run on the same model, which makes
  the evidence weaker than the run it describes.

## Consequences

- A qualification run that spans two devices no longer qualifies. The runner
  pins a device with `--serial`, and the environment is read from the
  runner's own `environment.json`, which always carries the serial.
- A record whose attempts do not name the device is refused, in the gate and
  in the runner. A qualification run that cannot say which machine it ran on
  does not establish that the flow is repeatable there.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Prior decisions: `docs/decisions/2026-11-20-maestro-qualification-anchors.md`,
  `docs/decisions/2026-11-20-maestro-qualification-tree-boundary.md`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py`
