---
title: "The status of a proposal is what its changes say"
date: 2026-10-24
status: accepted
tags: [proposals, domain, presentation, lifecycle]
---

# The status of a proposal is what its changes say

## Context

A proposal had a status of its own: a column in the row, a field in the model,
a value the surfaces could ask about.

```kotlin
data class AiProposal(
    …,
    val status: ProposalStatus,
    …
)
```

The column was written twice — once when the proposal was saved, once when a
decision was made — and the second write could fail:

```kotlin
override suspend fun refreshStatus(proposalId: ProposalId, userId: UserId): Result<Unit> =
    runCatchingCancellable { … }
        .also { result ->
            val error = result.exceptionOrNull() ?: return@also
            log.e(error) { "proposal …: items were decided but the aggregate status was not recomputed" }
            crashReporter.report(error, "proposals.refresh_status_failed")
        }
```

It was reported rather than thrown, on purpose — the items were decided
correctly, only the summary row was stale. But a stale summary row is a row
that says one thing about a proposal whose changes say another.

And the surfaces believed the row. The review screen asked for the proposals
whose status was `Pending`, and then filtered the answers by the changes:

```kotlin
proposals.watchProposalsByStatus(ProposalStatus.Pending)
    .map { list -> list.filter { proposal -> proposal.pendingItems.isNotEmpty() } }
```

Two questions, two sources. The row's answer decided *which* proposals arrived;
the changes' answer decided which of them were shown. When they disagreed, the
row won the first question and the changes won the second — so a proposal whose
row said `Resolved` never arrived, and a proposal whose row said `Pending`
arrived and was shown, whatever its changes said.

The same status was named in four surfaces: the review screen asked for it, the
note editor built a `Pending` proposal, the task hub built a `Pending`
proposal, and the task detail's preview drew one. Each was a place where the
lifecycle of a proposal was a matter of local opinion — and the gate that
forbids a surface from naming an item's status said nothing about the
proposal's own.

## Idea

A proposal's status is not a fact about the proposal. It is a fact about its
changes. So let the changes say it, and let the surfaces ask the proposal
instead of the row.

## Decision

**The status is derived.**

```kotlin
val status: ProposalStatus get() = ProposalStatusReducer.reduce(items.map { it.status })
```

The reducer moved into the model, next to what it reads: it is a fold over the
model's own values, and the proposal derives its own status from its own
changes.

**The column stays, and it is a cache.** The row still carries the aggregate
status, because the schema has it and queries may use it — but nothing reads
it into the domain any more. The domain derives the truth; the column records
it.

**The port asks the domain's question.**

```kotlin
fun watchWaitingProposals(): Flow<List<AiProposal>>
```

The repository watches the proposals of the user and keeps the ones the domain
says are waiting. A caller that wanted "the proposals whose status is Pending"
was asking the row — and the row's copy of the status is a cache that can
disagree with the changes it summarizes.

**The same question is asked in SQL, so the cost stays flat.** The rule the
domain applies — a change that has not been decided keeps the proposal waiting —
is written into the query, over the *changes* and not over the row's column:

```sql
SELECT p.* FROM ai_proposal p
WHERE p.user_id = :userId
  AND EXISTS (
    SELECT 1 FROM ai_proposal_item i
    WHERE i.proposal_id = p.id
      AND i.status NOT IN ('Confirmed','Rejected','Retracted')
  )
```

Without it, the review screen would read every proposal the user ever made —
and read the changes of each one — on every decision. The screen that answers
"what is waiting for me?" would get slower the more the user used the AI, and
the lag would grow silently. The SQL asks the same question the domain does, so
the rows that have to be read are the ones that are waiting.

**The surfaces ask the proposal.**

```kotlin
val isWaiting: Boolean get() = pendingItems.isNotEmpty()
```

The note editor and the task hub watch the proposals of their target and keep
the ones that are waiting. They no longer name a status, and they no longer
filter by a list they have to know the meaning of.

**The gate says so.**

`ProposalLifecycleStaysInTheDomainTest` already forbade a surface from naming
`ProposalItemStatus`. It now forbids `ProposalStatus` as well, and its positive
test asserts that the domain really answers: `AiProposal` must offer
`isWaiting`, and must derive its `status` — so the rule cannot pass for the
wrong reason.

## Rationale

A summary that can disagree with the facts it summarizes is a second source of
truth, and the second source is the one that is asked first. The row's status
is written on a best-effort path — the recomputation is reported rather than
thrown — so the row is *expected* to be stale sometimes, and the review screen
was built on the assumption that it is not.

Deriving it removes the possibility instead of the symptom. There is no second
copy to be stale, no recomputation to fail, and no question about whether the
two agree.

Asking the proposal rather than the row also moves the question to where the
answer is. "Is there anything here for the user?" is a question about the
proposal's changes, and the proposal is what holds them. A surface that
filters by `pendingItems.isNotEmpty()` is asking the right question in the
wrong place — it knows what "pending" means, and it has to be kept in step with
the domain by hand.

## Consequences

- **The review screen lists by the changes.** A proposal whose changes are all
  decided no longer waits for a recomputation to catch up.
- **The cost of the screen stays flat.** The waiting rows are selected by the
  same rule the domain applies, so the screen does not read — and hydrate — every
  proposal the user ever made.
- **The row's column is a cache.** It is still written — the schema has it and
  queries may use it — and it is never read into the domain. The fake that
  stands for the database writes it, exactly as the query does; the fake that
  stands for the repository holds the domain's own objects, which have no status
  field at all — so a test cannot make the column lie, because the column is not
  there to lie with.
- **The written format did not change.** The column carries the same four
  values, so there is no migration.
- **Two port methods are gone.** `watchProposalsByStatus` is replaced by the
  question the domain answers; `watchProposalsByTargetKind` had no caller at
  all — it was a way to ask the row about a status and a target kind, and both
  of those are the domain's business now.
- **The reducer moved** from `domain/logic` to `domain/model`: it is a fold over
  the model's values, and the model derives its own status from it.
- **A surface that names a status now fails the build.** The gate is the same
  one that already forbade an item's status, extended by one case — and the
  positive test says what the domain must offer in exchange.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/AiProposal.kt`
  — the derived status and the question the surfaces ask.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalStatusReducer.kt`
  — the fold, beside what it reads.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/port/ProposalRepository.kt`
  — the question the port asks.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ProposalLifecycleStaysInTheDomainTest.kt`
  — the gate, and the positive test.
- `docs/decisions/2026-10-23-the-lifecycle-belongs-to-the-domain.md`
  — the same principle for the item's status.
