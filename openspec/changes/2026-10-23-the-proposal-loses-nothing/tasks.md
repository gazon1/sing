# The proposal loses nothing — Tasks

## 1. The write path names the id before the write

- [x] `ProposedRow` carries the id, the write, the take-back and the "is it
      gone" question.
- [x] `ProposalDispatch.rowsOf` is the table of writers; one application point.
- [x] `ChecklistRepository.addItem` takes the id; `TimeTrackingRepository.createManualEntry`
      takes the id; the impls and the fakes follow.
- [x] The take-back tolerates a row that is not there.
- [x] The subtasks and the note's actions share one writer.
- [x] The claim is determined by the transaction: the flag is set inside the
      non-cancellable block that holds the claim.

## 2. A kind this build cannot read stays on the card

- [x] `ProposalItemKind.Unknown(raw)` joins the vocabulary.
- [x] `decodeKind` always answers with a kind.
- [x] The mapper keeps the row.
- [x] The plan refuses it before the claim, with the reason.
- [x] The card shows the reason.

## 3. The lifecycle belongs to the domain

- [x] `ProposalItem.outcomeLabel`; `ProposalItem.pending(...)`; `AiProposal.pendingItems`.
- [x] The card, the review screen, the note editor, the task detail and the
      previews stop naming the status.
- [x] `ProposalDecisionStyles` holds the three styles.
- [x] The arch gate: no surface names a status; the styles are declared in one
      place; the domain really does answer.

## 4. One store for the reasons

- [x] `ProposalFailureStore` is one class over a flow.
- [x] The review screen and the note editor mirror it into their state.
- [x] The task detail uses it as it did.

## 5. The stored change is the model's own shape

- [x] The sealed format is written.
- [x] The flat format is read, and a test says so.
- [x] The flat writer is gone.
- [x] The removal plan is in the ADR.

## 6. The harness gives the scope

- [x] `VmScope` names the two kinds of ViewModel.
- [x] The harness builds the scope; the test names the kind.
- [x] The call sites are migrated.

## 7. The travel of a taken-back row

- [x] The take-back of a created task is a delete, and a delete travels as an
      archive — tested (`a subtask that was written and then lost is archived,
      not left alive`).

## 8. The checks

- [x] `:shared:jvmTest` — the whole set.
- [x] `just lint-changed`.
- [x] `scripts/ci/static-gates.sh`.
- [x] The Python suite.
