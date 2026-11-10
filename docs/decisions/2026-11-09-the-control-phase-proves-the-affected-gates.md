---
title: "The control phase proves the affected gates"
date: 2026-11-09
status: accepted
tags: ["ci", "gates"]
---

# The control phase proves the affected gates

## Context

The control phase proves that each gate can fail: it sabotages the input a
gate reads, runs the gate, and requires the gate to go red. It is the answer
to "a gate whose pattern matches nothing is green forever" — the link-grammar
gate shipped that way once, with the whole suite reporting every gate as
passed.

The phase cost 380 s in CI and ran over all 70 gates. It was already gated on
the change itself: the `changes` job computes `needs.changes.outputs.gates`,
and the phase runs only when the gate corpus (`scripts/**`, `config/**`,
`.github/**`) changed. So the 380 s were paid by exactly the pull requests
that touched the gates — the ones where proving the gates matters most.

But "the corpus changed" is not "these gates changed". A pull request that
edits one gate script paid for the proof of sixty-nine other gates, whose
proofs could not have changed.

## Idea

Scope the phase to the gates the change can have broken.

## Decision

The phase computes the scope of the change and proves the gates in it. A gate
is in scope when the change touches:

- the gate's own script — the gates are separate processes over separate
  files, so a change to one gate's script cannot break another's control;
- the file the gate's control edits — the control edits text, and text that
  moved is a control that no longer proves anything;
- any other file under `scripts/` — a gate script may import it, and the
  scope does not resolve the import graph, so this is treated as "every gate
  is in scope".

A change under `scripts/tests/` is in scope for nothing: those are the tests
of the gate scripts, not the gate scripts. A change anywhere else is in scope
only for the gate whose control target it is.

When the scope cannot be read — no base ref, no merge-base, or a change with
no files in it — the phase proves every gate. A scope nobody can read is not a
scope.

The scope is read once, before the first sabotage: the sabotage dirties the
tree, and a scope computed after it would blame the gate that was just
sabotaged.

## Rationale

**A control proves one gate.** The verdict "this gate can fail" is a
property of that gate's script and that gate's control. Nothing about it
depends on whether a different gate's script changed this week, so re-proving
it costs 380 s and proves the same thing.

**The corpus condition was already the right idea, applied one level too
high.** "The corpus changed" was a proxy for "some gate may have broken".
The proxy was cheap to compute and safe; it was also blunt. The scope
replaces the proxy with the question itself.

**The import graph is not resolved, and the rule says so.** A gate script may
import a sibling module, and a change to that module can change what the gate
does. Rather than resolve the import graph — a graph that has to be right, or
the scope is wrong — any change to a non-gate script under `scripts/` puts
every gate in scope. The rule is coarse in the direction that costs time, not
in the direction that loses a proof.

**An empty change proves everything.** On `main`, and on any run where the
merge-base cannot be found, the diff is empty. An empty diff means "we cannot
tell what changed", not "nothing changed", and a phase that proves nothing
because it could not decide what to prove is the defect this phase exists to
catch.

**The skip is stated, not silent.** Every skipped gate is printed with the
reason it was skipped. The repository's rule is that an unreported skip is a
defect — the same rule that made the liveness probe's exemptions visible.

## Consequences

- A pull request that edits one gate script pays for that gate's control, not
  for all seventy.
- A pull request that edits only the gate scripts' own tests pays for
  nothing: the gates themselves did not change.
- A pull request that edits a shared module under `scripts/` still pays for
  everything. That is the price of not resolving the import graph.
- The gate that runs on the real tree is unchanged: a gate that is red on a
  clean tree still fails the run, whether or not its control ran. The
  control's clean-run check was a duplicate of that signal, not its only
  source.
- The scope is read from git inside the phase, so the local loop and CI reach
  the same verdict from the same tree.

## Links

- `scripts/src/sgate/control_scope.py` — the scope rules
- `scripts/check-gate-wiring.py` — `_control_scope`, and the skip in
  `check_can_fail`
- `scripts/tests/test_control_scope.py` — the boundary between "in scope" and
  "not in scope"
- `.github/workflows/ci.yml` — the static job's control phase
- `docs/decisions/2026-10-15-gate-registry-carries-controls-as-data.md` — the
  registry the controls are declared in
