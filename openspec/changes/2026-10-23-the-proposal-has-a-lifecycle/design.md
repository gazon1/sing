# Design — the proposal has a lifecycle, and the vocabulary is typed

The rationale for this change lives in the four ADRs it created:

- `docs/decisions/2026-10-23-the-value-is-the-field.md`
- `docs/decisions/2026-10-23-the-proposal-can-be-changed-before-it-is-applied.md`
- `docs/decisions/2026-10-23-the-review-scenarios-live-in-the-chat.md`
- `docs/decisions/2026-10-23-the-invariant-is-a-test-not-a-file.md`

This file records the shape of the implementation and what would have to be undone.

## The shape

- `feature/proposals/domain/model/ProposalValues.kt` — the typed vocabulary. The
  variant **is** the field: `TaskValue.Priority(TaskPriority.High)` replaces
  `SetTaskField(TaskField.Priority, "high")`. One reader per entity —
  `TaskValue.read(field, text)` and its three siblings — and a reader that cannot
  read a value says why.
- `feature/proposals/domain/model/ProposalItemKind.kt` — the kind and its
  behaviour. `humanSummary` carries the value ("Set the title to «Buy milk»"),
  `fingerprintTarget` is the identity the rejection guard compares, and
  `editableProposal()` answers whether the user may change this kind at all.
- `feature/proposals/data/ProposalKindCodec.kt` — the codec, in its own file. It
  is the one place where the type of a kind is a string, so it is the one place the
  compiler cannot help; the round-trip test in `ProposalMapperTest` is what holds
  it. `ProposalMapper.kt` stays what it was: the mapper of rows.
- `feature/proposals/domain/usecase/EditProposalItemUseCase.kt` — the change
  before the apply. It reads the typed value with the same reader the model's
  value was read with, and refuses a value that does not read, with the reason.
- `feature/proposals/data/ProposalItemDao.kt` — `updatePendingItem` is a
  compare-and-set on `status = 'Pending'`, so an edit and an apply cannot both
  win, and an edit of a decided item is not an edit at all.
- `feature/proposals/presentation/ProposalsViewModel.kt` — `failures` is a map
  from the item to the reason it could not be applied, and the screen reads the
  `ProposalsUiEvent.ShowError` it was never reading. Every failure on the
  proposals screen used to be silent.

## The three surfaces

The card is one card, so the change works the same wherever a proposal is shown:
the proposals screen, the note editor and the task detail. The task detail got
its own seam — `TaskDetailIntent.Domain.EditProposalItem` and
`TaskDetailCollaborationDeps.editProposal` — because a task detail is a
coordinator, not a screen with a view model.

## Why the storage format did not change

The kind is still stored as a flat JSON payload with a `field` and a `value`.
The domain is typed; the codec is not. Old rows keep reading, and a row written
before this change is still readable by the code that comes after it. Changing
the stored format would have meant a migration for a gain nobody asked for.

## The rejections

A value that does not read is refused at the point the proposal is built
(`ProposalOperation.Outcome.UnreadableValue`), not at the point it is applied: a
proposal that cannot be applied was never a proposal.

## Rollback risk

**Low for the vocabulary.** `ProposalValues.kt` is new, and the kinds it feeds
were already sealed hierarchies; undoing it means putting the strings back into
the kinds and deleting the file.

**Medium for the lifecycle.** The compare-and-set in the DAO, the `failures` map
and the three surfaces that now read error events would have to be unwound
together — the card shows a reason that the screen has to know about.

**None for the write path.** `create_tag` proposing like the other tools is a
behaviour change of the tool, not of the storage: the tool now answers
`proposalCreated` and stores a proposal, and the tag itself appears when the
proposal is applied.
