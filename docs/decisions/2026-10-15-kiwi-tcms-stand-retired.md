---
date: 2026-10-15
status: accepted
tags: [kiwi, tcm, testing, infra, tech-debt]
---

# ADR: Kiwi TCMS Stand — Retired Before Adoption

## Context

`infra/kiwi/` was designed as a local Kiwi TCMS stand for the scenario traceability
layer: each test class → Kiwi TestCase, each Gradle run → Kiwi TestRun, cases with no
execution → visible holes. The work was done in 2026-10, documented in three ADRs, and
a skill (`singularity-todo-kiwi-tcm-stand`) was written to operationalise it.

**`infra/kiwi/` was never committed to this repository.** It exists only as a
per-developer local deployment. The ADRs, the skill, and the arch-test fixture
references all point to a directory that does not exist in any worktree of this repo.

This creates a permanent inconsistency: the documentation refers to infrastructure
that no checkout can run, and the skill trains agents on a stand nobody can reproduce
without a private setup guide.

## Decision

**The `singularity-todo-kiwi-tcm-stand` skill is deleted.**

`infra/kiwi/` itself does not exist in this repo — there is nothing to delete. The
four kiwi-related ADRs (`2026-10-05-kiwi-*`) are retained as historical records of
the design work. They are not deleted because:
- they document decisions that shaped the scenario traceability layer
- deleting them would break `check-adr-references.py` (there are live links to them)
- they serve as context for the arch-test fixtures that still reference kiwi paths

The arch-test fixture references to `infra/kiwi/sync.py` etc. remain baselined as
accepted dead references. They document a real infrastructure choice that was made
but never committed.

## Consequences

- `singularity-todo-kiwi-tcm-stand` is gone from the skills catalog
- no agent will be trained on a stand nobody can run
- the kiwi ADRs remain readable but carry a note that the infrastructure was local-only
- arch-test fixtures referencing kiwi paths remain in the baseline (not a new problem)

## Links

- Supersedes: `2026-10-05-kiwi-tcms-stand-and-non-test-files.md`
- Related: `2026-10-05-kiwi-and-traceability-structural-debt.md`
- Related: `2026-10-05-kiwi-gates-and-rotation.md`
- Related: `2026-10-05-scenario-test-cases-in-kiwi.md`
- `docs/doc-maintenance.md` — list of retired skills
