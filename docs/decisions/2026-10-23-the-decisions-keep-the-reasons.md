---
title: "The decisions keep the reasons"
date: 2026-10-23
status: accepted
tags: [proposals, presentation, state]
---

# The decisions keep the reasons

## Context

Three surfaces show proposal changes: the review screen, the note editor and
the task detail. Each of them kept the reasons a change failed in its own way.

The review screen and the note editor wrote the reasons into their own state —
and their state is rebuilt from the proposals the surface was given, so a
reason that lived only there was lost on every rebuild. The task detail kept
the reasons in a flow of its own, because its state is assembled from nine
combined inputs and a reason kept inside it was lost on every rebuild too.

The previous round gave the three surfaces one store — `ProposalFailureStore` —
and the surfaces mirrored it into their state. The mirroring was still three
times over, and the store was still handed *in* by each surface, so a surface
that forgot to hand one in would have had no reasons to show.

## Idea

The reasons belong to the decisions. The decisions are the one thing all three
surfaces share, so the decisions keep the reasons and the surfaces show them.

## Decision

**`ProposalDecisions` owns the store.**

```kotlin
private val failureStore = ProposalFailureStore()

val failures: StateFlow<Map<ProposalItemId, String>> = failureStore.state
```

The store is not a constructor parameter any more. A surface builds the
decisions and reads `decisions.failures`; it does not own the reasons and it
cannot forget to.

**The surfaces mirror the flow they are given.** The mirroring stays — a
surface's state is what the surface draws from, and the reasons belong next to
the change they belong to — but there is one flow to mirror now, and it is the
same one in all three places.

**The rule about when a reason is forgotten stays in the decisions.** The
reason of a change that worked is cleared by the decision that succeeded, and
not by the surface that drew it.

## Rationale

A surface is a place to *show* things. The reasons are not the surface's
property: the same change failed the same way, whatever screen the user was
looking at when it failed. Three stores for one fact is three places to forget.

Handing the store in was the wrong direction of ownership: the caller had to
know that the reasons live somewhere, and construct that somewhere, and pass
it on. The decisions are what the three surfaces have in common, so the
decisions are where the reasons belong.

The mirroring stays because the state a surface draws from is assembled
differently in each of the three — one of them is assembled from nine combined
inputs — and a reason that is not in that state cannot be drawn next to the
change it belongs to.

## Consequences

- **One store, one flow, three subscriptions.** The mirroring is the same three
  lines in three places, and it mirrors the same thing.
- **A surface cannot show a reason it forgot to collect.** There is nothing to
  collect: the decisions have them.
- **The decisions' test reads the reasons from the decisions.** The test used to
  keep a store of its own and hand it in; now it asks the decisions it built.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalDecisions.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalFailureStore.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalsViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteProposalsViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskDetailCoordinator.kt`
