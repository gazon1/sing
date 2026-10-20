---
title: "The count travels with the value: Revisioned<T>"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, drafts]
---

# The count travels with the value: `Revisioned<T>`

## Context

An owner of a bridged text field has to say two things about the value it holds: **what the
value is**, and **how many times it was replaced from outside**. The second is what the
field reads to tell a replacement from an echo of its own last push.

The three owners hold those two facts in two places:

```kotlin
data class TaskDetailDraft(
    val title: String,
    val description: String,
    val revision: Long = 0,
)
```

The lie this shape allows is silent and mechanical: an owner replaces the value and leaves
the counter where it was. Nothing fails, no test goes red, the field reads a count that did
not move — and treats the replacement as an echo of its own last push, so it keeps the text
the user typed and the replacement is lost. The failure is not a crash; it is a field that
stops listening.

Writing the replacement correctly is a discipline the shape does not enforce. The
discipline *is* the contract, and the contract's job is to make the wrong thing impossible
rather than checked.

The counter also has to mean exactly one thing: **replacements**. It must not move for a
keystroke (that would make it a second copy of the value) and it must not move for the echo
of the owner's own save (nothing was replaced). Three rules to remember, in two places, per
owner.

## Idea

The count and the value are two facts about the same thing. Put them in one object, and
give that object exactly one way to be changed by a replacement.

## Decision

**`Revisioned<T>` carries a value and the count of its replacements, and the count moves
only through `replacedBy`.**

- **`Revisioned<T>` lives in `core.ui.textfields`**, next to the source types it belongs
  with. It has a **private constructor**: the only ways to make one are `initial(value)` —
  a value nothing has replaced yet — and the two operations below.
- **`typed(newValue)` — the field's own change.** A keystroke. The count does not move: a
  keystroke is the owner's own change and needs no announcement.
- **`replacedBy(newValue)` — a replacement from outside.** **The count moves.** It is the
  only way to move it, and that is the point: a rewrite that does not say so cannot be
  written down.
- **The echo of the owner's own save changes nothing.** The value is what it already was,
  so there is no operation to call and the count stays where it is.
- **The three owners hold the value inside it.** The task draft's title and description,
  the project draft's name and description, and the note editor's title are
  `Revisioned<String>`. The count is no longer a field the owner can forget; it is part
  of the value the field is handed, and the source is built from the two together.
- **The count is per field, not per draft.** The task draft's title and description each
  carry their own count, because they are two fields with two upstreams. A rewrite that
  replaces both moves both counts, in the same operation — which is what the shared count
  did before, except that the shared count could be moved without the value moving with
  it.
- **The rewrite contract follows.** The contract test's evidence for "the counter is
  declared" and "the counter advances" now looks for a `Revisioned` property and for a
  call to `replacedBy` — the movement is in the type, so the evidence is in the type.

## Rationale

- **The lie has to be impossible, not checked.** A check would have to know what the owner
  meant: whether a change was a keystroke or a replacement is a fact about the world, and
  the code that holds the value is the only place that knows it. So the code that holds
  the value is the place that says it — and it can only say it one way.
- **A private constructor closes the back door.** A constructor that takes a value and a
  count is a way to write down a count that does not match the value — the very lie this
  type exists to prevent. With the constructor private, the only way to make a new
  `Revisioned` is to say what kind of change it is.
- **The three rules become one.** "Never for a keystroke, never for the echo, always for a
  replacement" is a rule per owner, remembered in three places. `typed` and `replacedBy` are
  a rule per *change*, and there is no third case to remember: the echo does not change
  the value, so there is nothing to call.
- **The count ends up where the field reads it.** The source a field is handed is built
  from the value and the count together, so the two cannot belong to two different
  moments — the failure mode the GenUI context had with two independent observations,
  now impossible for the drafts as well.

## Consequences

- The drafts' `revision` field is gone, and with it the possibility of a count that does
  not match the value. `TaskDetailDraft.revision` becomes `title.revision` and
  `description.revision`; `NotesUiState.Editing.revision` becomes `title.revision`.
- The count is per field, so a draft has one count per field. The rewrite contract's table
  already had one row per field, so the table does not change.
- **The GenUI surface's counter is a different kind of thing and stays one.** The surface
  counts the *server's writes*, not the replacements of a value: a server write may not
  change the value at all, and the count is a property of the surface, not of a value. So
  the contract test accepts two forms of counter — a `Revisioned`, whose count travels
  with a value, and a plain count of the upstream's writes — and the skill says which is
  which.
- The `Revisioned` type is not a data class, so it has no `copy`: the operations are the
  only way to change it. It implements `equals` and `hashCode` by hand, because the drafts
  that hold it are data classes and their equality is part of what the tests assert.
- A new value type in `core` means the drafts' equality now depends on it: two drafts with
  the same value and the same count are equal, and two drafts with the same value and
  different counts are not. That is the point.

## Links

- `2026-10-20-the-rewrite-authority-is-a-type` — the decision that moved the answer into
  the type of the source; this decision moves the count into the type of the value.
- `2026-10-21-the-genui-context-hands-out-the-source` — the value and the count arrive
  together for a surface field, because the context builds the source.
- `2026-10-21-the-contract-is-published-once` — the contract is published in the skill's
  tables, and the tables are the registry the tests read.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/Revisioned.kt` —
  the type.
