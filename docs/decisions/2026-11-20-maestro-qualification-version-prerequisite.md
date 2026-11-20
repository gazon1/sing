---
title: "Maestro flow qualification — the engine version is a prerequisite, not a note"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, runner, evidence]
issue: 3052
---

## Context

A qualification record has to name the Maestro version that ran the attempt:
the version is part of the environment identity, and the admission gate rejects
a record without it.

The runner detected the version before the attempts and printed a note when the
detection failed:

```
note: the maestro version could not be detected — a qualification record has to
carry it, so nothing will be recorded as qualified
```

and then ran the attempts anyway. Three device runs per flow, for a record that
could not be admitted. The per-attempt probe behaved the same way: an attempt
whose own version probe failed still ran, and was recorded with no version.

The review round found this as a minor finding: the gate still rejects the
evidence, but the runner spent device time producing it.

## Idea

1. **Keep the note, keep the attempts.** The user learns the version was not
   detected and sees the attempts anyway; the record is refused at admission.
2. **Refuse the run before the first attempt.** The version is a prerequisite of
   the evidence, so the run cannot produce admissible evidence without it.
3. **Refuse the run, and record the refusal in the report.** The run says why it
   stopped instead of exiting on a note nobody reads.

## Decision

**Options 2 and 3.**

- `main()` probes the engine version before the flow loop. When the probe fails,
  it says so as an error and exits with code 2 — before any attempt, before any
  snapshot, before any device time.
- `run_one_attempt()` probes the version before it runs the flow. When the probe
  fails, the attempt is not run: it is reported as an `infra_failure` with the
  reason, and the qualification it belongs to fails with it.

The version is read once per run for the refusal and once per attempt for the
record; a probe that succeeds in one place and fails in the other can no longer
produce a record the gate rejects.

## Rationale

- **Evidence that cannot be admitted is not worth producing.** The gate already
  refuses a record without a version; refusing earlier only saves the device
  time it would take to produce it.
- **A warning that does not change the outcome is noise.** The note said the
  run would produce nothing admissible and the run went on to produce exactly
  that.
- **The refusal is loud and specific.** An error and a non-zero exit code tell
  the person who ran the command what happened, the way the other refusals in
  the runner (an untracked dependency, a baseline that does not match its pin)
  do.

## Consequences

- A machine without the Maestro CLI, or with a build whose `--version` output
  names no version, cannot qualify anything. That is the intended reading: the
  environment the evidence names is the environment the attempts ran in.
- The runner's exit codes now distinguish the two refusals: code 2 for a
  prerequisite that is missing (no version), code 1 for a flow that was refused
  after the run started.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Runner: `scripts/maestro-qualify-flows.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-device-identity.md`
