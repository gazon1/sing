# The read surface speaks the app's language

Issue: — · Related: `docs/decisions/2026-10-22-the-read-surface.md`

## What

Let a reader ask for the user's tasks the way the app names them, and let the answer be
the app's answer.

`list_tasks` and `get_task` are the tools a reader reaches for when they ask "what are my
tasks?" Today they answer a different question. `list_tasks` takes a project id and a
limit, and returns every task it finds, completed and unfinished alike, with no way to
ask for the ones that are overdue, the ones due today, the ones that are waiting on
something, or the ones the user has not touched in a month. `get_task` returns a title, a
description, a boolean and a project id — not a due date, not a priority, not what the
task is blocked on, not how far its checklist has got.

The app knows all of this. The screens show it. The tool does not, and a reader who asks
"which of my tasks are overdue?" gets a list of tasks with no overdue in it, and no way
to tell.

## The change in behaviour

- A reader asks for a **named set**: `inbox`, `today`, `upcoming`, `overdue`, `blocked`,
  `stagnant`, `pinned`, `someday`, `active`, `completed`, `all`.
- The set is resolved by the app's own rules, so the tool's answer and the screen's
  answer are the same answer.
- A reader may **narrow** the set by project, by tags and by priorities, and the
  narrowing is an intersection.
- A read may ask for **summaries** or for **full cards**, and a full card carries what
  only a single task can be asked about.
- A long list comes back **one page at a time**, and the cursor says which list it
  belongs to — a cursor from one set is refused rather than used for another.
- The answer says **how the selected tasks are doing**: how many are overdue, blocked
  and stagnant.
- A call whose arguments do not fit the tool is a **readable error**, not a protocol
  failure.

## What is not in this change

- The write tools are untouched. What a write does, and whether it should ask first, is
  a separate change.
- The search tool is untouched. Making search go through the search use case is a
  separate change, and it is already under way.
- The screens are untouched. Nothing about how the app shows tasks changes; only what
  the tools say about them.

## Why now

The chat can call tools. It can call them, but the tools it can call cannot answer the
questions people actually ask. Making the chat able to reach the data was the first
half; making the data speak the app's language is the second, and until it is done the
chat is a guessing machine with a lookup attached.
