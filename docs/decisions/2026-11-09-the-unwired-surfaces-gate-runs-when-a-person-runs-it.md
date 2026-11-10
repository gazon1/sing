---
title: "The unwired surfaces gate runs when a person runs it"
date: 2026-11-09
status: accepted
tags: ["ci", "gates"]
---

# The unwired surfaces gate runs when a person runs it

## Context

The gate `unwired surfaces` reads every Kotlin file in the tree, strips the
comments and looks for declarations nothing calls. It finds the recurring
defect this repository cares about: code that compiles, has tests, and is
never invoked.

It cost 140 s of runner time on every push (26 s of it in the local loop),
and it was the most expensive part of the static gate job that a pull
request touching no Kotlin file had to pay for.

Two attempts to make it cheaper failed. Memoizing the classifier that says
which Kotlin declaration a name belongs to changed the runtime by nothing
(2997 cache hits, 4611 misses, 38.4 s before and after). Dispatching the
same classifier by the first character of the name instead of five
`startswith` calls was slower (39.8 s against 38.4 s): the profile had
overstated the cost of `startswith`, and the scan is bound by the parsing of
2 353 files and 14.5 million characters, not by the classification.

So the gate's price was not a coding mistake that a better implementation
would remove. It was the price of reading the whole tree.

Meanwhile the signal it produced was not read. The findings of a run
arrived in the CI log of a job nobody opens, next to sixty-eight other
verdicts, and the floor was already at zero — the gate had nothing left to
say about the tree as it stands.

## Idea

A gate that costs a hundred and forty seconds of every push to repeat a
verdict nobody reads is not a gate. A tool that a person runs when the
wiring changes is a tool.

## Decision

The gate leaves the automatic registry. It stays a gate in every other
respect:

- it keeps its floor (`config/docs/unwired-surfaces-floor.txt`) and its
  one-directional comparison;
- it keeps its command (`python3 scripts/find-unwired-surfaces.py`), its
  report and its exit code;
- it stays in the Python registry, so `sgate unwired_surfaces` still runs it;
- it stays in the control phase, so the "this gate can fail" proof is still
  made whenever the change touches what the control edits.

What changed is its placement: from `PlacementKind.STATIC` to
`PlacementKind.ELSEWHERE`, with the reason and the pointer to the script
that replaces it. `scripts/ci/static-gates.sh` and
`docs/scripts/gate-registry.md` are regenerated from that.

The gate that reads the whole tree and the gate that says "the wiring
changed" are now the same event, which is when the reading pays.

## Rationale

**The price was structural.** The scan reads every Kotlin file because the
defect it looks for has no local form: an unwired surface is a declaration
that is fine and a call site that is missing somewhere else. Nothing in the
scan can be narrowed to the diff, because the missing call site is exactly
what the diff does not contain.

**A ratchet nobody reads is a cost, not a control.** The floor was already
zero and the findings were zero. The gate could not stop anything, because
the person who could act on a finding was never in the loop that produced
it. Moving it to the moment the wiring changes puts the finding in front of
the person who can act on it.

**Removing it from the registry is not removing it.** The repository already
has the mechanism for a check that is not in the shell registry:
`PlacementKind.ELSEWHERE` with a `reason` and a `tracked_by` pointer. The
gate-registries check requires exactly that of any gate without a shell
row, so the manual gate stays visible in the registry as a manual gate, not
as a gap.

**The control stays.** The proof "this gate can fail" costs a few seconds
and is paid only when the change touches the control's target. Dropping it
would trade a cheap proof for a cheap-looking hole.

## Consequences

- Every push saves the 140 s of runner time the gate cost. This is runner
  minutes, not developer waiting: the static job was not on the critical
  path of the pull request.
- The floor is no longer checked on every push. A surface that becomes
  unwired is found by the person who wired it, when they run the gate.
- The gate-costs page still lists the gate and its 26.1 s local price: that
  is the cost of the manual run, and the number stays true.
- `sgate unwired_surfaces` and `python3 scripts/find-unwired-surfaces.py`
  behave exactly as before.

## Links

- `scripts/src/sgate/gate_manifest.py` — the placement
- `scripts/find-unwired-surfaces.py` — the tool a person runs
- `docs/architecture/ci.md` — the CI surfaces
- `docs/decisions/2026-10-01-ci-quality-ratchet.md` — the ratchet this gate
  came from
