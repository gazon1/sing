# The review is a place you can go

Issue: — · Related: `docs/decisions/2026-10-22-the-review-is-a-place-you-can-go.md`

## What

Give the proposals a screen, and give the assistant one more way to help.

Two things are missing after the write path asks first. The first is a place for the
user to agree: a proposal that nobody can reach is a proposal that can only be
answered by the chat, and the chat is the wrong place to answer it — the answer is
"yes" or "no" about a specific change, and the user should be able to see the change.
The second is the first step of a task that cannot be started.

## The change in behaviour

- A **Review** screen lists the proposals waiting for a decision. Each change is a line
  with two answers: apply it, or turn it down. Nothing is applied until the user says
  so, and the apply path is the app's own.
- A **first_step** tool names the first *physical* action of a task — one action, not a
  plan — with the checkable outcome that ends it, and one sentence on why that is
  first. It is for the task that begins with "figure out X", where the first move
  would itself need a decision nobody has made.
- The Review screen is reachable from the menu, on both platforms.

## What is not in this change

- The confirmation screen that already exists is not replaced. The Review screen is a
  list of what is waiting; the screens that show a proposal in context are where a
  proposal is applied from.
- The `first_step` tool is not wired into the task's slot API. It is a tool the chat
  can call, and the slot that would offer it on a task card is separate work.
- The review prompts — a written review of a body of work — are not in this change.
  They need a screen to live in and a shape to be read in, and neither is settled
  here.

## Why now

Because the chat can now propose things, and a proposal with nowhere to go is a
proposal the user can only dismiss by telling the chat to stop. And because the
chat can call tools, the tools it reaches for are worth making worth reaching for.
