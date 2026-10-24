---
title: "The format is told by its shape"
date: 2026-10-23
status: accepted
tags: [proposals, codec, serialization, forward-compatibility]
---

# The format is told by its shape

## Context

The sealed format was adopted in the previous round: the model's own serializer
writes the model's own shape, and the flat format stays readable for as long as a
build that writes it can be in the user's hands. The reader was written as a
sequence of attempts:

```kotlin
decodeSealed(obj)?.let { return it }
return decodeFlat(obj, raw) ?: ProposalItemKind.Unknown(raw)
```

Two quiet failures lived in those two lines.

**The sealed reader ignored what it did not know.** The one `Json` instance both
formats were read with was built with `ignoreUnknownKeys = true`. So a blob a
newer build wrote — a variant that gained a field — was read by this build with
that field dropped, and the value it produced was a *different, valid* value. A
priority that grew a second field, a title that grew a marker: the old build
filled in the default and showed the user a change nobody proposed. The sealed
format was bought to stop exactly this, and the reader undid it.

**A sealed blob could be read as a flat one.** When the sealed reader refused a
blob — an unknown variant — the flat reader got a chance at it. The flat reader
looks for `"field"` and `"value"` as text; a sealed blob has `"value"` as an
object, so the flat reader found no field, read an empty value, and produced
`TaskValue.Unreadable("unknown", "")` — a change that says "set the unknown to
nothing" about a change that is something else entirely.

The two failures have one cause: the reader did not know which format it was
reading, and guessed by trying.

## Idea

The two formats do not look alike, and the difference is visible in the blob
itself: the flat format packs everything into text — a list into one column
separated by Unit Separator — while the sealed format carries the model's own
shape, with a nested object for a value, an array for a list, a nested object
for a created thing. So the shape says which format it is.

## Decision

**The format is told by the shape, not by trying one reader after the other.**

```kotlin
return if (obj.carriesStructure()) {
    decodeSealed(obj) ?: ProposalItemKind.Unknown(raw)
} else {
    decodeFlat(obj, raw) ?: ProposalItemKind.Unknown(raw)
}
```

A blob that carries a structure is a sealed blob, and only the sealed reader
reads it. A blob that does not is a flat blob (or the one shape both formats
share: the deletions and the unknown kind, which mean the same thing either
way), and only the flat reader reads it.

**The sealed reader is strict.** The `Json` instance the sealed format is read
and written with is the default one: `ignoreUnknownKeys = false`. A key this
build does not know is a key a newer build wrote, and a blob read without it is
a change read as something it is not. So the reader refuses such a blob, and
the refusal is `ProposalItemKind.Unknown` — the change stays on the card with
the reason it cannot be read.

The flat reader is unaffected: it is read by hand, field by field, so it needs
no serializer at all, and a key the old writer did not know is a key the old
reader ignores — which is what the old format always did.

**The reason says which fact it is.** `ProposalItemKind.Unknown` now carries a
reason of its own, and the reader names it: a blob that is not an object at all
is not the same fact as a blob another version wrote. The card shows the
reason, and a change that carries the same sentence for three different facts
carries no fact at all.

**The written format did not change.** The writer is still the model's own
serializer, and `ignoreUnknownKeys` never affected writing — so a row this
build writes looks exactly as it did before this change. No migration, no cost.

## Rationale

The reader has to know what it is reading, and there are two ways to find out:
guess, or look. Guessing was tried first and it failed in a way that is
invisible — a change read as a different change, with no reason attached.

Looking is free here, because the two formats were never confusable: the flat
writer only ever wrote text, and the sealed writer writes the model's shape.
The discriminator falls out of that, and it is a property of the data rather
than of the reader's order of attempts.

Strictness is the same argument as the shape rule. A reader that fills in a
default for a key it does not know is a reader that invents a change. The
format was changed to the model's own shape precisely so that the model would
keep it honest; a lenient reader puts the invention back.

## Consequences

- **A change a newer build wrote is refused, not read as something else.** The
  card shows it with the reason.
- **A sealed blob is never read as a flat one.** The "set the unknown to
  nothing" reading is gone.
- **The flat format still reads.** The old rows still read, and the flat-format
  test still stands.
- **The residual limit is written down**: a field the older build knows *and*
  has a default for, which the newer build dropped, is defaulted by the older
  build. A version envelope in the blob would close that too; it is a
  follow-up, and it is not needed for the failure it would prevent — a field
  the older build has never heard of is refused, and that is the case that
  matters.
- **The flat reader's removal plan is unchanged** and is now a checklist — see
  `docs/decisions/2026-10-23-the-flat-reader-goes-when-the-old-release-goes.md`.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalKindCodec.kt`
  — `decodeKind`, `decodeSealed`, `decodeFlat`, `carriesStructure`.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/proposals/data/ProposalMapperTest.kt`
  — the refusal tests.
- `docs/decisions/2026-10-23-the-stored-change-is-the-models-own-shape.md`
  — the sealed format this makes strict.
- `docs/decisions/2026-10-23-a-change-this-build-cannot-read-stays-on-the-card.md`
  — what a blob nobody can read becomes.
