# the-static-kind-is-a-guarantee

Issue: — · Related: `docs/decisions/2026-10-21-the-static-kind-is-a-guarantee.md`

Status: **accepted**
Tags: **text field rewrite contract static source owner**

## What

A **static** source says: *the upstream replaces the value only with one the field never
produced*. That was a claim in a sentence, about writes that happen wherever they happen,
and nothing could check it.

Now the promise lives in the **owner's type**. A static owner holds a `FieldOwnedText` —
a value whose only operations are `seeded(value)`, `echoed(fieldText)`, `cleared()` and
`empty()`, with a private constructor — so every write to the value is named, and there is
no fourth kind of write to make.

## Why

An owner that holds a bare `String` writes to it with `state.copy(query = …)`, and that
write says nothing: a value the field produced, a value the upstream loaded, the field's
own text coming back, an emptying — all the same shape at the call site.

And the failure is silent. A static upstream that replaces the value with something the
field produced has its replacement **dropped** by the echo guard, and the field keeps the
text the user typed. Nothing fails; the server's value is simply gone.

## The shape of the fix

- `FieldOwnedText` in `core.ui.textfields`, next to the source types: a private
  constructor, `seeded`, `echoed`, `cleared`, `empty`.
- Every static owner holds one: the search query, the notes' and the projects' search
  query, the chat input, the saved agenda's name, the annotation draft, the project
  editor's name and description, the AI provider settings, the create form's title and
  description.
- The source is built from the typed value: `StaticText(value.text)`. The source type does
  not change.
- The rewrite contract checks it: a static row's owner must declare a `FieldOwnedText`.

## What not to do

- Do not hand a static owner a bare `String` again. The check exists for that.
- Do not write the value without naming the write. A write that is not an echo, a seed or
  a clear has no name and no way to be written.
- Do not use this type for a value the upstream really owns. The GenUI surface's value is
  the server's, and the count of the server's writes is not a value the field writes.
