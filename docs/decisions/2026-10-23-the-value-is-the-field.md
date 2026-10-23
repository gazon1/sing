---
title: "The value is the field, and the kind carries its own behaviour"
date: 2026-10-23
status: accepted
tags: [proposals, vocabulary, domain, ai]
---

# The value is the field, and the kind carries its own behaviour

## Context

A proposal holds a kind of change: `SetTaskField`, `CreateTask`, `SetNoteField`
and so on. Two things about that were wrong, and they were wrong in the same
way — **the meaning of a kind was spread over several files, and the compiler
could not see the spread.**

The first was the shape of a value. A kind named a field and carried a string:

```kotlin
SetTaskField(TaskField.Priority, "42")
```

So the shape of the value was whatever the model happened to type. A priority
was the string `"high"` in one place and the enum `TaskPriority.High` in
another, and the two spellings did not always agree — the code that read the
string had to know both spellings, and the code that wrote the enum had to
know the string. The same knowledge, twice, in two directions.

The second was the behaviour of a kind. What the user reads on the card was one
place; the identity the rejection guard compares was another; the way a kind is
applied was a third, a fourth and a fifth. Adding a kind was a five-file edit,
and **missing one of the five was not a compile error** — it was a kind that
appears in the list and behaves like nothing, or a kind whose summary says
something the card does not show.

## Idea

Make the value the field, and make the kind the only place its behaviour is
written down.

## Decision

**The variant is the field.** A task's value is a sealed hierarchy whose
variants *are* the fields — `TaskValue.Title`, `TaskValue.Priority`,
`TaskValue.DueDate`:

```kotlin
SetTaskField(TaskValue.Priority(TaskPriority.High))
```

The field and the value cannot disagree, because there is no field to disagree
with. There is **one reader per entity** — `TaskValue.read(field, text)` — and
everything that reads a value goes through it: the tools, before they store a
proposal; the planner, when it applies one; and the user, when they change one.

**The kind carries its behaviour.** `ProposalItemKind` declares the summary the
user reads, the fingerprint identity, and the editable surface — what the user
may change, and how the changed value is read again. Each kind overrides them.
A new kind is therefore a new kind, in one file, and the compiler says so when
it is missing one.

**The stored format does not change.** The codec still writes a flat
`{"type": …, "field": …, "value": …}` payload, so the rows written before this
decision keep reading. The domain is typed; the wire format is not. That is a
deliberate trade: the format is greppable in the database and migratable by
hand, and the type error it could allow is caught at the boundary, by the
reader.

**The values live in their own file.** `ProposalValues.kt` holds the values and
their readers; `ProposalItemKind.kt` holds the kinds. A file that holds one
thing is a file whose name says what it is.

## Rationale

The first defect is a class of errors, not one symptom: any value read from
outside — the model, the database, the user — had to be interpreted by code
that knew the spellings. Typing the value moves that knowledge into one
reader, and the compiler checks the rest.

The second defect is the one the review named: adding a kind was a five-file
edit, and the fifth file was optional. Putting the behaviour in the kind makes
the edit one file, and the kinds that have no editable surface say so
explicitly rather than by omission.

The format stays flat on purpose. A typed *storage* format would have been a
migration of every row in every profile, for a defect that a reader at the
boundary already catches.

## Consequences

- **The reader is the choke point.** A value that does not read is refused with
  a reason, and the reason is reportable to whoever produced it — the model, or
  the user. This is what makes "the user may change the value" possible
  without a second interpretation of the text.
- **The summaries now carry the value.** "Set the title to «Buy milk»" instead
  of "Update title" — because a proposal whose value the user cannot read is a
  proposal the user approves blind.
- **The `when`s over the kind are still exhaustive**, and now they are in the
  place where a kind is added.
- **The codec is still the place the compiler does not reach.** It decodes on a
  string, so a kind it was not taught to read is dropped silently. That is
  covered by a test, not by a type — see the round-trip check in
  `docs/architecture/proposals.md`.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalValues.kt`
  — the typed values and their readers.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItemKind.kt`
  — the kinds and their behaviour.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalMapper.kt`
  — the codec, and the format that deliberately did not change.
- `docs/architecture/proposals.md` — the map of the vocabulary.
