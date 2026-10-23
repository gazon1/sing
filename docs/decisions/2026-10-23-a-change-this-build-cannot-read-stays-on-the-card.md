---
title: "A change this build cannot read stays on the card"
date: 2026-10-23
status: accepted
tags: [proposals, codec, vocabulary, contract]
---

# A change this build cannot read stays on the card

## Context

The proposal vocabulary is stored as JSON in the `kind_json` column, and a codec
reads it back. The codec decodes on the **name** of the kind — the flat payload
carried `"type": "SetTaskField"` and the reader switched on the string.

A kind the codec was not taught to read decoded to *nothing*:

```kotlin
internal fun decodeKind(raw: String): ProposalItemKind? = runCatching {
    val obj = payloadJson.parseToJsonElement(raw) as JsonObject
    decodeTaskKind(obj) ?: decodeEntityKind(obj) ?: decodeCreationKind(obj) ?: decodeDeletionKind(obj)
}.getOrNull()
```

`null` meant "this row is not a change this build knows", and the mapper
dropped the row:

```kotlin
internal fun ProposalItemEntity.toDomainOrNull(): ProposalItem? {
    val kind = decodeKind(kindJson) ?: return null
    ...
}
```

So a change written by a build that knew a kind this one does not was not shown
at all. The proposal simply lost a change, and the user was never told: there
was nothing on the card to say anything about.

The vocabulary grew from 15 kinds to 19 while this repository was being worked
on, so this was not a hypothetical — it was a matter of when a kind was added
and a device was left behind.

The same shape had already been fixed one level down. A *value* this build
cannot read became `TaskValue.Unreadable(field, raw)`, and the change stayed on
the card with the reason. The *kind* had no such answer — and a value is a
smaller thing than a kind: a kind is the whole change.

The compiler could not help. The `when`s over `ProposalItemKind` in the domain
are exhaustive, and the codec is not a `when` over the type — it is a `when` over
a string.

## Idea

A kind this build cannot read is not a missing kind. It is a change that carries
the reason it cannot be applied.

## Decision

**`ProposalItemKind.Unknown(raw)` joins the vocabulary.** The raw blob is kept
inside it, so the change can be written back unchanged — a row this build cannot
read must not become a row this build *rewrote* into something else.

**The codec always answers with a kind.** `decodeKind` returns
`ProposalItemKind.Unknown(raw)` for a blob it cannot read — a kind it does not
know, or a blob that is not a blob at all. The mapper keeps the row; there is
no `toDomainOrNull` any more.

**The change cannot be applied, and says so.** The planner refuses an unknown
kind before the claim, with the reason the change carries. The card shows the
reason next to the change. The user can still turn the change down — that is
the point: the change is still a change, and the user is the one who decides
about it.

**The unknown kind is part of the round-trip coverage.** The mapper test
compares the samples against the sealed serializer's list of kinds, so a kind
that arrives without a sample fails the coverage test — the new kind is covered
by the same test as every other kind.

## Rationale

The alternative — dropping the row — is a decision made on the user's behalf,
and it is a decision the user cannot undo: the change is gone, and there is
nothing on the card to turn down.

The reason is the reader's to give. The row says "I am a change this build
cannot read"; *why* is the reader's business, and the reader is the only place
that knows what it does not know.

The raw blob is kept so that the change survives a round trip through a build
that does not understand it. A change this build cannot read must not become a
change this build rewrote — the next build that can read it has to find the same
change.

**Why not refuse the row at the boundary?** A row that cannot be read is still a
row: it holds a proposal, a status and a decision the user may have made. The
proposal would have to be assembled without it, and the proposal would be a
different proposal from the one that was written.

## Consequences

- **The vocabulary has one more kind**, and every `when` over it says what to do
  with a change that cannot be read.
- **The mapper keeps every row.** A corrupt row is a change with a reason, not a
  hole in a proposal.
- **A test says the change is kept**, and another says the flat shape is still
  read — the blob of an unknown kind is written back as it was found.
- **The `Unknown` kind has no editable value.** There is nothing to edit: the
  build does not know what the change sets.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItemKind.kt`
  — `Unknown`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalKindCodec.kt`
  — `decodeKind`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalMapper.kt`
  — `toDomain`.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/proposals/data/ProposalMapperTest.kt`
  — the tests.
- `docs/decisions/2026-10-23-a-value-that-does-not-read-keeps-the-change.md`
  — the same answer, one level down.
