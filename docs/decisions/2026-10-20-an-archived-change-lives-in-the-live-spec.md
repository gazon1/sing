---
title: "An archived change lives in the live spec"
date: 2026-10-20
status: accepted
tags: [openspec, specs]
---

## Context

`openspec/changes/` held 51 changes. Six of them had every task checked and
their requirements already shipped:

- `android-test-hardening` — the instrumented floor, the PR smoke tier, the
  wait budgets, the flow fixtures (REQ-19..REQ-22 of
  `test-execution-integrity`, REQ-3 of `version-gate`).
- `baseline-write-pipeline` — the profile-scoped write pipeline (13
  requirements of a capability that had no live spec at all).
- `offline-local-session` — the local session and the sign-out (REQ-UA-004
  modified, REQ-UA-020..022 added).
- `working-days-are-a-set`, `working-day-switches-reach-the-agenda` — two
  requirements of `tasks`.
- `split-project-detail-viewmodel` — a refactor with no spec-level change.

They sat in `changes/` because archiving is a step nobody was doing, and a
directory of finished work is indistinguishable from a directory of pending
work. The index of live specs (`openspec/specs/MODULE-INDEX.md`) listed six
capabilities; three of the changes' capabilities were not in it at all.

The live `test-execution-integrity` spec also had a defect of its own: two
requirements called **REQ-13**. One is "a claimed scenario that ran without
reporting is a failure" (the per-scenario rule, named by an ADR); the other
is "a declared test class that produced no report fails the build" (the
by-results check, added a day earlier). Two requirements, one id — and the
`req id uniqueness` gate, which would have caught it, is registered but not
run, because it "exits non-zero on a clean tree".

## Idea

- **A.** Move the directories to `openspec/changes/archive/` and leave the
  requirements where they are.
- **B.** Move the directories *and* merge their requirements into the live
  specs, so the live spec is what the code ships.
- **C.** Leave the changes where they are. The archive is a convention, not
  a requirement.

## Decision

**B.** Archiving is a statement that the change is done, and "done" has to
mean the requirements are stated somewhere that outlives the change.

- The six changes move to `openspec/changes/archive/2026-10-20-<slug>/`.
- Their requirements merge into the live specs:
  - `test-execution-integrity` gains REQ-19..REQ-22 and the modified REQ-18.
  - `user-authentication` gains the modified REQ-UA-004 and REQ-UA-020..022.
  - `tasks` gains the two working-day requirements.
  - `write-pipeline` becomes a live spec — thirteen requirements that had no
    home, written with a purpose section that says why the pipeline exists.
- The duplicate REQ-13 is resolved: the per-scenario rule keeps REQ-13 (an
  ADR names it), and the by-results check takes **REQ-23**, the next free
  number, with the two references that meant it updated.
- `MODULE-INDEX.md` gains the three capabilities that were missing from it
  and loses `core/write-pipeline` from the *not covered* list.
- The issue registry is re-imported, so each archived change's record points
  at its archive path.

## Rationale

- **A live spec is the only thing that survives the change.** A requirement
  that exists only inside a finished change's delta is read once, when the
  change is written, and never again. The live spec is what a reader opens
  when they want to know what the code is supposed to do — and the code
  outlives the change.
- **The capability with no spec was the clearest case.** `write-pipeline` had
  thirteen requirements, forty citations across two ADRs, and no spec; the
  index even carried a row saying "author one when a change touches this
  module". The change that touched it is finished, so the moment to write it
  has passed — and writing it now, from shipped behaviour, is the opposite
  of backfill.
- **Two requirements with one id make the spec unusable as a reference.** The
  two REQ-13s are different rules, and the text around them already
  distinguishes them ("the by-results check in REQ-13" versus "the same
  defect class as REQ-13 and REQ-14"). A reader who follows "REQ-13" gets
  two answers.
- **The requirement that keeps the id is the one that is referenced.** An ADR
  names REQ-13 as the per-scenario rule, and the by-results check is a day
  old. Renumbering the newer one is the smaller change; renumbering the
  older one would rewrite a dated decision record.
- **The uniqueness gate is registered and not run because the corpus is red.**
  That is the same shape as the two advisory gates in the same change: a gate
  that cannot pass on a clean tree cannot be a control. Fixing the duplicate
  is the precondition for running it; running it is the next step, and it is
  a separate change.
- **C was not on the table.** A directory where forty-five active and six
  finished changes look identical is a directory nobody can read.

## Consequences

- **`openspec/changes/` holds 45 changes, all of them open.** The index in
  `MODULE-INDEX.md` is now honest about what the specs cover.
- **The live `test-execution-integrity` spec has one requirement per id.**
  The number `REQ-23` is out of the file's order in the sense that it sits
  after REQ-22 — it is the newest rule, and it took the next free number.
- **The `req id uniqueness` gate can be enabled.** It is the last registered
  gate that is not run for want of a green corpus; the corpus is green now
  for the requirement ids, and the gate's own output decides whether it can
  move into the static tier.
- **The archive is a record, not a source of truth.** A requirement that
  changes after the change is archived is changed in the live spec, and the
  archive keeps the delta it was written with.

## Links

- `openspec/specs/test-execution-integrity/spec.md` — REQ-19..REQ-23
- `openspec/specs/write-pipeline/spec.md` — the capability that had no spec
- `openspec/specs/MODULE-INDEX.md` — the index, now including three
  capabilities
- the archived `android-test-hardening` change (2026-10-20) — whose
  requirements are now in the live spec
- `scripts/openspec-import-all.py` — the issue registry, re-imported
