---
title: "A gate that reports a live thing as dead is worse than no gate"
date: 2026-10-06
status: accepted
tags: [tooling, testing, process, ci]
---

# A gate that reports a live thing as dead is worse than no gate

## Context

Four defects were found while restructuring the CI workflows, and three of them
share one shape: **a check that was failing on a true positive, or that was
failing on a false one, in a way nothing distinguished from working.**

| Defect | Symptom | What it actually meant |
|---|---|---|
| `check-doc-dead-refs.py` could not see enum constants | 8 live symbols reported as dangling | The genui-catalog skill quoted `STRING`, `INT`, `BOOL`, `TONE` as members of `A2uiType`. All four exist. Four more that do *not* exist were reported in the same breath |
| The same skill listed `NUMBER`, `NUMBER_ARRAY`, `STRING_ARRAY`, `CHILD_SLOT` | Indistinguishable from the 8 false positives | An agent following the skill would emit property types the validator rejects, and its worked example called a non-existent type. Four real members (`NODE_REF`, `NODE_REFS`, `DIRECTION`, `PATH`, `JSON`) went unmentioned |
| `room-schema-integrity`'s control replaced `SCHEMA_VERSION = 37` by literal | "gate cannot detect this" | The schema had moved to 38. `replace` matched nothing, the gate received an untouched file, correctly passed — and the control reported itself as broken |
| `provenance-registry.tsv` listed `PurchaseState.kt` | "registry lists a file which does not exist" | The file was renamed to `Entitlement.kt` in `0a7f8222`. A GPL-provenance claim pointed at nothing, and the replacement was unregistered |

## Idea

- **A.** Baseline the findings. `skill-symbol-baseline.txt` already exists for
  exactly this and `--update-baseline` writes it in one command.
- **B.** Fix the checkers, then fix the documents they were right about.
- **C.** Wait for a sweep.

## Decision

**B**, and in this order: fix the checker first, re-run it, then fix what it
legitimately found.

`_kt_enum_entries()` in `check-doc-dead-refs.py` now indexes the bodies of
`enum class` declarations. The body is read from the opening brace to the first
line closing it at the enum's own indentation, rather than by scanning for
anything identifier-shaped — a permissive scan would add thousands of names, and
a check that accepts more than it can prove is the failure this gate exists to
stop. The result: the twelve reported symbols fell to four, and all four are real.

The four were fixed in the skill, not baselined. The enumeration now matches
`A2uiCatalog.kt` and names that file as the source of truth, so the next reader
has somewhere to check rather than a list to trust. The worked example compiles
against the real enum.

The `room-schema-integrity` control matches by regex and asserts the match count,
the pattern `test-runs` and `traceability-ratchet` already used. A control that
quietly stops sabotaging is reported as a *passing* control, which is worse than
having none — it is evidence, and it is false.

The provenance registry and `docs/legal/PROVENANCE.md` now name `Entitlement.kt`
and record that the file absorbed `PurchaseState.kt` in `0a7f8222`.

**A was rejected for the same reason it is rejected everywhere else in this
repository:** baselining a finding because it is loud is how a baseline becomes a
sink. These four were not loud because they were noise; two of them were wrong
and two were documentation defects that a following agent would have inherited.

## Rationale

The failure that mattered was not the false positives. It was that a *true*
finding was sitting in the middle of a report a reader had already learned to
dismiss. Once `CHILD_SLOT` was grouped with `STRING` — one invented, one real —
the whole report was un-actionable, and the invented name survived indefinitely.

This is the same lesson as the meta-gate's Parts A and B, one level down: a gate
that cannot distinguish the two cases it is meant to tell apart is not evidence,
whatever its exit code says.

The cost of the enum fix is 30 lines in a checker. The cost of not fixing it is
that every future skill which documents an enum gets reported, ignored, and
eventually baselined by whoever stops caring.

## Consequences

- `check-doc-dead-refs.py --skill-symbols` is green and is now trustworthy for
  enum-containing code.
- A future edit to `A2uiType` that forgets the skill's list is now reported by
  name, which is the drift signal the skill's own note promises.
- `check-provenance.py` is green again, so the licence claim points at a file.
- The gate suite went from five red gates on `main` to two. Both remaining ones
  are true positives about source code, and both need an owner's decision — see
  `docs/decisions/deferred-backlog.md`.

## Links

- `2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the restructure these
  findings surfaced in
- `scripts/check-gate-wiring.py` — Parts A and B, the same lesson applied to gates
- `scripts/check-doc-dead-refs.py` — `_kt_enum_entries`