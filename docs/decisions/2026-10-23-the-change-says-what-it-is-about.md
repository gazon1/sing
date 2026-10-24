---
title: "The change says what it is about"
date: 2026-10-23
status: accepted
tags: [proposals, domain, dispatch, routing]
---

# The change says what it is about

## Context

The proposal vocabulary is declared in one place — `ProposalItemKind` — and
everywhere else the app asked the change what it was, in a list of questions:

```kotlin
private val ProposalItemKind.isCreateVariant: Boolean
    get() = this is ProposalItemKind.CreateTask ||
        this is ProposalItemKind.CreateProject || ...

private val ProposalItemKind.isNoteBound: Boolean
    get() = this is ProposalItemKind.SetNoteField || ...
```

Five predicates, each re-enumerating the variants, in a use case — a place that
had to be kept in step with the model by hand. And the routing they drove had a
fallback:

```kotlin
else -> {
    // Fetch task and resolve tag ids before calling the planner.
    planner.planTaskItem(item, task, resolvedTagIds)
}
```

A kind that answered none of the questions fell through to the branch that
assumed a task. Nothing said so at compile time: the predicates are `is`
checks, not an exhaustive `when`, and the planner's own `when` ended in
`else -> error("Unexpected task-bound kind: $kind")`. So a kind added to the
model compiled, and then failed at runtime — with a message about a kind that
did not exist.

The vocabulary grew from fifteen kinds to nineteen while this was being worked
on, and the plan was to keep growing it.

## Idea

The change knows what it is about. Let it say so, and let the compiler check
that the routing agrees.

## Decision

**Every kind declares its target.**

```kotlin
sealed interface ProposalTarget {
    data class Creation(val kind: CreationBoundKind) : ProposalTarget
    data class OnTask(val kind: TaskBoundKind) : ProposalTarget
    data class OnNote(val kind: NoteBoundKind) : ProposalTarget
    data class OnProject(val kind: ProjectBoundKind) : ProposalTarget
    data class OnTag(val kind: TagBoundKind) : ProposalTarget
    data object Unreadable : ProposalTarget
}
```

**The target carries the change with it, and that is what makes it checkable.**
A target that names a group can only be built from a change of that group, so
`ProposalTarget.OnTask(this)` does not compile for a change that is not
task-bound. The group interfaces — `TaskBoundKind`, `NoteBoundKind`,
`ProjectBoundKind`, `TagBoundKind`, `CreationBoundKind` — are sealed, so the
variants that join them are known to the compiler.

**The routing is one `when`, over the target.**

```kotlin
return when (val target = kind.target) {
    ProposalTarget.Unreadable -> error(kind.readFailure ?: "This change cannot be read.")
    is ProposalTarget.Creation -> planner.planCreateItem(item, target.kind, userId)
    is ProposalTarget.OnTask -> { /* fetch the task, plan it */ }
    ...
}
```

**The planner has one function per group, and each switches over its group
exhaustively.** A change that joins the task group and is not handled in
`planTaskItem` does not compile. The `else -> error(...)` at the end of each
planner function is gone: there is nothing left to fall through to.

**The five predicates are gone.** The use case no longer asks a change what it
is; it reads the answer and fetches the row the target names.

**The names a change carries are part of the change.** `tagNames` moved into the
model, so the use case resolves a name into an id without knowing which kind
carries names.

## Rationale

The question "what is this change about?" has one right answer, and the model
is the only place that can give it. Asking it in five predicates in another
file means two declarations of the same vocabulary, and the second one is the
one that gets forgotten.

Carrying the change inside the target is what turns "the vocabulary is
declared in one place" into "the vocabulary is checked". Without the payload,
a kind could claim a group it does not belong to and the compiler would have
nothing to say. With it, the claim is a type, and the claim is checked.

The planner's exhaustive `when` is the second half of the same bargain: the
group is the unit of planning, so the group is the unit the compiler checks.

## Consequences

- **A new kind cannot be forgotten.** It must declare a target, and the target
  names the group that must handle it.
- **The planner is five functions, not six.** The separate "delete" planner is
  gone: a deletion is a change about a task, a note, a project or a tag, and it
  says so.
- **The stored shape did not change.** The target is a property of the kind, not
  a field of it, and only constructor fields are serialized.
- **The `Unreadable` target is the one a known kind may still choose.** A kind
  that cannot be applied by this build says so, and carries its own reason.
- **The residual gap is named**: a kind that declares `Unreadable` while it is
  perfectly readable is a kind that says it cannot be applied — a choice, not
  an accident, and the card shows the reason it carries.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItemKind.kt`
  — the groups, the target, the kinds.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ProposalPlanner.kt`
  — one function per group, each exhaustive.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ApplyProposalItemUseCase.kt`
  — the routing.
- `docs/decisions/2026-10-23-the-lifecycle-belongs-to-the-domain.md`
  — the same principle, applied to the status.
