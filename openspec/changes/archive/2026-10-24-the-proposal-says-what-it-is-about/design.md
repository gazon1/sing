# The proposal says what it is about, and reads strictly — Design

## The three facts

A proposal says three things about itself, and each of them was said twice.

| Fact | Said by | Said again by | Who decided |
|---|---|---|---|
| What the change is about | the kind of the change | a string beside it | the caller |
| What state the change is in | the stored status | the reader's fallback | the reader |
| What state the proposal is in | the summary column | the changes | the row |

The design of this change is the same in all three cases: **one place says it,
and the other place is derived from it.**

## 1. The kind of target

```kotlin
sealed interface ProposalTargetKind {
    val storageValue: String

    data object Task : ProposalTargetKind { override val storageValue = "TASK" }
    // …
    data class Unknown(val raw: String) : ProposalTargetKind {
        override val storageValue: String get() = raw
    }
}
```

The storage values are the ones the row already carries. `Unknown` is for a name
this build does not know — the same answer the codec gives for a blob it cannot
read, and it keeps the name it was found with.

The declaration lives on the target, because the target is the thing that knows
what it is about:

```kotlin
sealed interface ProposalTarget {
    val targetKind: ProposalTargetKind
    data class OnTask(val kind: TaskBoundKind) : ProposalTarget { … }
    data class Creation(val kind: CreationBoundKind) : ProposalTarget { … }
    data object Unreadable : ProposalTarget { … }
}
```

`Creation` asks its creation kind, and a creation kind declares what it brings
into being — so `CreateTask` is about a task and `CreateTag` is about a tag,
and neither says only "something is created".

`ProposalTargetKind.of(changes)` reads the one kind of a set of changes and
refuses a set that mixes kinds.

The builder reads the answer and never asks for it:

```kotlin
fun forTarget(kind: ProposalItemKind, targetId: String, …) = …  // no targetKind
fun forTask(kind: ProposalItemKind, taskId: TaskId, …) {
    requireTarget(kind, ProposalTargetKind.Task)
    …
}
```

## 2. The status of a change

The status has to be able to hold a state this build has no word for, so it
stops being an enum:

```kotlin
sealed interface ProposalItemStatus {
    val name: String
    data object Pending : ProposalItemStatus { … }
    data object Confirmed : ProposalItemStatus { … }
    data object Rejected : ProposalItemStatus { … }
    data object Retracted : ProposalItemStatus { … }
    data class Unknown(val raw: String) : ProposalItemStatus { … }
}
```

Two predicates, and they are not the same question:

- `isPending` — exactly `Pending`: the state in which a change may be applied.
- `isDecided` — `Confirmed`, `Rejected` or `Retracted`: the state in which the
  user has answered.

`Unknown` is neither. The change cannot be applied, and the user has not
answered.

The reader is strict, and there is only one:

```kotlin
fun read(raw: String): ProposalItemStatus = known.firstOrNull { it.name == raw } ?: Unknown(raw)
```

The reason travels with the change (`ProposalItem.statusFailure`) and the
refusals name it. The turn-down still works: the compare-and-set expects the
status the change was read with, so a change this build cannot read can still
be refused — a refusal the user cannot make is a card they cannot clear.

## 3. The status of a proposal

```kotlin
val status: ProposalStatus get() = ProposalStatusReducer.reduce(items.map { it.status })
```

The reducer moves from `domain/logic` to `domain/model`: it is a fold over the
model's values, and the model derives its own status from it.

The column stays and is still written. It is a cache — the schema has it and
queries may use it — and it is never read into the domain.

The port asks the domain's question:

```kotlin
fun watchWaitingProposals(): Flow<List<AiProposal>>
```

and the surfaces ask the proposal:

```kotlin
val isWaiting: Boolean get() = pendingItems.isNotEmpty()
```

The same question is asked in SQL — over the changes, not over the row's
column — so the review screen reads the waiting rows and not every proposal the
user ever made.

## What breaks, and what does not

**Nothing in the stored format changes.** The four status names, the four target
kinds, the columns — all as they were. There is no migration and no
compatibility window.

**Nothing the user sees changes**, except where a row was written by a build
that knew more than this one: that change now stays on the card with a reason,
instead of being applied again or never shown at all.

**Thirteen call sites lose a line**, and the two of them that built a proposal
by hand lose the string they typed.

## The gate

`ProposalLifecycleStaysInTheDomainTest` already forbade a surface from naming
an item's status. It now forbids `ProposalStatus` as well, and its positive
test asserts what the domain must offer in exchange: `AiProposal` must derive
its status and must answer whether it is waiting. Without that, the rule could
pass for the wrong reason — a codebase that stopped asking the domain anything
would also stop breaking the rules.
