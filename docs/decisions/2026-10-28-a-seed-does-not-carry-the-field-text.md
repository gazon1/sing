---
title: "A seed does not carry the field's own text"
date: 2026-10-28
status: accepted
tags: [testing, text-fields, compose, contract]
---

# A seed does not carry the field's own text

## Context

`FieldOwnedText` gives the upstream's writes names. `seeded` says *the upstream
loaded a value from outside the field*; `echoed` says *the field's own text
came back*; `cleared` says *the upstream emptied it*. The type turned a
promise that lived in a sentence into a promise that lives in the shape of the
owner's writes.

The promise `seeded` makes is a claim about a **value**, and the bridge acts on
the claim. The echo guard drops every value the field itself produced: the
value the field holds now, the value it held last, and the value it pushed
upstream earlier. A seed that carries such a value is therefore never applied
— the owner believes it replaced the field, and the field still shows the old
text.

That is the lie. It is invisible today, because provenance is not in the
name: an owner that receives the field's own value as a parameter of a handler
cannot tell it apart from an owner that receives a loaded value, and the
parameter's name says nothing either way. `SavedAgendaViewModel` seeds a
saved agenda's `name`, and the shape of that call is the shape of a call that
seeds the field with the field's own name.

## Idea

The type cannot carry the provenance of a value the owner did not produce, but
it can say what a seed may *not* carry: a value read out of a field.

A `FieldOwnedText` holds exactly one thing — its text — and the only way to
get that text out of it is `.text`. So a seed whose value is a `.text` read is
seeding a field with a field's text, whatever the receiver is. That shape is
visible at the call site, and it is the shape the lie takes when it is written
down.

## Decision

**A seed does not carry the field's own text.** The rule is a contract test
(`FieldOwnedTextSeedContractTest`): no `seeded(...)` call in `commonMain` may
carry a value that reads `.text`.

The honest spellings stay as they are:

- a value from outside the field — `seeded(value)`;
- the field's own text coming back — `echoed(value)`.

The rule is checked on the *declarations* with the comments stripped, so a
KDoc that mentions `.text` is not a read and a comment that mentions a seed is
not a seed.

The rule has a positive control in both directions: a synthetic seed that
carries the field's own text has to be reported, and a synthetic seed of a
value from outside the field has to be left alone. A rule that has never caught
anything may be broken rather than clean, and a rule that reports everything
says nothing.

## Rationale

The alternative is to make the provenance of every value explicit in the type
— a new wrapper for "a value that came from outside the field" — and to have
every owner wrap its seeds. That is a change to every call site of every
field owner, for a lie that has one visible shape and no known instance.

The rule chosen here is narrower and it is mechanical: it catches the shape
the lie takes when it is written down, it costs nothing at the call sites that
are honest, and it fails loudly the first time somebody writes a seed from a
field's own text.

It also leaves the harder case to the type, which is where the type is
strongest: a value the field produced *earlier* and that the owner now holds
as a plain `String` is invisible to a text scan, and the way to say it is
`echoed`.

## Consequences

- The rule passes on the tree today: no seed in `commonMain` carries a
  `.text` read. It is a guard, not a fix — and it is the guard that makes the
  fix unnecessary at the sites that are honest.
- The rule cannot see a seed whose value *was* a field's text one keystroke
  ago and is now held elsewhere. That case needs `echoed`, and the reviewer's
  eye on the call site — the type has already made it possible.
- The scan reads `commonMain`. A seed written in a platform source set would
  not be seen; the field contract lives in `commonMain`, and the platform
  source sets hold the wiring, but the gap is real and named.
- `Draft.seeded(...)` — the saved agenda's own type, with a different
  meaning — is read by the same detector, because the shape of the lie is the
  same wherever it is written.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/FieldOwnedTextSeedContractTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/FieldOwnedText.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/TextFieldStateBridge.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldRewriteContractTest.kt`
