---
date: 2026-10-22
status: accepted
---

# The group of destinations that stands for a record is a type, not a list

## Context

Every destination of the app has a *content key* — the stable string that tells
two screens apart when the navigation state is saved and restored. The keys
live in `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/StableContentKey.kt`.

The destinations that stand for a record — a task, a project, an attachment — or
for a nested graph compute their keys differently from the others: the key
carries the record's id. Those nine destinations were handled by a *second*
`when`, and the membership of the group was written down twice:

* once in the outer `when`, as a list of nine types that delegate to
  `recordContentKey()`;
* once in the inner `when`, as the nine branches that compute the keys.

The inner `when` had an `else` branch — `error("no content key for $this")` —
because the compiler could not check it: its receiver was `AppDestination`, not
the group. So the two lists could drift. A destination that joined the group in
the outer list and not in the inner one compiled, and failed at runtime, on the
first screen that opened it.

The repository carried a gate for exactly this (`scripts/check-stable-content-key.py`,
"every destination has a content key"): it parsed both `when`s and reported a
delegation without a counterpart. It was a gate because the compiler could not
do the job — the invariant was stated twice, in the code and in the gate.

## Idea

State the invariant once, in the type system.

Make the group a *type*: a sealed interface `AppDestination.RecordDestination`,
which the nine destinations implement. Then

* the outer `when` has one branch — `is AppDestination.RecordDestination ->` —
  and stays exhaustive over `AppDestination`, so a new destination does not
  compile until it is classified;
* the inner `when` is a `when` over the sealed `RecordDestination`, so it is
  exhaustive *by the compiler* and needs no `else`.

The group is named in one place, and the compiler enforces both exhaustivenesses.

## Decision

The group becomes a sealed interface, the inner `when` loses its `else`, and
the gate that duplicated the invariant is retired.

The gate goes because the compiler now says everything it said: a destination
without a key does not compile, and a key for a destination that does not exist
does not compile either. A gate that checks what the compiler already refuses
to let through is a second statement of an invariant that has one owner — the
kind of duplication this repository removes elsewhere.

## Rationale

The failure mode was not a wrong key; it was a runtime error on a screen that
somebody opens once. It survived review because the code looked exhaustive —
the outer `when` really did name every destination — and the gate that noticed
it was a Python script reading Kotlin with regexes, which is the weakest kind
of check in the repository.

The Kotlin test that walks every destination (`NavContentKeyTest`) stays: it
proves at runtime that every destination really has a key, which is a different
statement from "the code compiles".

## Consequences

* A destination that joins the group does not compile until it has a key — the
  check happens while the code is written, not while it is used.
* The runtime `error("no content key for $this")` is gone: there is no path
  that reaches it, because there is no branch that can fall through.
* The gate count drops by one (45 → 44). The rule it enforced now lives in the
  compiler, and the Kotlin test keeps the runtime proof.

## Links

* `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt`
* `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/StableContentKey.kt`
* `shared/src/commonTest/kotlin/com/singularity/todo/feature/nav/NavContentKeyTest.kt`
