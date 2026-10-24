---
title: "The identity of a row has a name of its own"
date: 2026-10-23
status: accepted
tags: [proposals, dispatch, ids]
---

# The identity of a row has a name of its own

## Context

The dispatcher writes the rows a proposal asked for, and it takes them back
when a later row fails. To do that it remembers which rows it wrote — and the
identity of a row was kept as a plain `Any`:

```kotlin
private class ProposedRow<out Id>(
    val id: Id,
    ...
)

val takenBack = mutableSetOf<Any?>()
```

The rows are of different kinds: a task, a tag, a note, a project, a checklist
item, a time entry. Their identities are of different types, and the set that
holds them held them as `Any?` — so a missing identity was representable, and
two rows of different kinds shared one namespace.

Nothing was broken by it, and that is the problem: a set of `Any` is a place
where a mistake is invisible. The set is consulted on the take-back path — the
path that runs when a write failed and the change goes back to waiting — so a
wrong answer there is a row that is not taken back and stays behind, to be
written twice on the next attempt.

## Idea

Give the identity a name of its own, so the set can only hold identities.

## Decision

```kotlin
private data class ProposedRowId(val value: Any)
```

The row carries a `ProposedRowId`, and the set of the rows that are already
gone is a `MutableSet<ProposedRowId>`. The wrapper is non-null by
construction, and the identity inside it keeps the type it had — so a task's
identity and a note's identity are not the same thing, even when their text
is the same.

The wrapper is added where the row is built, so the callers keep writing the
identity they have.

## Rationale

The take-back is the path where a wrong answer costs the user a row that is
written twice, and it is the path that runs when something has already gone
wrong. It is not the place for a type that says "anything at all".

The wrapper is not a type-safe id in the full sense — the inner value is still
`Any` — but it is enough for the question the set actually asks: *have I
already taken this row back?* Two rows of different kinds are two rows, and
the set now says so.

## Consequences

- **A missing identity is not representable.** `ProposedRowId` holds a
  non-null value.
- **Two kinds of row cannot pass for one row** in the set of the rows that are
  gone.
- **The generic parameter of the row is gone.** It said what the identity was
  and was erased at every call site anyway (`List<ProposedRow<*>>`).

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ProposalDispatch.kt`
  — `ProposedRow`, `ProposedRowId`, `takeBackAll`.
- `docs/decisions/2026-10-23-the-id-is-known-before-the-row-is-written.md`
  — why the identity is known before the write.
