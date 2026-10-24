---
title: "A status this build does not know is not a waiting one"
date: 2026-10-24
status: accepted
tags: [proposals, domain, persistence, codec]
---

# A status this build does not know is not a waiting one

## Context

An item's status is the one thing that decides whether a change may still be
applied. The apply use case asks exactly one question about it:

```kotlin
check(stored.status == ProposalItemStatus.Pending) {
    "Proposal item $itemId is already ${stored.status.name}"
}
```

The row's status was read with a reader that filled the gap:

```kotlin
status = enumOrDefault(status, ProposalItemStatus.Pending),
```

`enumOrDefault` parses the stored name and falls back to `Pending` when the
name is not one it knows. A row written by a build that knew a status this one
does not was therefore read as **waiting** — and `Pending` is the one status
that says "this change can still be applied". A change that had already been
applied read as a change waiting to be applied, and applying it again wrote the
same thing twice.

The same status was read in two ways in two places. The mapper filled the gap;
the repository that recomputes a proposal's aggregate status did not:

```kotlin
val derived = ProposalStatusReducer.reduce(items.map { ProposalItemStatus.valueOf(it.status) })
```

`valueOf` throws on a name it does not know. So a row a newer build wrote made
the recomputation fail — caught, reported, and the summary row left with the
status it had — and the item itself read as waiting. Two readers, two answers,
one of them a crash and the other a double apply.

## Idea

The reader does not fill the gap. A status it cannot name is a status it does
not know, and that is different from every status it does know.

## Decision

**The status is not an enum any more.** It is a sealed hierarchy, because it has
to be able to hold a state this build has no word for:

```kotlin
sealed interface ProposalItemStatus {
    val name: String

    data object Pending : ProposalItemStatus { override val name = "Pending" }
    data object Confirmed : ProposalItemStatus { override val name = "Confirmed" }
    data object Rejected : ProposalItemStatus { override val name = "Rejected" }
    data object Retracted : ProposalItemStatus { override val name = "Retracted" }

    data class Unknown(val raw: String) : ProposalItemStatus {
        override val name: String get() = raw
    }
}
```

**The reader is strict.**

```kotlin
fun read(raw: String): ProposalItemStatus = known.firstOrNull { it.name == raw } ?: Unknown(raw)
```

The four names are the ones the row has always carried, so every row written
before this change reads as what it says it is. `Unknown` keeps the name it was
found with, so a row this build cannot read is written back unchanged.

**An unknown status is neither waiting nor decided.**

```kotlin
val ProposalItemStatus.isPending: Boolean get() = this == ProposalItemStatus.Pending

val ProposalItemStatus.isDecided: Boolean
    get() = this == ProposalItemStatus.Confirmed ||
        this == ProposalItemStatus.Rejected ||
        this == ProposalItemStatus.Retracted
```

`isPending` is exactly `Pending` — the state in which a change may be applied.
`isDecided` is the state in which the user has already answered. An unknown
status is neither: the user never said yes and never said no, so the change is
still theirs to turn down, and it is not applicable.

**The reason is with the change.** The card shows it, and the refusals name it:

```kotlin
val statusFailure: String?
    get() = when (status) {
        is ProposalItemStatus.Unknown ->
            "This change was written with the status «${status.raw}», " +
                "which this version of the app does not know."
        else -> null
    }
```

**The claim expects the status the change was read with.** The compare-and-set
used to be `WHERE status = 'Pending'`, hardcoded. A change whose status this
build cannot read is not waiting — and the user still has to be able to refuse
it, because a refusal they cannot make is a card they cannot clear. So the
claim takes the status the row is expected to carry, and a turn-down passes the
status the change was read with:

```kotlin
val claimed = proposals.claim(
    itemId,
    ProposalItemStatus.Rejected,
    stored.status,          // the status the row is expected to carry
    DecidedActor.User,
    reason?.trim()?.takeIf { it.isNotEmpty() },
    userId,
)
```

The turn-down of a change that *was* decided is still refused — that is what
"decided" means.

**The aggregate keeps the proposal on the card.** A change whose status is not
read has not been decided, so the proposal that holds it is still waiting —
conservatively, the way the reducer has always treated anything that is not
fully resolved.

## Rationale

The reader that fills the gap is the reader that invents. `Pending` is not a
neutral fallback: it is the answer "this change can still be applied", so
falling back to it turns "I do not know what this is" into "apply it again".

The two readers were the loud part of the bug. One of them crashed and one of
them invented — and both were reading the same column, in the same package, in
the same feature. A rule that says "read it strictly, and say what you could
not read" removes the choice entirely: there is one reader, and it has one
answer for a name it does not know.

Making the status a hierarchy rather than an enum is what lets the reader have
that answer at all. An enum cannot hold a state it has no constant for, and an
`Unknown` constant without the name would say "something went wrong" without
saying what — the same mistake the codec made once and had to be taught twice.

## Consequences

- **A change this build cannot place is not applied.** It was read as waiting
  before, and applying it wrote the change a second time.
- **It can still be turned down.** The user never said yes to it, so it is
  theirs to refuse — and the compare-and-set was taught to expect the status
  the change was read with, not only `Pending`. Before this, the tap did
  nothing at all and said nothing either.
- **The reason is on the card.** The user sees why the change cannot be applied,
  next to the change, and can still turn it down.
- **The row can be written back unchanged.** `Unknown` keeps the name it was
  found with.
- **The compiler now asks about the new case.** Every `when` over the status has
  to handle `Unknown`, so adding a state later breaks the build where it
  matters instead of silently reading as something else.
- **The stored values did not change.** The four names are the four the rows
  already carry, so there is no migration and an old row reads as itself.
- **One reader, not two.** The mapper and the repository read the same way, and
  `valueOf` — which threw — is gone.
- **The source is still read leniently.** `ProposalSource` falls back to
  `Detail`, because the source gates nothing: it says which surface produced the
  proposal, and a wrong one mis-attributes the proposal in the feedback loop
  without changing what happens to it. The statuses are read strictly because a
  status decides whether a change may be applied.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItem.kt`
  — the status, its reading, and the two predicates.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalMapper.kt`
  — the one reader.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ApplyProposalItemUseCase.kt`
  — the question the status is asked.
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/proposals/ProposalItemStatusTest.kt`
  — the reading, and the two predicates.
- `docs/decisions/2026-10-23-the-format-is-told-by-its-shape.md`
  — the same rule for the kind: a blob this build cannot read is not read as
  something else.
