---
title: ApplyProposalItemUseCase — Refactor Strategy
date: 2026-10-04
status: draft
deciders: 
deciders: 
---

## Context

`ApplyProposalItemUseCase` currently has **12 public functions** (above the `TooManyFunctions` threshold of 10), covering:

- `confirm`, `reject`, `confirmAll` — decision actions
- `plan`, `planNoteItem`, `planDeleteItem` — routing
- `dispatch` — execution for 12+ variants
- `resolveTagIds`, `resolveTaskField`, `resolveTimeEntries`, `resolveChecklistItems`, `resolveSubtasks` — field resolvers
- private helpers

Every new `ProposalItemKind` variant (e.g. `SetNoteField`, `DeleteNote`, `ExtractActions`, `DeleteTask`, `DeleteProject`, `DeleteTag`) adds 2-3 methods, worsening the problem.

## Decision

**Out-of-scope for Phase A.** The class currently works correctly; the `TooManyFunctions` smell does not cause bugs. The refactor is documented here to be executed when the next variant is added.

## Proposed Refactor

Split into three layers, each focused on one responsibility:

### 1. `ProposalPlanner` — pure transformation, no I/O
```
ProposalItem → ProposalPlan
```
Receives a stored `ProposalItem`, validates the target entity exists, returns a `ProposalPlan`. No Room, no network. Fully testable with fakes.

**Public surface:** `plan(item): ProposalPlan`

### 2. `ProposalDispatch` — executes a validated plan
```
ProposalPlan → Unit (side-effect)
```
Pure execution against repositories. No routing logic.

**Public surface:** `dispatch(plan, userId)`

### 3. `ApplyProposalItemUseCase` — orchestrates claim + plan + dispatch
```
confirm(itemId) = claim → plan → dispatch
reject(itemId)  = claim → persist
```
Thin orchestrator using the above two.

## Alternative Considered: Visitor Pattern

`ProposalItemKind` sealed interface could dispatch via a visitor:

```kotlin
fun ProposalItemKind.resolve(dependences): ProposalItemKind = when(this) {
    is SetTaskField -> resolveField(this)
    is AddTags -> resolveTags(this)
    // ...
}
```

**Rejected:** `ProposalItemKind` already has a `when` dispatcher in `ApplyProposalItemUseCase`. A visitor adds a layer of indirection without solving the fundamental issue (growing variant count). The planner/dispatch split is simpler.

## Consequences

- Planners can be unit-tested in isolation (no `runTest` needed)
- New variants require: new `ProposalPlan` subclass + planner arm + dispatch arm — compiler enforces completeness
- `ApplyProposalItemUseCase` drops from 12 → ~5 functions

## Links

- Phase A retrospective 2026-10-04
