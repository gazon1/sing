---
title: "A Green Gate Only Proves The Gates You Ran"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `.just/tests/gate.just:124-151` names all four steps inline under `set -euo pipefail` and prints a banner when `SKIP_MAESTRO=1` means the flows were not gated. `justfile:61` exposes it as `just gate`. The smoke-set half is #87.

**Found in:** 2026-10-04, the same sweep. Three separate gates were red, in
three different ways, and each had been red for a different reason that made it
invisible:

- `check-doc-sizes` — over budget by 3 and 5 lines, from growth that no step
  checks at the moment it happens.
- `check-doc-dead-refs` — 12 dead references, from skills that point at
  documents nobody wrote.
- The Maestro `smoke` set — contains a flow that can never have passed (wrong
  but valid id), and was run by no local command and by a CI job that has never
  executed.

None of these is a missing check. Every one of the checks exists, is wired, and
would have failed. The common failure is **coverage of the gates themselves**:
each one only ever ran on push, or only for one tag, or only in one directory.

**The generalisable part:** a green result from a gate is a claim about the set
of gates that ran, and nothing in a normal workflow makes that set explicit. The
fix is always the same shape — name the set, in one place, and run all of it.
`just gate` is that place for this repo.

**Cost note, because this is a trap worth seeing:** the instinct on finding a
red gate is to fix *only* the red one and move on, which is what happened for
`check-doc-sizes` — the digest generator and `AGENTS.md` were trimmed, and the
dead-refs gate next to it was left red on the grounds that it was "not this
task". Fixing one gate while leaving its neighbour red is how a repo reaches a
state where a single `CI is green` claim is worth nothing.

---
