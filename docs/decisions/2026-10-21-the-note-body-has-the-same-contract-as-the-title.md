---
title: "The note body has the same contract as the title"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, notes]
---

# The note body has the same contract as the title

## Context

The note editor has two fields: the title and the body. Both are owned by the editor, and
both may be rewritten from outside it — a proposal applied to the note, a sync from another
device.

The title has the contract. The count of the title's replacements travels with it, in a
`Revisioned`, and the title's field reads the count to tell a replacement from an echo of
its own last push.

The body did not. The body was a plain `String` in the editor's draft, and the signal that
told the editor "the content was replaced, take it" was the **title's** count. Two
consequences, and neither of them is visible in the code:

- **A rewrite of the body was announced by another field's counter.** The body had no count
  of its own, so the editor could not tell "the body was replaced" from "the title was
  replaced". It re-seeded the rich editor on both, and the body's own value said nothing
  about what had happened to it.
- **A rewrite of the title re-seeded the body.** The title's count moved, the editor read
  that as "the content changed", and it took the note's body and put it into the rich
  editor — for a rewrite that had not touched the body at all.

And the failure this contract exists to prevent is the one the editor is most exposed to:
the editor owns the content it shows, so a rewrite it did not make reaches it only as a
request to take the new content. Without that, the editor keeps the old text and the next
keystroke dispatches the old body over the new one — the rewrite is silently undone by the
user's next word.

## Idea

The body is a field like any other. It should have the same contract as the title: a value
that carries the count of *its own* replacements, and a source that says who may rewrite
it. The title's count should say something about the title.

## Decision

**The note's body is a `Revisioned<String>` with its own count, and the editor session reads
that count.**

- `EditorState.Editing.html` is a `Revisioned<String>`, and `bodySource` is its `OwnedText` —
  the same shape as the title's `titleSource`.
- **A keystroke is a `typed`**: the count does not move, exactly as for the title.
- **A rewrite is a `replacedIfChangedBy`**: the count moves only when the value actually
  changed, and only for the field that changed. A rewrite of the body leaves the title's
  count where it was; a rewrite of the title leaves the body's count where it was.
- **The session re-seeds the editor when the body's count moves** — not when the title's
  does.
- `Revisioned.replacedIfChangedBy` says it in one place: the count moves if the value
  changed, and stays where it was if it did not.

## Rationale

- **Each field counts its own replacements.** A count is a statement about one value. A
  title's count that moved because the *body* was rewritten says "the title was replaced
  from outside", which is false, and the title's field acts on it.
- **The body is not a special case.** The editor's session already re-seeds the rich editor
  when it is told the content was replaced; what was missing was a count that belonged to
  the body. Making the body a `Revisioned` gives the session the same thing the title's
  field has, and the two fields now answer the same question the same way.
- **The count moves only for a real change.** `replacedIfChangedBy` is the difference
  between "the upstream wrote this value" and "the upstream wrote *this* value". A
  replacement that lands on the value the owner already holds is the echo of the owner's
  own save, and it must not announce a rewrite.
- **It closes the last gap in the count's own story.** The count travels with the value so
  that it cannot be left behind — and a count that belongs to another field is a count
  that can be left behind in a way no type can prevent. Now that the body carries its own,
  every field that reads a count reads *its* count.

## Consequences

- The editor's draft holds `html: Revisioned<String>`; the places that read the body as a
  string read `html.value`, and the body row of the rewrite table names the same owner,
  counter and advancer as the title row.
- A rewrite that changed neither field changes nothing — there is no rewrite to report.
- A rewrite that changed one field moves that field's count, and only that field's: the
  title's field pulls a new title, the editor takes a new body, and neither is told about
  the other.
- The session no longer re-seeds the editor when the title alone was rewritten. The editor
  keeps the content the user is working on, which is the content that was not replaced.

## Links

- `2026-10-21-the-count-travels-with-the-value` — the count moved into the value; this
  decision gives the body a count of its own.
- `2026-10-19-text-field-state-migration` — the migration this contract came out of.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/Revisioned.kt` —
  the type, and `replacedIfChangedBy`.
