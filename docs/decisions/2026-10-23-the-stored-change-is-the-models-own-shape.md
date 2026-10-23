---
title: "The stored change is the model's own shape"
date: 2026-10-23
status: accepted
tags: [proposals, codec, serialization, migration]
---

# The stored change is the model's own shape

## Context

The `kind_json` column holds the shape of a proposal's change. It was written by
hand — a builder per group of kinds — and read back by a reader that switched on
the `"type"` string:

```kotlin
private fun ProposalItemKind.toJsonObject(): JsonObject = buildJsonObject {
    put("type", this@toJsonObject::class.simpleName ?: "Unknown")
    writeTaskPayload(this@toJsonObject, this)
    writeEntityPayload(this@toJsonObject, this)
    ...
}
```

The flat shape packed a payload into text: the list of checklist texts in one
column, separated by Unit Separator; the time entries in another, separated by
Record Separator. The reader unpacked it and read the value through the
domain's own reader — a priority is the string "High" in the column, and
`TaskValue.read` says what that is.

That is a lot of hand-written agreement between a writer and a reader, and the
agreement was not checked by anything. A field that the model renamed was a
field the flat writer kept writing under the old name, or stopped writing — and
a value that came back as something else was a change the user approved as one
thing and got as another.

The sealed format was available all along: the vocabulary is
`@Serializable`, every value is `@Serializable`, and every kind carries a
`@SerialName` that is already the string the flat format used as its
discriminator. The flat format was kept deliberately, and the reason was written
down: it stayed flat and greppable in the database.

## Idea

Let the model's own serializer write the model's own shape, and keep the flat
format readable for as long as a build that writes it can be in the user's
hands.

## Decision

**The writer is the sealed serializer.**

```kotlin
internal fun ProposalItemKind.toJsonString(): String =
    payloadJson.encodeToString(ProposalItemKind.serializer(), this)
```

The discriminator is the `@SerialName` the kinds already carry — the same
strings the flat format used — and the payload is the variant's own fields.

**The reader tries the sealed format first and the flat format second.** A flat
blob cannot be read as a sealed one by accident: every sealed variant carries a
payload the flat format does not have — a nested object for a value, an array
for a list, a nested object for a created thing — and a flat blob has a string
where the sealed one has that structure, so the sealed reader refuses it. The
two shapes that coincide (the deletions and the unknown kind) carry the same
value either way.

**The flat writer is gone.** It was the fragile half: a hand-written writer is
where a renamed field goes wrong. What is left is the flat *reader*, and it is
read-only from now on.

**The removal plan.** The flat reader goes when the oldest build in the user's
hands is one that writes the sealed format — that is, after the release that
ships this change has been out long enough for the previous release to fall out
of support. The removal is a follow-up: delete `decodeFlat` and the packing
helpers, and delete the flat-format test with them. Until then, a row the
previous release wrote is a row the user still has, and it has to read.

**The cost, stated plainly.** A change written by this build and read by a
build that only knows the flat format does not read there: the old build shows
it as a change it cannot read, with the reason — the old build reads a sealed
blob as a flat one, finds no field where the value should be, and says so. The
change is not lost there; it is not applicable there. A proposal is a *pending*
change, so the user on the old build can turn it down or wait for an update.
That is the price of a format the model itself keeps honest, and it is paid
only between two releases.

## Rationale

The flat format was chosen for greppability — a row in the database that a
person can read. That is a real property, and it was the wrong trade: a value
that reads back as a different value is a change nobody decided about, and
greppability is a comfort.

The sealed format answers the question the flat format could not: what does
this blob mean? Not "what does this hand-written reader make of it", but "what
does the model say" — because the model and the serializer are the same
declaration.

The flat reader stays because a row is not only what this build wrote. The
user's other device may be an older build, and the row it wrote is still in the
database. A format change is a migration, and the migration is not complete
when the writer changes — it is complete when the reader that is no longer
needed is gone.

## Consequences

- **The stored shape is the model's shape.** A field that changed in the model
  changed in what is stored, by construction.
- **The flat format is still read.** There is a test for it, and the test goes
  with the reader when the reader goes.
- **The `Unknown` kind's raw blob is written back unchanged.** A row this build
  cannot read must not become a row this build rewrote.
- **The greppability is gone.** A person reading `kind_json` in the database now
  reads a nested object. That was the trade.
- **The follow-up is named**: delete the flat reader when the previous release
  falls out of support.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalKindCodec.kt`
  — `toJsonString`, `decodeKind`, `decodeSealed`, `decodeFlat`.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/proposals/data/ProposalMapperTest.kt`
  — the round trip and the flat-format test.
- `docs/decisions/2026-10-23-a-change-this-build-cannot-read-stays-on-the-card.md`
  — what a blob nobody can read becomes.
