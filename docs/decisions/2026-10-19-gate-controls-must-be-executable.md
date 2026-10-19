---
title: "A control that cannot fail is worse than no control, so every gate's control is executable and checked against the registry"
date: 2026-10-19
status: accepted
tags: ["tooling", "ci", "gates"]
---

## Context

The repository's rule is that every gate has a **positive control**: a way to
make the gate fail on purpose, so that "the gate passed" cannot be confused
with "the gate checked nothing". Two gaps made that rule weaker than it
claimed.

**Gap 1 — a control that provably could not fail.** The log-interpolation gate
(`scripts/check-log-interpolation-allowlist.py`) declared its control as
"remove an entry from the allowlist". Measuring why the gate never fired
showed the control was structurally impossible to fail: the allowlist is keyed
by *name* (`email`, `taskId`, …), and **none** of the 74 interpolations the
gate sees resolves to a type the gate can check. Not one of the 17 allowlist
entries was reachable. Deleting an unreachable entry changes nothing, so the
control always "passed" — a control that reports success while proving
nothing.

**Gap 2 — two lists of controls that nothing compared.** The gate registry
lives in `scripts/src/sgate/commands/_registry.py`; the executable controls
live in `scripts/check-gate-wiring.py` (`SCRIPT_GATES`). Nothing checked that
the two agree. A control could be declared in the registry and never executed
at all — and the wiring checker, which is the thing that runs controls, would
report success.

## Idea

Make both gaps impossible to keep:

1. A control must **plant a violation** that the gate's own detector sees, and
   it must be **restored** afterwards.
2. The wiring checker must **cross-check** each registry control against its
   executable twin: same script, same sabotage path, same command shape. A
   registry entry with no executed twin is a failure, not a warning.

## Decision

**A control plants a real violation, and the registry and the executable
controls are compared by a machine.**

- `scripts/check-log-interpolation-allowlist.py` was rewritten to scan the
  **whole file** (`find_interpolations_in_text` plus a file-level pass) instead
  of a single line, and it understands the `log.e(e) { … }` form and
  multi-line calls. It now sees **74** interpolations where the old
  line-based detector saw 28. Its control was replaced: instead of deleting an
  allowlist entry, it plants a probe interpolation in
  `shared/src/commonMain/kotlin/com/singularity/todo/core/config/RemoteConfigCacheRepositoryImpl.kt`,
  runs the **real** detector over the probe source, and restores the file.
  `--sabotage` now exercises the same code path and exits 1.
- `scripts/check-gate-wiring.py` Part J gained the "twin vs registry" check:
  for every `SCRIPT_GATES` entry that has a registry control, the `sabotage_path`
  and the command are compared (`{python}` is normalized to `sys.executable`,
  `{tmp}` is skipped). Divergence is an error.
- `scripts/src/sgate/commands/_registry.py` — the log-interpolation control was
  re-declared to match, and an executable `SCRIPT_GATES` twin was added.

**The policy of the log gate did not change.** Unknown *names* are still not
found, because switching the allowlist from names to types would change what
the gate means and would paint 38 call sites. What changed is that the gate's
docstring now says honestly what it is: a **floor, not a ceiling**. The
allowlist is unreachable today; that is a fact about the gate's design, and it
is documented rather than papered over.

## Rationale

- **A control that cannot fail is worse than a missing control.** A missing
  control is a visible gap; a broken control is an invisible one that reports
  success. The old log control was the second kind.
- **Planting a violation tests the detector, not the bookkeeping.** A control
  that removes an allowlist entry tests the allowlist loader. The gate is the
  detector, so the control must feed the detector.
- **One source of truth, machine-compared.** Two lists of controls that a
  human is expected to keep in sync is a drift generator with a review step
  nobody reads. Part J now fails on drift; the failure was demonstrated by
  renaming a sabotage path and watching the checker report it.
- **Measuring the gate before fixing the control is what revealed the truth.**
  "0 of 74 interpolations resolve to a type; all 17 allowlist entries are
  unreachable" is not a detail — it is the reason the gate has never fired in
  the repository's life.

## Consequences

- **The log gate can now be proven to fail**, and the proof is a real
  violation in a real file, restored immediately afterwards.
- **Part J fails on registry/control drift.** Verified by deliberately
  diverging a `sabotage_path`: the checker reports it; on an unmodified tree
  it passes.
- **Part J no longer reports the log-interpolation control as missing** — it
  was flagged as "declared but never executed" before this change, which was
  true.
- **The log gate stays a floor.** It will not catch a new interpolation whose
  name is unknown. That is a deliberate, documented policy decision, and
  changing it is a maintainer's call, not a side effect of this change.
- **The gate's coverage went up for free** (28 → 74 interpolations seen) as a
  consequence of fixing the detector to read whole files.

## Links

- `scripts/check-log-interpolation-allowlist.py` — the rewritten detector
- `scripts/check-gate-wiring.py` — Part J, the twin/registry cross-check
- `scripts/src/sgate/commands/_registry.py` — the registry and the control
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the rule this change enforces
