---
title: "What the compiler can take over, and what it cannot"
date: 2026-10-23
status: accepted
tags: [architecture, gates]
---

# What the compiler can take over, and what it cannot

## Context

L4-11 showed a move worth repeating: an invariant that is written down twice —
once in the code, once in a Python gate — is written down once, and the
*compiler* takes the check. The examples that followed it are all of one kind:

- the rewrite authority is a type (`2026-10-20-the-rewrite-authority-is-a-type.md`);
- the count travels with the value (`2026-10-21-the-count-travels-with-the-value.md`);
- the field's shape is derived, not read (`2026-10-21-the-field-shape-is-derived-not-read.md`);
- the static kind is a guarantee (`2026-10-21-the-static-kind-is-a-guarantee.md`).

The next step was to find the *remaining* pairs and move them the same way. The
examples in the request were "every screen has a title" and "the intent covers
all the states" — illustrations, not a specification of where the pairs are.

So the pairs were looked for: every Python gate in the registry (48 blocking,
plus the advisory ones), read against the Kotlin arch tests, the custom detekt
rules, and the type declarations, looking for the shape L4-11 had: the same
fact, checked in two places, where one of the two places is a type.

## What was found

**There is no pair left of that shape, and the reason is a taxonomy.**

Every invariant the gate suite holds is one of four kinds, and only the first
is a thing the type system can carry:

| Kind | Example | Can a type carry it? |
|---|---|---|
| **A value** | "the count travels with the value it counts" | Yes — that is what a type is for |
| **A syntax** | "no file outside the link grammar's home writes `task://`" | No — a string literal is not a value the compiler checks |
| **A reachability** | "no screen composable has no call site" | No — reachability is not a property of a declaration |
| **A history** | "the review verdict for this change exists" | No — the past is not in the type system |

The first kind is where L4-11 lives, and the repository has been through it:
the rewrite contract, the owned/static distinction, the count, the field shape
— those are types now, and the gates that used to re-read them are gone.

What is left in the gate suite is the other three kinds, and each one is
there because the compiler genuinely has no opinion:

- **The link grammar** (`check-link-grammar.py`) says it plainly: "the
  compiler has no opinion about a string literal, and the writer does not know
  there is a home for it". A Kotlin type cannot forbid `"task://"` — only a
  convention and a gate can.
- **The suppression intent** (`check-suppression-intent.py`) wants a
  *reason* next to a `@file:Suppress`. The language's annotation has no
  parameter for one, and detekt reads the built-in annotation, so a custom
  annotation with a required `reason` would not silence the rule.
- **The unwired surfaces** (`find-unwired-surfaces.py`) wants code that
  nothing calls to not exist. Reachability is decided by the whole program,
  not by a declaration's type.
- **The DI fixture sync** (`check-di-fixture-sync.py`) compares a definition's
  parameter types with the values a fixture hands it — and says why it is a
  text check and not a runtime check: the runtime check exists and is good
  (`DomainGraphResolutionTest`), it just cannot fail *early*. The pair is
  deliberate, and the two halves fail at different times on purpose.

The pairs that *look* like duplicates are, on reading, complements: the
selectable-text scan guesses what prose is, the registry test compares two
exact facts; the platform-ports gate checks the document, the Kotlin test
checks that every seam is classified and still live. Each says so in its own
docstring, and each was written that way on purpose.

## Decision

**No gate is removed, because none of the remaining ones is a duplicate.**

The move is recorded here so the next session does not repeat the survey, and
so the examples in the request — "every screen has a title", "the intent covers
all the states" — are answered rather than left hanging: neither exists as a
fact today. A screen's title is a string in a composable; a state's coverage is
a `when` over a sealed type, and the compiler already refuses a `when` that
misses a branch. Encoding them would be a *new* invariant, not a moved one,
and a new invariant is a decision about the app rather than about the gate
suite.

The next type-shaped step is already named, in the ADR that says the static
kind is still a claim: `2026-10-21-the-static-kind-is-a-guarantee.md`. That one
is a Kotlin refactor of the text-field contract — the owner's writes become
typed — and it is worth doing on its own terms.

## Rationale

A survey that ends in "nothing to remove" is only worth writing down if the
reason is a fact about the code rather than about the mood of the session. The
taxonomy is that fact: the type system carries values, and the gate suite's
remaining invariants are about syntax, reachability and history. Each of the
four kinds was checked against the registry, and the three that cannot move
were each read to find out *why* they cannot — and each says so itself, in
words a reader can check.

## Consequences

- The registry keeps its gates, and none of them is a duplicate of a type.
- The examples in the request are answered: they are new invariants, not moved
  ones, and they are left to a decision about the app.
- The survey is not repeated. A gate whose docstring says "the compiler has no
  opinion about this" is not a candidate, and this ADR is where that is
  recorded.

## Links

- `docs/decisions/2026-10-21-the-static-kind-is-a-guarantee.md` — the next
  type-shaped step.
- `docs/decisions/2026-10-20-the-rewrite-authority-is-a-type.md` — the shape
  this survey looked for.
- `scripts/src/sgate/commands/_registry.py` — the registry the survey covered.
