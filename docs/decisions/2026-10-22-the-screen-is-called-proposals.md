---
title: "The screen is called Proposals, because it lists proposals"
date: 2026-10-22
status: accepted
supersedes: 2026-10-22-the-review-is-a-place-you-can-go
tags: [proposals, ui, naming, ai]
---

# The screen is called Proposals, because it lists proposals

## Context

The app had a screen called "Review". It listed the proposals waiting for a
decision — and nothing else. It did not review anything.

The review *procedure* exists, but it lives in the chat: four scenarios (inbox
organization, daily planning, backlog cleanup, system health check) are described
in the system prompt, and the model runs them when the person asks in the chat.
The screen was named after the procedure it does not run.

That is a promise the screen does not keep. A person who opens "Review" expects
the app to look at their work — to say what is out of place, what needs a plan,
what has been sitting too long — and finds a list of changes the assistant
proposed, most of them from a conversation that happened yesterday. The name
teaches the wrong expectation, and a screen that teaches a wrong expectation is
worse than a screen with an ugly name: the person either waits for the review
that never comes, or stops trusting the word.

The screen also drew its own card. The shared proposal card — the one the task
detail and the note editor use — was written for exactly this, and the screen
drew a second one beside it: its own buttons ("Apply" / "No"), its own
vocabulary, its own rendering. Two renderings of one thing meant three renderings
of one thing overall, and the person who met a proposal on a task and a proposal
on this screen met two different sets of words for the same act.

## Idea

Either the review procedure comes to the app, or the screen stops calling itself
a review.

## Decision

**The screen is called "Proposals".** The destination, the screen, the ViewModel
and the state's names all changed with it — a name that lies in the code is the
same lie as a name that lies in the interface, and the code is what the next
developer reads.

**The review scenarios stay in the chat, and that is recorded.** The four
scenarios are the model's, and the app has no agent-run machinery for "run this
scenario and show me what it proposes". Bringing them to the app is its own
piece of work — it needs a place to run an agent from the app, a way to show a
run in progress, and a way to bring its proposals back. This change does not
pretend to have done that; it makes the screen say what it is, so the gap is
visible instead of hidden behind a name.

**The screen draws the shared card.** One card, one set of buttons, one
vocabulary. The person who meets a proposal on a task, in a note and on this
screen meets the same words and the same gestures, and the third rendering of a
proposal is gone.

**The empty state says what the screen is.** "Nothing to propose", and the line
under it says that changes the assistant proposes show up here. It is a message
the person reads, so it stays selectable — a message a person cannot copy is a
message they cannot send to the assistant to ask about it.

## Rationale

The choice was between two honest states: the procedure comes to the app, or the
name stops promising it. The procedure is the better end state — a person should
be able to open the app and ask for a review of their work — but it is a
different piece of work, and shipping a screen named "Review" that does not
review is not a smaller version of it. It is a lie that gets more expensive the
longer it stands.

Naming the screen after what it holds has a second effect: the *next* change
becomes obvious. When the review procedure does come to the app, it will be a
new thing with its own name, and it will sit next to "Proposals" rather than
replace it — because a list of proposals and a review are two different things,
and a user who has both can tell which one they opened.

The shared card is the same argument as the name, one level down: three renderings
of a proposal is three chances for the wording to drift, and the drift is what
the person reads. The card already exists and already says "AI Suggestions" and
"Accept all" — words chosen once, with the shape of the decision in mind.

## Consequences

- **The destination changed name.** `AppDestination.Review` is
  `AppDestination.Proposals`, and its stable content key changed from `review` to
  `proposals`. A saved back stack that remembered the old key will not restore
  that screen's state — the screen holds no state of its own, so nothing is lost
  that the person can see.
- **The review procedure is still chat-only.** The gap is recorded rather than
  closed, and this decision is the note that says where to look.
- **One card, three surfaces.** The screen no longer draws a card of its own, and
  the buttons it offers are the shared ones: accept a change, turn a change down
  with a reason, accept the whole proposal, dismiss the whole proposal.
- **The screen can now be about a proposal that has no target yet.** A creation is
  a proposal too, and the card draws it like any other.

## Links

- `docs/decisions/2026-10-22-the-review-is-a-place-you-can-go.md` — the decision
  this one continues: the screen exists so a proposal is somewhere the person can
  reach.
- `docs/decisions/2026-10-02-ai-proposal-confirmation.md` — the confirmation
  screen the proposals wait on.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/components/ProposalCard.kt`
  — the one card.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalsScreen.kt`
  — the screen.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/prompts/Prompts.kt`
  — where the review scenarios still live.
