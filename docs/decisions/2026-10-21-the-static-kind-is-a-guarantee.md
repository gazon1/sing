---
title: "The static kind is a guarantee: the owner's value names its writes"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, genui]
---

# The static kind is a guarantee: the owner's value names its writes

## Context

The rewrite contract has two kinds of source. An **owned** source (`OwnedText`) says the
upstream owns the value and may replace it with anything — and the source carries the count
of the replacements, so the field can tell a replacement from an echo of its own last push.
A **static** source (`StaticText`) says the upstream replaces the value only with one the
field never produced, so the echo guard applies as it is and there is no count to read.

The owned kind is a type, and the count moved into it: an owner cannot replace a value and
leave the count where it was, because the count travels with the value and moves only
through `Revisioned.replacedBy`.

The static kind is still a **claim**, and it is a claim about the *upstream's writes*:

> the upstream replaces the value only with one the field never produced

Nothing checks it, and nothing could: the upstream's writes happen wherever they happen. The
owner holds a bare `String` — `val query: String`, `val input: String`, `val name: String` —
and writes to it with `state.copy(query = …)`. The write says nothing about what it was: a
value the field produced, a value the upstream loaded, the field's own text coming back, an
emptying — all of them are the same shape at the call site, and the reader of the code
cannot tell them apart.

And the failure is not a crash. A static upstream that replaces the value with something
the field produced has its replacement **dropped** by the echo guard, and the field keeps
the text the user typed. The user sees their own text; the server's value is gone; nothing
fails.

## Idea

The promise belongs in the **owner's type**, not in a sentence next to the source.

A static upstream's writes are of three kinds, and they are all the owner ever does:

- the **field's own text** comes back — the field already shows it;
- the upstream **seeds** a value — it came from outside the field, so the field never
  produced it;
- the upstream **clears** the field.

If the owner's value is a type whose only operations are those three, then a write to it is
always named, and there is no fourth kind of write to make.

## Decision

**A static owner holds a `FieldOwnedText`, and the value's writes are named.**

- **`FieldOwnedText` lives in `core.ui.textfields`**, next to the source types. It has a
  **private constructor**: the only ways to obtain a value are `seeded(value)` — the
  upstream loaded it, `echoed(fieldText)` — the field's own text, `cleared()` — the
  upstream emptied it, and `empty()`.
- **Every static owner holds one.** The search query, the notes' and the projects' search
  query, the chat input, the saved agenda's name, the annotation draft, the project
  editor's name and description, the AI provider settings, the create form's title and
  description: all of them hold a `FieldOwnedText` instead of a `String`.
- **The source is built from the typed value**: `StaticText(value.text)`. The source type
  does not change — the static kind still means "the upstream's writes are echoes, seeds
  and clears" — but the promise is now in the owner's type.
- **The rewrite contract checks it.** A static row's owner must declare a `FieldOwnedText`.
  A static owner that went back to a bare `String` fails the check, and the check is
  structural: it reads the owner's declarations, not its text.

## Rationale

- **The claim becomes a property of the type.** The static kind said something about the
  upstream's behaviour and could not be checked. Now it says something about the *shape of
  the owner's writes*, and the compiler checks it: a value that is a `FieldOwnedText` can
  only have been produced by one of the three named operations.
- **A mis-named write is visible; an unnamed write is not.** A call site that says
  `seeded(…)` says what it is doing, and a reader can see that a seed carries a value the
  field did not produce. Today the same call site says `query = …` and the reader has to
  guess. The type cannot forbid a seed that carries a field-produced value — nothing can,
  because the caller knows something the type does not — but it makes the caller's claim
  explicit, which is the difference between a promise and an accident.
- **One rule for the whole app.** Every static owner is the same shape, so the contract
  test can say "a static owner holds a `FieldOwnedText`" and mean it everywhere. Before,
  the promise was per owner and per call site.
- **It is the same trick as the count.** The owned kind moved the count into the type; the
  static kind moves the promise into the type. In both cases the contract stops being
  something a reviewer has to hold in their head.

## Consequences

- The static owners' states hold a `FieldOwnedText`; the places that read the value as a
  string read `.text`. The values and the behaviour are unchanged.
- The owner's *write* path is where the naming happens: the field's write is an `echoed`,
  a load is a `seeded`, an emptying is a `cleared`. A write that is none of those — a
  value the upstream made up — has no name and no way to be written.
- The `StaticText` source type is unchanged, so the bridge and the field do not know any of
  this. The guarantee is entirely on the owner's side, which is where the promise was
  always kept.
- **The AI provider settings carry the write through their intent.** Their value lives in a
  persisted store, so the store hands the field's own text back after the user typed it, and
  the store cannot tell that text from a value it loaded — only the screen can. So
  `SettingsIntent.Ai.UpdateBaseUrl` carries a `FieldOwnedText` rather than a string: the
  screen names the write where the provenance is known (the field's own text is an `echoed`,
  the provider's default is a `seeded`), and the store remembers the write it made so that
  the value it hands back keeps its kind.
- The rewrite contract test has one more check, and it is the first one that can say
  something about the *static* kind at all.
- The GenUI surface is not one of these owners: its upstream is a server, and the count of
  the server's writes is not a value the field writes. It keeps the owned kind, and the
  count it carries is a plain number the controller increments.

## Links

- `2026-10-21-the-count-travels-with-the-value` — the owned kind's count moved into the
  type; this decision moves the static kind's promise into the type.
- `2026-10-20-the-rewrite-authority-is-a-type` — the decision that made the source the
  authority in the first place.
- `2026-10-21-the-genui-context-hands-out-the-source` — the surface's source, and why it
  is not a static one.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/FieldOwnedText.kt` —
  the type.
