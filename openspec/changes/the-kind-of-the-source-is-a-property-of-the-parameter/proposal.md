# the-kind-of-the-source-is-a-property-of-the-parameter

Issue: — · Related: `docs/decisions/2026-10-21-the-kind-of-the-source-is-a-property-of-the-parameter.md`

Status: **accepted**
Tags: **text field rewrite contract compose task editor source kind**

## What

The task editor serves two upstreams with two different kinds of source: the create draft
loads the title and the description once (`StaticText`), and the detail draft's upstream
owns them and may replace them (`OwnedText`).

The kind was erased on the way: the sources travelled as `TextSource` — the sealed parent —
through the model, the editor and the field components, and the bridge decided the kind at
runtime with a `when`.

Now the kind is a compile-time property of the parameter:

- `TaskEditorContent` is the editor whose upstream **owns** the title and the description:
  its parameters are `OwnedText`.
- `TaskCreateEditorContent` is the editor whose upstream **loads** them once: its parameters
  are `StaticText`.
- The shared body takes the bridged states, and the field components take the state.
- **The bridge has no overload that takes `TextSource`.** Two kinds, two overloads.

## Why

A sealed parent in a parameter is a promise the compiler cannot keep: anyone holding a
`TextSource` may pass it on, and the receiver has to decide what to do with it. The
decision was a `when` in the bridge — an answer given at runtime, in a place that did not
know it. The place that knew was the screen.

So a static title handed to the detail editor compiled, and the mismatch showed up as a
field that stopped listening rather than as a compile error. And a reader of the call site
could not tell from the call whether the title could be rewritten from outside.

## The shape of the fix

- The editor is split into the two kinds, and each half's signature says which kind it is.
- The field components show a state; they do not decide who may rewrite it.
- The model no longer carries the sources: they are the editor's parameters, and their kind
  is the editor's business.
- The rewrite contract publishes the task editor's rows at the file that bridges the fields.

## What not to do

- Do not add an overload that takes the sealed parent and dispatches on it. That is the
  runtime decision this change removes.
- Do not let a component that serves two kinds of upstream hold the parent in a parameter.
  Split it: each half knows its kind, and the kind is checked by the compiler.
