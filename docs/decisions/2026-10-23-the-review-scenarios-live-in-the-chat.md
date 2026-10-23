---
title: "The review scenarios live in the chat, and the prompt stops promising otherwise"
date: 2026-10-23
status: accepted
continues: 2026-10-22-the-screen-is-called-proposals
tags: [ai, prompts, product, proposals]
---

# The review scenarios live in the chat, and the prompt stops promising otherwise

## Context

The chat's system prompt describes four situations the model works in:
organising the inbox, planning the day, cleaning the backlog, checking the
system's health. It then says:

> These are the four situations you work in, and they are the four the app is
> built around.

The second half is not true. The app is not built around them. There is no
screen that organises the inbox, no screen that plans the day, no screen that
cleans the backlog and no screen that checks the system's health. The app has
a task list, a calendar, notes, projects — and one screen for the changes the
model proposed, called "Proposals".

So the prompt told the model something untrue about the product it lives in.
That is not a cosmetic sentence: a model that believes the app is built around
four screens will say so. It will offer the user "the inbox screen", describe
sections of the app that do not exist, and be confused when the user asks for
them. The user is then holding a conversation about an app that is not this one.

The review that asked for this put the choice plainly: **run the scenarios from
a screen, or take them out of the prompt.** The decision is a product one, not
a code one.

## Idea

Either the app grows four screens, or the prompt stops claiming it does.

## Decision

**The scenarios stay in the chat, and the prompt stops claiming the app is
built around them.**

The sentence becomes "These are the four situations you work in." The
situations themselves stay — they are good guidance about what the model is
for, and they describe what the model does when the user asks in the chat.

**Bringing a scenario to the app is separate work, with a name of its own.**
It would mean: a screen per scenario (or one screen with four modes), a place
for the model's review to run and to be re-read, and a way to show the result
next to the work it talks about. That is a feature, not a correction, and it
deserves to be asked for on its own terms rather than smuggled in as a
consequence of a sentence in a prompt.

## Rationale

The prompt's job is to tell the model what it is for and what it may do. It
does not have to tell it what the product is shaped like — and when it does, it
has to be true.

Two of the four options were wrong on their own terms. Taking the scenarios out
of the prompt would remove real guidance: the model is better at "organise my
inbox" when it knows that is one of the things it is for. And building four
screens would answer a question nobody asked — the app's own review found that
the *screen* was the thing that promised too much, and the fix there was to
stop promising, not to build more.

The cheapest honest fix is to make the sentence true.

## Consequences

- **The prompt no longer describes a product that does not exist.** The model
  is told what situations it works in, and not told a story about the app's
  shape.
- **The scenarios are still reachable.** They are the model's, and the chat is
  where the model works.
- **The screen is not a review, and says so.** "Proposals" lists proposals.
- **If the scenarios should come to the app, that is a change with its own
  spec.** The decision here does not close the door on it; it stops the app
  from claiming something it does not do.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/prompts/Prompts.kt`
  — the sentence.
- `docs/decisions/2026-10-22-the-screen-is-called-proposals.md` — the decision
  this one continues: the screen is called Proposals because it lists proposals.
- `docs/decisions/2026-10-22-the-review-is-a-place-you-can-go.md` — the
  decision that put the screen there.
