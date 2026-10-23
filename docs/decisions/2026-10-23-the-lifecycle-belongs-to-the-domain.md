---
title: "The lifecycle belongs to the domain"
date: 2026-10-23
status: accepted
tags: [proposals, presentation, architecture, ui]
---

# The lifecycle belongs to the domain

## Context

Three surfaces show a proposal: the review screen, the note editor and the task
detail. They draw the same card, and they used to answer the same questions
three ways.

**The status.** The card said "Turned down" in a `when` over
`ProposalItemStatus`. The review screen decided what to show with
`items.any { it.status == ProposalItemStatus.Pending }`. The note editor built
its preview item with `status = ProposalItemStatus.Pending`. The task detail
built its preview the same way. So the lifecycle was read in four places, and
the wording was in one of them.

**The wording.** Each surface carried its own `ProposalDecisionStyle` — the
review screen's in its ViewModel, the note editor's in its ViewModel, the task
detail's in its coordinator. A style is a value that three surfaces share, and
the values were in three files.

**The reasons.** A change that could not be applied keeps the reason next to
it. The reasons lived three ways: the review screen and the note editor wrote
them into their own state, and the task detail kept them in a flow of its own
(`MutableProposalFailureStore`). The two state-keeping implementations were
not just two implementations — they lost the reasons: the state of those
surfaces is rebuilt from the proposals the screen was given, and a reason kept
only in that state was gone on the next rebuild.

## Idea

The domain answers what a proposal's state means, and the surfaces print the
answer. The values the surfaces share live together. The reasons live in one
place.

## Decision

**The domain answers three questions.**

| The question | The answer |
|---|---|
| What does a decided change read as? | `ProposalItem.outcomeLabel` |
| What is a change that was proposed? | `ProposalItem.pending(...)` |
| What is still waiting? | `AiProposal.pendingItems` |

**A surface may not name a status.** The arch gate says so: no file under a
`presentation` package may import `ProposalItemStatus`. The previews build
their items with `ProposalItem.pending(...)`, and the card prints
`item.outcomeLabel`.

**The wording of a decision lives in one place.** `ProposalDecisionStyles`
holds the three styles — `reviewScreen`, `noteEditor`, `taskDetail` — and the
second half of the gate says that only that file may declare a
`ProposalDecisionStyle`.

**One store for the reasons.** `ProposalFailureStore` is one class over a
`StateFlow`. The surfaces that keep their reasons in their own state mirror the
store into it; the task detail, whose state is assembled from nine combined
inputs, reads the store directly.

**The gate has a positive test.** A rule that matches nothing passes for the
wrong reason, so the third test asserts that the domain really does answer:
`ProposalItem` declares `outcomeLabel` and `pending(`, and the surfaces go
there for them.

## Rationale

A surface that names a status is a surface that has opinions about the
lifecycle. The opinions are not wrong — they are the same opinions, written
down three times, with nothing to keep them in step. The card's wording and
the screen's filter are the same fact asked twice, and the two answers drift:
"Rejected" in one place and "Turned down" in another is two answers to one
question, and the user reads both.

The domain is where the answer belongs. The domain already knows what a
proposal is and what a decision is; the *word* for it is a small addition, and
it makes the surfaces unable to disagree.

The reasons were the sharper half. The state of the review screen is rebuilt
whenever the proposals arrive, and the reason of a change the user has not read
yet was rebuilt with it. A reason the user did not read yet is still a reason —
and the store, which owns the reasons and is not rebuilt, keeps it.

## Consequences

- **The card, the review screen, the note editor, the task detail and the
  previews no longer import a status.** The compiler stopped helping here —
  the gate is what holds it.
- **The styles moved.** The values did not change; only their address did.
- **The reasons have one implementation.** The `MutableProposalFailureStore`
  and the `proposalFailureStore(onRecord, onClear)` factory are gone.
- **The gate is a Konsist scan**, and it is tagged `slow` with `slow:konsist`:
  it walks the production sources.
- **A surface that wants a new wording** now changes one value in one file.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItem.kt`
  — `outcomeLabel`, `pending`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalDecisionStyles.kt`
  — the three styles.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalFailureStore.kt`
  — the store.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ProposalLifecycleStaysInTheDomainTest.kt`
  — the gate.
- `docs/decisions/2026-10-23-the-difference-between-two-surfaces-is-a-value.md`
  — the shared coordinator these styles belong to.
