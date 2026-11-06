---
title: "The tech-debt refactor skill is retired"
date: 2026-11-06
status: accepted
tags: ["tooling", "docs", "skills"]
---

# The tech-debt refactor skill is retired

## Context

`singularity-todo-tech-debt-refactor` was the plan for the tech-debt epic:
three epics of ten pull requests each, with the per-PR pointers and the
commits that had already landed.

The epic it planned is behind us — Epic 1 is marked COMPLETED in the skill
itself, and the plan lists the exact commits that did the work. What it
described was therefore no longer a procedure but a record: a skill that says
"when starting or resuming work on the tech-debt epic" points at work that is
over, and it pointed at files and skills that have since moved or gone.

A skill is a procedure. A plan that has been executed is not one, and a skill
that describes finished work is read by an agent as a menu of things to do.

## Idea

Retire the skill. The plan it holds is still in the repository — in git
history, and in the ADR that reconciled the epic's claims against the code.

## Decision

The skill is removed from `.agents/skills/`. `docs/SKILLS-CATALOG.md` is
regenerated (it is a generated file and is not edited by hand), and the
entry the skill held in `config/docs/dead-refs-baseline.txt` is removed with
it — the baseline lists dead references of files that exist.

The plan itself is not lost: ADR `2026-10-01-tech-debt-reconciled` records what
the epic claimed and what the code actually said, and the commits named in the
plan are in the history.

## Rationale

**The catalog is how an agent chooses.** A skill in the catalog is a claim
that the procedure is worth loading. A plan that is finished is not.

**The record has better homes.** Git history keeps the per-PR plan, and the
ADR keeps the reconciliation. The skill was the third copy.

## Consequences

- The retired-skills table in `docs/doc-maintenance.md` gains this skill.
- The regenerated catalog also carries the line counts that were stale in it
  before this change: the file is generated, and it was committed out of date.
- `singularity-todo-worktree-isolation`, which the skill pointed at, is
  untouched — it is a procedure of its own.

## Links

- `docs/decisions/2026-10-01-tech-debt-reconciled.md` — what the epic claimed
  and what the code said
- `docs/doc-maintenance.md` — the retired-skills table
- `scripts/regen-skills-catalog.sh` — the generator of `docs/SKILLS-CATALOG.md`
