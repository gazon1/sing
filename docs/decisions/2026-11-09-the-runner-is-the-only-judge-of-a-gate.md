---
title: "The runner is the only judge of a gate"
date: 2026-11-09
status: accepted
tags: ["ci", "gates"]
---

# The runner is the only judge of a gate

## Context

`sgate run-all` dispatches every gate and records the exit code. That is not
enough to tell a green gate from a dead one, and the tree carried two probes
to make up the difference:

- `scripts/check-gate-liveness.py` re-ran the whole gate corpus and asked
  "did this gate exit non-zero?". It cost 70 s in CI and 224 s locally.
- `scripts/check-gate-json-contract.py` re-ran the gates the shell registry
  dispatches through `sgate`, asked for `--json`, and required stdout to be
  exactly one JSON object carrying `passed`. It cost 36 s in CI and 126 s
  locally.

Both probes are the same shape as the thing they judge: they dispatch the
gates. So the corpus ran three times per CI run — 523 s in the runner, 224 s
in the liveness probe, 126 s in the JSON probe — and the two probes were
themselves gates inside the corpus they probed.

The probes also answered slightly different questions, and the difference
was the defect. `check-gate-liveness.py` decided "died instead of reported"
by looking for traceback markers in the captured output. A gate that printed
a traceback and exited 0 was green to the runner and red to the probe; a gate
that died with a non-zero exit and no traceback text was red to both, but for
different reasons.

## Idea

- Let the runner read the verdict of the gate it dispatched, and let a
  traceback in that gate's output fail the gate.
- Delete both probes.

## Decision

`sgate run-all` is the only judge of a gate. When it dispatches a gate whose
command starts with `sgate`, it passes `--json` and reads the verdict out of
the payload; a gate that does not produce a JSON verdict fails, and a gate
whose stderr carries a traceback marker fails even when it exits 0. Gates
dispatched as plain scripts keep the old rule: the exit code decides.

The two probes are gone: `scripts/check-gate-liveness.py`,
`scripts/check-gate-json-contract.py`, their unit tests, their entries in the
gate registry, the manifest, the controls, the exemptions, the second
registry in `scripts/src/sgate/gate_controls.py`, the CI step that ran the
liveness probe, the `hk` step, and the recorded benchmarks.

## Rationale

**The runner already had the evidence.** The probes re-ran the corpus to
collect what `run-all` had in its hands — the exit code, stdout and stderr of
the very same dispatch. Re-running to obtain it twice is how a corpus that
takes 9 minutes to check takes 27.

**The question "did it report or did it die?" has one owner.** Two judges of
the same gate disagree by construction: the runner saw exit 0, the probe saw
a traceback. One judge, one rule: the runner reads the verdict, and a
traceback is a failure.

**The probes had a positive control that was itself the corpus.** The
liveness probe's own proof that it worked was to re-run every gate, which is
the cost the probe was added to remove. The replacement has a positive
control that costs nothing: `scripts/tests/test_sgate_gates.py` drives the
runner against a gate that reports, a gate that dies, a gate that prints a
traceback, and a gate that exits 0 with a traceback.

**A gate that cannot be run is a fact about the gate, not a separate
question.** `run-all` already fails a gate that cannot be dispatched — the
command is not found, the module does not import. That check was never
missing; it was duplicated with a second, slower spelling.

## Consequences

- The corpus runs once per CI run instead of three times. In the measured
  run, 523 s of runner plus 350 s of probes becomes 523 s.
- A gate dispatched through `sgate` must answer with a JSON verdict. This is
  a contract change for gate authors, and it is enforced where the gate runs.
- The `Gate tooling` job loses the liveness probe's 70 s, and the `Static
  gates` job loses the JSON probe's 36 s.
- `sgate run-all --with-controls` and the shell registry keep the same
  external behaviour: a gate that lies about its verdict still fails the run.
- The historical records that name the removed scripts — the ADRs of
  2026-10-16 and 2026-10-21, the spec-kit specs 012 and 013, the tooling
  audit — are left as they were written and are catalogued in the dead-refs
  baseline.

## Links

- `scripts/src/sgate/commands/run_all.py` — the runner, `TRACEBACK_MARKERS`
  and `_judge`
- `scripts/tests/test_sgate_gates.py` —
  `TestTheRunnerAsksTheGateForItsContract`
- `docs/decisions/2026-10-16-nightly-ci-gate-that-could-not-see.md` — the
  night the probe's own verdict was wrong
- `docs/decisions/2026-10-21-one-owner-of-stdout.md` — one owner for stdout
- `docs/scripts/gate-registry.md` — the registry the runner reads
