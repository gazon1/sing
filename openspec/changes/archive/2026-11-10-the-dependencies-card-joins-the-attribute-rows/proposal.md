# Change: the dependencies card joins the attribute rows

**Issue:** #2010 · **Spec:** `tasks`

---

## What

The task detail draws the dependencies card first among the attribute rows — directly
under the description, above the priority and the due date. The card appears only when
there is something to pick from, so on a task with relations the priority and the due
date sit one row lower than on a task without them.

The card moves after the due-date row.

## Why

The rows are what the task is: when it is due, how important it is, which project it
belongs to. A card that appears because the task has relations must not move the fields
a person fills in. On a narrow phone one row is the difference between above the fold
and below it.

## What changes

- The dependencies card is drawn after the due-date row and before the start-date row.
- Its condition, label, callback and test tag are unchanged: it appears when there is
  something to pick from, names the relations, and opens the dependency picker.
- The order test measures the priority and the due date with and without the card and
  requires the same position.

## Out of scope

- The child sections and their order. That is
  `the-offer-above-the-rows-and-the-state-below`.
- The order of the other attribute rows among themselves.
