# The proposal says what it is about, and reads strictly — Tasks

## 1. The change says what the proposal is addressed to

- [x] `ProposalTargetKind` — a type for the kind of target, carrying the values
      the row already carries, and `Unknown` for a name this build does not know.
- [x] `ProposalTarget` declares the kind of target; `Creation` asks its creation
      kind, and a creation declares what it brings into being.
- [x] `ProposalTargetKind.of(changes)` — one kind per proposal; a mixed batch is
      refused.
- [x] The builder reads the kind of target from the change; the caller cannot
      state it.
- [x] `forTask` and `forNote` refuse a change that is about something else.
- [x] The thirteen call sites that wrote the kind by hand no longer do.

## 2. A status this build does not know is not a waiting one

- [x] `ProposalItemStatus` is a hierarchy: the four known states and
      `Unknown(raw)`.
- [x] The reader is strict, and there is only one — the mapper and the
      repository read the same way, and `valueOf` is gone.
- [x] `isPending` is exactly `Pending`; `isDecided` is a decision.
- [x] `Unknown` is neither: it cannot be applied, and it is still the user's to
      turn down — the compare-and-set expects the status the change was read
      with, so the refusal reaches it.
- [x] The reason is with the change, and the refusals name it.
- [x] The name the status was found with is kept, so the row is written back
      unchanged.

## 3. The status of a proposal is what its changes say

- [x] `AiProposal.status` is derived from the changes.
- [x] The reducer moved into the model, next to what it reads.
- [x] The stored summary is a cache: it is written, and it is not read into the
      domain.
- [x] The port asks the domain's question — `watchWaitingProposals`, and the SQL
      asks the same one, so the screen reads the waiting rows and not every
      proposal the user ever made.
- [x] The surfaces ask the proposal — `isWaiting`.
- [x] The gate forbids a surface from naming a proposal's status, and its
      positive test says what the domain must offer in exchange.

## 4. The checks

- [x] `:shared:compileTestKotlinJvm`.
- [x] `:shared:jvmTest` — the whole set.
- [x] `just lint-changed`.
- [x] `scripts/ci/static-gates.sh`.
- [x] The Python suite.
