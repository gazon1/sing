---
title: "The review is a place you can go"
date: 2026-10-22
status: superseded
superseded-by: 2026-10-22-the-screen-is-called-proposals
tags: [ai, proposals, ui, navigation]
---

# The review is a place you can go

## Context

The write path now asks first: the chat proposes a change to a task, and the change
waits. That is only half of what "waits" means. A proposal the user cannot reach is a
proposal the user can only answer by telling the assistant to stop — and the answer to
a proposal is not "stop", it is "yes" or "no" about a specific change, which needs the
change to be in front of them.

The machinery was all there and unreachable. `ProposalRepository` had
`watchProposalsByStatus(Pending)` and no production caller. `ApplyProposalItemUseCase`
had a confirm and a reject and no screen that called them. The proposals the delete
tools made — and, since the last change, the ones the chat makes — were stored
somewhere the user could not go.

There was a second, smaller gap. The chat could call forty-odd tools, and a task that
begins with "figure out the pricing" was still a task nobody could start: the first
move needs a decision nobody has made. The tool that decomposes a task does not help
there — a plan is what the user already has, in the task. What helps is the one
physical move, and the sign that it is done.

## Idea

**A screen that lists what is waiting, with two answers per change.**

Not a second confirmation flow. The app already has a confirmation screen, where a
proposal is shown in the context of the thing it changes, and that is where a proposal
should be applied from. The Review screen is the list: what is waiting, what it would
change, and two buttons. The apply path is the app's own — the use case that
validates before it claims and compares-and-sets so a double-tap cannot apply twice.

**One action, with a checkable outcome, for the first step.**

The difference between this and the tool that decomposes a task is not the name; it is
the contract and the prompt. `decompose_task` returns three to seven pieces of a task.
`first_step` returns one action, one outcome that ends it, and one sentence on why
that is first. The rule the prompt states is the one that makes it useful: **a
physical action, not a thought**. "Figure out the pricing" is a thought; the first
action is whatever starts finding the pricing out — and when even that needs a
decision, the action is to write the decision down as a question.

**The review is a destination, not a feature.**

A new top-level destination in the app's existing navigation, in the menu, on both
platforms. Not a new module, not a new family: the screen shows what the assistant
proposed, so it belongs to the assistant's family, and the navigation policy already
knows what to do with a screen in that family.

## Decision

**A Review screen lists the pending proposals.** Each change is a line with "Apply"
and "No". The screen observes the pending proposals and the apply path is the app's
own use case.

**The Review screen is a menu destination**, on Android and on the desktop, in the
shared entry provider so both platforms get it from one place.

**A `first_step` tool names one physical action**, with the checkable outcome that
ends it and one sentence of rationale. Its prompt states the rule; its contract
carries one action.

**The `first_step` tool is also an action on the task itself.** It is an action of
its own in the task's slot, with a use case of its own, and it proposes the first step
as a change the user confirms — the same path every other AI action on a task takes.

## Rationale

The alternative to a Review screen is to answer proposals in the chat, and it is
worse in a way that is easy to see. "Shall I apply this?" in a chat is a question
the user has to answer by reading prose, about a change they have to scroll back to
find, and the answer is a yes or a no that the chat then has to interpret. A list of
what is waiting, with a button on each, is the same decision with less ceremony and
no interpretation.

Putting the screen in the app's existing navigation rather than making a feature of
it is the smaller change and the better one. A "review" feature would want its own
family, its own routing rules, and its own place in the shell's chrome, and it would
hold a list — the same list the proposal repository already holds, already scoped to
the current user, already refreshing itself.

For the first step, the temptation was to make it a list too, because a list is what
the neighbouring tool returns and a list looks more useful. But the task that needs
this is the one that cannot be started, and a list of seven steps does not start it —
it just gives the user seven things to not do first. One action starts the task. The
checkable outcome is there because "done" has to mean something: a step nobody can
check is a step nobody can be finished with, and the user is left with an action they
cannot tell they have completed.

**The review is a procedure, and the prompts say so.** The three ideas the review
rests on — that a task should start with a physical action rather than a decision, that
a burden is not the same as a big task, and that a review decomposes, finds the first
step, and says how the reader will know it is done — are written into the prompts
rather than left as an intention. The chat prompt names the four situations the app is
built around (organising the inbox, planning the day, cleaning the backlog, checking
the whole system), because those are the situations the tools answer. The planning
prompt asks for a plan that fits, because a list of five important tasks is not a plan
when the five together are twenty hours. The project review prompt asks what is stuck
and for one action that would move it, because a review that lists everything has not
reviewed anything.

**`first_step` is in the task's own slot, not only in the chat.** The first physical
action is useful exactly where a task cannot be started — and that is a place the user
is already looking. So it became an action of its own, with a use case of its own and
a proposal of its own, and the checkable outcome travels in the line the user reads: a
step nobody can check is a step nobody can be finished with.

## Consequences

- A change the chat proposed can be applied or turned down without going back to the
  chat.
- The proposals the delete tools made are reachable for the first time.
- The Review screen shows what is waiting, and nothing else: the screens that show a
  proposal in context are still where a proposal is applied from.
- The chat can name the first move of a task that cannot be started, and say what
  finishes that move.
- The `first_step` tool is a tool the chat can call and an action on a task card, so
  the first move of a task that cannot be started is one tap away from the task.
- The written review — a review of a body of work, in prose — is not here. It needs a
  shape and a screen, and neither is settled.

- The chat prompt carries the four situations and the three ideas, so the assistant
  works from the same vocabulary the app does.
- A task that cannot be started can be asked for its first step from the task itself.
- The plan the planning tool returns is meant to fit a day, and says so when it does not.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ReviewScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ReviewViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/FirstStepTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/prompts/Prompts.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/use_cases/LlmUseCase.kt`
- `openspec/changes/2026-10-22-the-review-is-a-place-you-can-go/`
