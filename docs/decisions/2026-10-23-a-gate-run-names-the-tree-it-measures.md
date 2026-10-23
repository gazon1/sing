---
title: "A gate run has to name the tree it measured"
date: 2026-10-23
status: accepted
tags: [gates, tooling, measurement]
---

# A gate run has to name the tree it measured

## Context

A local gate run in one worktree reported "41 passed, 0 failed" — and said
nothing about the tree in front of it.

`sgate` is installed *editable*: the venv on `PATH` decides which checkout's
`scripts/src/sgate` the gate CLI is. And `sgate/repo.py` derives the
repository root from its own file location, so a foreign CLI reads a foreign
`scripts/ci/static-gates.sh`, runs a foreign registry, and reports about a
foreign tree. The run is green, quiet, and reads like an honest result.

The two registries differed exactly where it hurt: the checkout that was
measured had 45 gate specs against this checkout's 58, and the three that were
missing — the tag catalogue, the issue registry, the selectable-text coverage
— were exactly the three that failed in CI afterwards.

`scripts/ci/preflight-gate-tools.sh` already knew how to tell the two apart:
it resolves the importable `sgate` and compares it with this checkout's
`scripts/src`. It printed `FOREIGN` and exited non-zero. What was missing was
that **nothing called it** on the path a gate run takes: `check.sh` called it,
`scripts/ci/static-gates.sh` did not, and a bare `sgate run-all` cannot be
caught from inside this checkout at all — a foreign CLI never reads this
checkout's registry.

So a green local run measured a tree nobody was working in, and it looked like
a measurement.

## Idea

- **Call the preflight everywhere.** Cheap, but the preflight answers a
  broader question ("are the tools installed?"), and a missing `lychee` would
  then stop the whole suite instead of the one gate that needs it.
- **Install the CLI non-editable.** Then the origin is fixed per venv — but
  the venv is still another checkout's, and the failure becomes "the CLI is
  stale" instead of "the CLI serves another tree". It also breaks the
  development loop the editable install exists for.
- **Ask the gate run to name its tree.** The question a local gate run has to
  answer before it is read as a verdict is *which checkout did you measure?* —
  and the answer is available without knowing anything about the other
  checkout's code.

## Decision

A dedicated check, `scripts/check-gate-tooling-origin.py`, resolves the
importable `sgate` and compares it with this checkout's `scripts/src/sgate`.
It runs **first** in `scripts/ci/static-gates.sh`, before a single gate, and
refuses the run when the two differ.

It is a refusal, not a warning: a run that cannot say which tree it measured
has no verdict to give.

Two properties of the check are deliberate:

- **It does not import `sgate`.** Every other gate script ends with
  `from sgate.commands.base import run`; this one cannot, because the state it
  exists to report is the one where that import is foreign or missing. It uses
  Click, like the others, and nothing else from the project.
- **It is not a gate in the registry.** A gate is dispatched by the registry,
  and a foreign CLI runs a foreign registry — a gate in *this* checkout's
  registry would never be reached in the state the check exists for. The check
  lives in the entry points of this checkout instead.

`check.sh` keeps calling the preflight: it answers a wider question, and it is
the right question at the start of the whole local loop.

## Rationale

The defect is not in the checks — every check did its job. It is in the
*reading* of the result: a run was read as a verdict about this tree while it
was a verdict about another one. A check that cannot fail is a rule nobody
reads; here, the rule ("the run measures this tree") existed only in prose,
and prose is not consulted at the moment the run happens.

The fix is placed where the reading happens: at the start of the run, before
any result exists to be misread.

## Consequences

- A local gate run in a worktree whose `PATH` points at another checkout's
  venv fails immediately, with the message that names the fix (a venv per
  checkout: `SINGULARITY_TOOLS_VENV=... bash scripts/ci/setup-local-tools.sh`).
- The check costs one Python import.
- `bash scripts/ci/static-gates.sh` is no longer a way to run the gates
  without the tooling: that is the point.
- A bare `sgate run-all` with a foreign CLI still measures the foreign tree.
  Nothing inside this checkout can prevent that — the CLI resolves its own
  root before it reads anything of ours. The remedy is the message the
  preflight and this check print, and the convention that every checkout has
  its own venv.

## Links

- `scripts/ci/preflight-gate-tools.sh` — the broader question (are the tools
  installed), and the FOREIGN report this check narrows to a refusal.
- `scripts/check-gate-tooling-origin.py` — the check.
- `scripts/ci/static-gates.sh` — where it runs.
- Issue #902 — the class of defect: a check that cannot fail is a rule nobody
  reads.
