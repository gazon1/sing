---
title: "The kind of the source is a property of the parameter"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, compose]
---

# The kind of the source is a property of the parameter

## Context

The task editor serves two upstreams: the create draft, which loads the title and the
description once, and the detail draft, whose upstream owns them and may replace them with
anything. The two are different kinds of source — `StaticText` and `OwnedText` — and the
difference matters: only the owned kind carries the count of the replacements, and only the
owned kind lifts the echo guard.

One editor served both. Its sources were parameters of type `TextSource` — the sealed
parent — and that parent travelled the whole way: from the model's fields, through the
editor's parameters, into the field components, and finally into the bridge, which decided
the kind with a `when`:

```kotlin
internal fun rememberBridgedTextFieldState(source: TextSource, onTextChanged: (String) -> Unit) =
    when (source) {
        is OwnedText -> rememberBridgedTextFieldState(source, onTextChanged)
        is StaticText -> rememberBridgedTextFieldState(source, onTextChanged)
    }
```

The `when` was exhaustive, and the sealed parent made the answer closed — but it was an
answer given at runtime, in a place that did not know the answer. The place that knew was
the screen: the create screen had a static title, the detail screen an owned one. Every
layer between them had forgotten.

What that cost:

- **The field component could not say what it held.** `TaskTitleRow` took a `TextSource`, so
  it could be wired with either kind, and nothing in its signature said which it expected.
- **A wrong wiring compiled.** A static title handed to the detail editor, or an owned one
  handed to the create editor, was a `TextSource` either way — and the mismatch showed up as
  a field that stopped listening, not as a compile error.
- **The shape of the contract was invisible at the call site.** The reader of
  `TaskEditorContent(titleSource = …)` could not tell from the call whether the title could
  be rewritten from outside.

## Idea

The kind of the source is a property of the parameter that carries it — decided where the
knowledge is, and checked by the compiler.

## Decision

**The editor is split, and the bridge has no overload that decides.**

- `TaskEditorContent` is the editor of a task whose upstream **owns** the title and the
  description: its parameters are `OwnedText`.
- `TaskCreateEditorContent` is the editor of a task whose upstream **loads** them once: its
  parameters are `StaticText`.
- Both hand their source to the bridge and show what comes back. The shared body takes the
  bridged states, so it never asks which kind of upstream it has.
- The field components — `TaskTitleRow`, `TaskDescriptionField` — take the state. They show
  a field; deciding who may rewrite it is not their business.
- **The bridge has no `TextSource` overload.** The two kinds are two overloads, and there is
  no third that reads the kind at runtime.
- The model no longer carries the sources. They are the editor's parameters, and their kind
  is the editor's business.

## Rationale

- **A sealed parent in a parameter is a promise the compiler cannot keep.** Anyone holding a
  `TextSource` may pass it on, and the receiver has to decide what to do with it. The
  decision is the `when`, and the `when` is where the kind stops being known.
- **Splitting puts the difference where it can be read.** The detail editor takes an owned
  title; the create editor takes a static one. A caller holding the wrong kind does not
  compile, and a reader of the call site sees the kind without opening the type.
- **The bridge stays closed.** Two overloads, one per kind: a third kind of source cannot be
  added without the bridge saying what it means. That was true of the `when` too, but the
  `when` made the *bridge* responsible for the meaning of a kind it could not see.
- **The field component is released from a question it cannot answer.** It shows a state.
  The kind was decided where the knowledge is, and it is written down in the signature.

## Consequences

- The task editor's rewrite contract is published at
  `feature/tasks/presentation/components/detail/TaskEditorContent.kt` — the file that
  bridges the fields — and the two field components are no longer field sites.
- The detail screen spells out a model and a callback bundle instead of forty parameters.
  The model existed for exactly that; the screen now uses it.
- A component that serves two kinds of upstream has to be split. That is the cost, and it is
  the cost that buys the guarantee: the kind is a compile-time property of a parameter, and
  the bridge can no longer be asked to guess it.

## Links

- `2026-10-21-the-count-travels-with-the-value` — the owned kind's count, and why the two
  kinds are different.
- `2026-10-19-text-field-state-migration` — the migration this contract came out of.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/TextFieldStateBridge.kt`
  — the two overloads, and no third.
