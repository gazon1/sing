# the-offer-above-the-rows-and-the-state-below

## What

The task detail's editor gives its caller two places for content the editor does not
render itself, instead of one:

- an upper place, drawn directly under the description, for content that **asks** the
  person for a decision — the first-run offer and the AI proposals waiting for an answer;
- a lower place, drawn after the attribute rows, for content that shows the task's own
  **state** — the checklist, the subtasks, the attachments, the tags, the logbook, the
  time tracking.

Both are optional. A caller that fills neither draws the screen it drew before.

## Why

One place carried both kinds, so the two could not be placed differently. When the child
work was moved below the attribute rows — so that the due date, the priority and the
project stay on the first screen — the offer and the proposals moved down with it. An
offer waiting for an answer then sat under a screen of fields and empty "add" cards,
which is where nobody looks.

The opposite complaint was measured on a 360 dp phone before the child work moved: a new
task's first screen was three empty "add" cards, and the due date was reachable only by
scrolling. Both complaints are the same missing distinction: an ask is not state.

## Scope

### In scope

- Two optional content places on the editor, with documentation that says which is drawn
  where and what each is for.
- The lower place is named for what it carries, and the sections that document where they
  are drawn use that name.
- The task detail's split: the offer and the proposals above the attribute rows; the child
  work below them, in its existing relative order.
- Every proposal action keeps working: confirm one, confirm all, reject with a reason,
  edit the text, dismiss.

### Out of scope

- Whether an empty child section is drawn at all. That is a separate question from where
  the drawn ones sit.
- The dependencies card, which sits above the attribute rows and is not one of them.
- The order of the attribute rows among themselves.
- The device-level check of the fold on a phone.
- A test that seeds a proposal in order to pin the upper half of the order.
- The app-wide spacing and shape scale.
