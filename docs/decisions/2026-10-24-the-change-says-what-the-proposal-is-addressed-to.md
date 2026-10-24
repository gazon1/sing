---
title: "The change says what the proposal is addressed to"
date: 2026-10-24
status: accepted
tags: [proposals, domain, dispatch, routing]
---

# The change says what the proposal is addressed to

## Context

A proposal row is addressed by two things: the kind of target it is about and the
id of that target. The id was always the caller's to state. The kind was the
caller's to state too — and it was stated a second time, beside the change the
proposal was about:

```kotlin
fun forTarget(
    kind: ProposalItemKind,
    targetKind: String,          // ← the second declaration
    targetId: String,
    ...
)
```

The builder's own comment admitted the risk:

> The specialised forms above say the target kind for the two common cases,
> because a caller that gets the kind wrong writes a proposal the confirmation
> screen will not find.

Thirteen places wrote it by hand: `"TASK"`, `"NOTE"`, `"PROJECT"`, `"TAG"` — ten
in the AI tools, three in the surfaces that build a proposal directly. Nothing
checked that the string matched the change next to it. A caller that wrote
`"TASK"` beside a note change wrote a proposal addressed to a task, and the
confirmation screen — which looks the proposal up by its address — did not show
it. The user saw a change that had been proposed and never appeared.

The kinds were worse than the strings, because they were not even one fact each.
`ProposalTarget.Creation(kind)` said only *something* comes into being. A
`CreateTask`, a `CreateNote`, a `CreateProject` and a `CreateTag` were all
`Creation`, so the target could not say which of them it was — and the caller
filled the gap by hand, exactly as above.

## Idea

The change already says what it is about. Let the proposal be addressed by that,
and let the compiler check it.

## Decision

**There is a type for the kind of target.**

```kotlin
sealed interface ProposalTargetKind {
    val storageValue: String

    data object Task : ProposalTargetKind { override val storageValue = "TASK" }
    data object Note : ProposalTargetKind { override val storageValue = "NOTE" }
    data object Project : ProposalTargetKind { override val storageValue = "PROJECT" }
    data object Tag : ProposalTargetKind { override val storageValue = "TAG" }

    data class Unknown(val raw: String) : ProposalTargetKind {
        override val storageValue: String get() = raw
    }
}
```

The storage values are the ones the row already carries, so every row written
before this type existed is read as what it says it is. A value this build does
not know is `Unknown` — a proposal about something this build cannot name is not
misaddressed to a task, and is not a crash either. It keeps the name it was
found with, the way the status of a change does: a row this build cannot read
must not become a row this build rewrites into something else.

**Every target declares the kind of target it is.**

```kotlin
sealed interface ProposalTarget {
    val targetKind: ProposalTargetKind

    data class Creation(val kind: CreationBoundKind) : ProposalTarget {
        override val targetKind: ProposalTargetKind get() = kind.targetKind
    }
    data class OnTask(val kind: TaskBoundKind) : ProposalTarget {
        override val targetKind: ProposalTargetKind get() = ProposalTargetKind.Task
    }
    // …
}
```

**A creation says what it brings into being.** `CreationBoundKind` gained the
declaration, so a creation cannot be built without naming the thing it creates:

```kotlin
sealed interface CreationBoundKind {
    val targetKind: ProposalTargetKind
}

data class CreateTask(val task: ProposedTask) : ProposalItemKind, CreationBoundKind {
    override val targetKind: ProposalTargetKind = ProposalTargetKind.Task
}
```

**The builder reads it, and the caller cannot write it.** `forTarget` and
`multiForTarget` no longer take a kind of target; they take the change and read
the answer. The specialised forms check it:

```kotlin
fun forTask(kind: ProposalItemKind, taskId: TaskId, ...): Pair<AiProposal, List<ProposalItem>> {
    requireTarget(kind, ProposalTargetKind.Task)
    return forTarget(kind = kind, targetId = taskId.value, ...)
}
```

A change about a note offered a task id now fails loudly, instead of writing a
proposal addressed to a task that holds a note's change.

**A proposal holds changes about one thing.** `ProposalTargetKind.of(changes)`
reads the one kind and refuses a batch that mixes kinds — a proposal about two
things is not a proposal about anything, and it is not addressed to whichever
change happened to come first.

## Rationale

"What is this change about?" has one right answer, and the change is the only
place that can give it. Writing it down twice — once as the change, once as a
string beside it — means the second copy is the one that gets forgotten, and
nothing says so until a user cannot find a change that was proposed.

Deriving it also removes a class of bug that a type alone would not: a caller
that got the kind wrong could still write `forTask(kind = DeleteNote(...))`,
because the builder hardcoded `TASK`. Now the id and the change are checked
against each other, and the mismatch is an error at the point where it is made.

The `Unknown` member is the same decision the codec made for a kind this build
cannot read: the reader does not fill the gap with the value it knows.

## Consequences

- **Thirteen hand-written strings are gone.** The kind of target is derived from
  the change in every one of them.
- **The values did not change.** Every one of the thirteen wrote the same string
  the derivation now produces, so no proposal is addressed differently — and the
  stored format did not change, so there is no migration.
- **A new kind cannot be misaddressed.** It declares what it is about, and the
  proposal follows.
- **A creation says what it creates.** `Creation` alone never said, so a caller
  filled the gap; now the creation answers.
- **A batch that mixes kinds is refused** rather than addressed to the wrong
  thing. The tools build one kind per batch today, so nothing changes for them.
- **A proposal about something this build cannot name** is `Unknown` — it stays
  where the review screen can reach it, and it claims to be about nothing in
  particular.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalTargetKind.kt`
  — the type, and the reading of a stored one.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItemKind.kt`
  — the targets and the declarations.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/logic/ProposalBuilder.kt`
  — the builder that reads the answer and checks the id against it.
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/proposals/ProposalTargetKindTest.kt`
  — the declarations, the derivation, and the two refusals.
- `docs/decisions/2026-10-23-the-change-says-what-it-is-about.md`
  — the same principle one level up: the change declares its group.
