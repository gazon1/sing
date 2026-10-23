---
title: "The difference between two surfaces is a value, not a branch"
date: 2026-10-23
status: accepted
tags: [proposals, presentation, viewmodel, refactoring]
---

# The difference between two surfaces is a value, not a branch

## Context

A proposal is answered in three places: the review screen, the note editor and
the task detail. Each of them answered it in its own code.

The review screen and the note editor were near-twins — the same `decide`,
`edit`, `confirmAll`, `recordFailure` and `clearFailure`, differing only in the
type of the event and in the carrier of the state. The task detail held the same
five operations in a third shape, in a coordinator that assembles nine inputs
into one state.

Three copies drift, and they had drifted:

- the task detail grew an "apply every change" that reported a partly failed
  batch, while the other two stayed silent about it;
- the reason a change failed reached the user in three different wordings —
  `"Apply the change: …"`, `"Confirm failed: …"`, and in one surface nothing
  at all;
- the note editor emitted its errors into a channel nobody read: the same change
  failed on one surface and succeeded on another, and the user could not tell
  which had happened.

The two files were not identical, and that is the point: a shared
implementation that asks "which surface is this?" is a shared implementation
with a switch in it, and the switch grows with every difference the surfaces
grow apart.

## Idea

One implementation of the operations, and the differences handed to it as
values.

## Decision

**One class holds the operations.** `ProposalDecisions` knows the five things a
surface does with a proposal: apply one change, turn one down, change its
value, apply all of a proposal, turn a whole proposal down. It knows nothing
about which surface it serves.

**The differences are values.** `ProposalDecisionStyle` says how a surface
words a decision, whether it stores the reason the user typed, and what a
partly failed batch says. The style is a `data class`; the surface builds its
own.

**What is not a difference: whether a failure is shown.** Every surface that
shows a change shows the failure of that change too — a failure the user cannot
see is a change nobody decided about. So there is no flag for it: the shared
operations hand every failure to the surface, and the surface shows it. A
flag would have been the wrong shape, because the first thing written with one
was a surface that set it to "no", and the requirement the change had written
down was then false on one of the three surfaces.

**Where the reason lives is a port.** `ProposalFailureStore` says where the
reason a change failed lives — in the review screen's own state, in the note
editor's own state, or in a flow of its own for the task detail, whose assembled
state is rebuilt from nine combined inputs and would lose a reason kept inside
it.

**Where the failure goes is a port.** `ProposalErrorSink` says what a surface
does with a failure: the MVI surfaces report it to the crash reporter and show
it as a one-shot event; the task detail reports it and shows it as its own
error event. The sink is where the report happens too — a surface that catches
a failure has to report it, because the scope's handler no longer sees it.

**The task detail reports what the review screen reports.** That includes the
ordinary failures — a change that could not be applied because the task is gone
meanwhile lands in the crash reporter on the task detail now, the way it has
always landed on the review screen. It is new volume on that surface, and it is
a decision rather than a side effect: two surfaces that answer the same
question answer it the same way, and a failure nobody can see on one of them is
a change nobody decided about. The issue key is the operation's name — a fixed
literal at the call site, the way the MVI surfaces group theirs.

**A surface without a write path has nothing to decide.** The task detail's
collaboration dependencies are optional, so the use cases are nullable and the
operations say so instead of throwing.

## Rationale

**A switch in a shared class is a class that knows about its callers.** The
alternative — one implementation, and a question "which surface is this?" — puts
the surfaces' differences in the middle of the shared code, where every new
difference adds a branch. A value keeps the difference at the edge: the shared
code reads a label, and the surface decides what the label says.

**The differences are real and they are words.** The three surfaces genuinely
say different things, and the user reads those words. Making the wording a
value keeps the difference visible instead of hiding it in a `when`.

**The two ports are what the surfaces already had.** The reason lived in the
state and the failure went to an event; naming them says so, and the shared
code does not have to know the shape of either.

**The rules the shared code keeps are the rules, not the wording.** A batch
where some changes failed is a normal outcome, not an error: each failed change
keeps its own reason next to it, and each change that worked has its reason
cleared. That rule is now written once instead of three times, and the surfaces
that differed on it are now the same.

## Consequences

- **One implementation of the five operations**, and one place where the rule
  "a partly failed batch is not an error" is written.
- **The three surfaces keep their own wording**, as values, where a reader can
  see what each surface says.
- **The task detail's optional dependencies are honest.** A task detail without
  a proposal write path does nothing instead of failing.
- **The test for the shared operations is one test class**, not three: the
  surfaces' own tests check that each surface passes its own style and reads
  its own events.
- **A new surface brings a style and two ports**, and nothing else.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalDecisions.kt`
  — the five operations.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalDecisionStyle.kt`
  — the wording, as a value.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalFailureStore.kt`
  — where the reason lives.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalErrorSink.kt`
  — where the failure goes.
