# the-count-travels-with-the-value

Issue: — · Related: `docs/decisions/2026-10-21-the-count-travels-with-the-value.md`

Status: **accepted**
Tags: **text field rewrite contract draft revision**

## What

The count of a value's replacements travels **inside** the value, in a `Revisioned<T>`, and
it moves only through `replacedBy`.

An owner that holds a value and a counter in two fields can lie, and the lie is silent: it
replaces the value and leaves the counter where it was. The field reads a count that did
not move, treats the replacement as an echo of its own last push, and keeps the text the
user typed. Nothing fails — the field just stops listening.

## Why

The count and the value are two facts about the same thing, so they belong in one object.
Changing the value means saying *how* it changed, and there is no way to change it without
the count following. The constructor is private for the same reason: a constructor that
takes a value and a count is a way to write down a count that does not match the value.

The three rules the count had to obey — never for a keystroke, never for the echo of the
owner's own save, always for a replacement — become two operations instead of a discipline
in three places.

## The shape of the fix

- `Revisioned<T>` in `core.ui.textfields`, next to the source types it belongs with: a
  private constructor, `initial(value)`, `typed(newValue)` (the count does not move) and
  `replacedBy(newValue)` (**the count moves**).
- The task draft's title and description, the project draft's name and description and the
  note editor's title hold a `Revisioned<String>`. The drafts' `revision` field is gone.
- The count is per field, not per draft: a rewrite that replaces both of a draft's fields
  moves both counts, in the same operation.
- The rewrite contract's evidence follows: the counter is a `Revisioned` property, and the
  count advances through a call to `replacedBy`.

## What not to do

- Do not add a counter next to a value again. The two belong in one object.
- Do not move a count by hand. `replacedBy` is the only way, and that is the point.
- Do not use `Revisioned` for a count that is not a value's — the GenUI surface counts the
  *server's writes*, and a write may not change the value at all. That count stays a plain
  number the controller increments.
