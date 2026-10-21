# the-note-body-has-the-same-contract-as-the-title

Issue: — · Related: `docs/decisions/2026-10-21-the-note-body-has-the-same-contract-as-the-title.md`

Status: **accepted**
Tags: **text field rewrite contract note editor owned source**

## What

The note editor has two fields — the title and the body — and both may be rewritten from
outside the editor: a proposal applied to the note, a sync from another device.

The title carries the count of its own replacements, in a `Revisioned`, and the title's
field reads that count. The body carried no count at all: the signal that told the editor
"the content was replaced, take it" was the **title's** count.

Now the body is a `Revisioned<String>` too, with a count of its own, and the editor session
reads *that* count. A keystroke is a `typed` for both fields; a rewrite moves the count of
the field that actually changed, and only that field's.

## Why

A count is a statement about one value. The title's count moving because the *body* was
rewritten said "the title was replaced from outside", which is false — and the title's
field acts on such a statement.

The other way round was worse: a rewrite of the title alone re-seeded the rich editor with
the note's body, for a rewrite that had not touched the body at all.

And the failure the contract exists to prevent is the one the editor is most exposed to:
the editor owns the content it shows, so a rewrite it did not make reaches it only as a
request to take the new content. Without that, the next keystroke dispatches the old body
over the new one, and the rewrite is silently undone by the user's next word.

## The shape of the fix

- `EditorState.Editing.html` is a `Revisioned<String>`, and `bodySource` is its `OwnedText`.
- A keystroke is `typed`; a rewrite is `replacedIfChangedBy` — the count moves only when
  the value actually changed.
- The editor session re-seeds the rich editor when the body's count moves.
- `Revisioned.replacedIfChangedBy` says it in one place.
- The rewrite table gains a row for the body: the same owner, counter and advancer as the
  title.

## What not to do

- Do not announce one field's rewrite through another field's count.
- Do not move a count for a replacement that did not change the value: the echo of the
  owner's own save is not a rewrite.
- Do not treat the body as a special case. It is a field, and it answers "who may rewrite
  it?" the same way the title does.
