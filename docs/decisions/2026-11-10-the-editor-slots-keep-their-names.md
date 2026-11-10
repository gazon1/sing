---
title: The editor's supplied-content slots keep their names; the state slot moves below the attribute rows
date: 2026-11-10
tags: [architecture, compose, ui, tasks]
deciders: [Singularity Developer]
status: accepted
---

# The editor's supplied-content slots keep their names; the state slot moves below the attribute rows

## Context

`TaskEditorContent` takes two caller-supplied content slots. `bannerSections` carries
what *asks* the person for something — the first-run banner, the pending AI proposals.
`extraSections` carries the task's own *state* — the checklist, the tags, the subtasks,
the logbook, the time tracking.

`extraSections` was drawn before the attribute rows. The consequence was measured: on a
360 dp phone a new task's first screen was three empty "add" cards, and the due date was
reachable only by scrolling. The rows a person fills in were pushed below the fold by
the content that describes the task they are filling in.

The two kinds also shared one slot before `bannerSections` existed, so an offer waiting
for an answer rode wherever the child work ended.

Issue #1942 asked for the two to be separable in place. PR #2399 answered it by
renaming the slots to `askSections` and `stateSections` — the names say what each slot
carries — and by moving the lower slot after the attribute rows. The implementation
plan in the issue's own comment (2026-11-10, written against the current default
branch) asked for something smaller: keep `bannerSections` and `extraSections`, and move
the lower slot's invocation.

## Idea

Two ways to answer a placement defect:

- **Rename.** `askSections` / `stateSections` say what each slot carries, and the names
  are the difference between one slot and two.
- **Move.** Keep the names, move one invocation, and let the KDoc say where each slot
  is drawn.

## Decision

Keep the names. `bannerSections` is drawn under the description, above the attribute
rows; `extraSections` is drawn after the last attribute row. One invocation moves; the
callbacks, the call sites, the data and the dependencies are untouched.

## Rationale

The defect was a placement, not a name. The name `extraSections` already says "content
the editor does not know about", and the KDoc now says where the editor draws it.

A rename moves a *decision* into a *name*: the slot's place on the screen becomes part
of its identity. That is a promise the name cannot keep — the next placement change
makes the name wrong, and the rename has to happen again, this time across every call
site and every document that mentions the slot.

It was also not free. While the rename was in flight, the live skill documentation kept
naming `extraSections`, so an agent following it wrote code that no longer compiled —
the review of PR #2399 found exactly that, and it is the cost of renaming a public slot
in a repository whose documentation is written to be followed.

The editor's contract with its caller stays the same as it was: *where my content goes
is the editor's business*. The name of the slot does not have to repeat it.

## Consequences

- `TaskEditorCallbacks` keeps two nullable slots, `bannerSections` and `extraSections`,
  and no caller had to change.
- The lower slot's content is now past the fold on a phone-sized screen. That is the
  point of the change, and the time-tracking guard was updated to scroll to the section
  rather than to weaken its assertion.
- The slot names are now a little less self-describing than `askSections` /
  `stateSections` would have been. The KDoc of both slots, and the skill documentation
  that names them, carry the difference instead.

## Amendment: an empty slot must draw nothing (2026-11-10)

The slot moved, but the caller still emitted its `Column` unconditionally. A task
with no first-run offer and no pending proposals therefore drew an empty column
between the description and the attribute rows, and the editor's
`Arrangement.spacedBy(TaskSpacing.md)` counted it as a child — so the gap
between the description and the first row was `md` wider than it should have
been, on every task that had nothing to offer.

The rule is now: **the offer slot draws nothing when it has nothing to draw.**
The caller computes the first-run state and the proposals outside the column and
emits it only when there is a banner or a proposal in it.

Three tests pin this down, and none of them is a screenshot:

- a task with nothing to offer leaves the gap between the description and the
  first row below the slot at one spacing step — the acceptance criterion of
  issue #2033;
- a pending proposal is drawn *above* the attribute rows — the placement this
  ADR is about, asserted with a proposal actually seeded rather than with the
  first-run banner;
- the offers fit the narrowest phone screen, measured at 360 dp wide, which is
  the width of a folded phone.

The narrow-screen check runs in the desktop Compose harness at 360 dp. It is
not a device check: the Maestro lane is CI-only and runs on a `pixel_6`, so a
device-level variant of this assertion is still open.

## Links

- Issue #1942 — the placement this answers
- Issue #2033 — the empty-offer criterion
- PR #2399 — the change that carried it
- The follow-up pull request — the empty-offer guard and the placement tests
- `openspec/changes/the-offer-above-the-rows-and-the-state-below` — the contract
- `2026-09-15-task-editor-unification` — the slot API these two slots came from
