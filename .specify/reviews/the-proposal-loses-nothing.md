---
change: the-proposal-loses-nothing
verdict: pass
date: 2026-10-23
pr: 1036
reviewed: a0f79f291a086b91ad7fd0f1a738935923632aaa
documents: sha256:4eaeca7c43aa5aee6d97b3f943704d74c518efd5ec327e450f329a26fe5d393e
---

## What was reviewed

The eight follow-ups the proposal lifecycle left behind, read off the code and
implemented as one change: `gazon1/feat/the-proposal-loses-nothing`.

The review is of the diff of the whole change — the write path, the codec, the
domain model, the three surfaces and the test harness — against the contract
the change carries (`openspec/changes/2026-10-23-the-proposal-loses-nothing/`).

## What the change closes

**The three gaps the previous round named as unverified.** The last review of
the lifecycle work ended with three things it could not verify, and this change
is the answer to all three:

- *"The `NonCancellable` protection itself is not exercised by a test."* It is
  now: a test cancels the scope while the claim is in flight and asserts that
  the change waits again and that nothing was written.
- *"Room's cancellation semantics for a suspend DAO call … leaves a row written
  and unrecorded, or a claim that was taken and not released."* Both are closed
  by naming the id of every row before the row is written, and by making the
  claim's own call uncancellable so that the fact "the claim is standing" is
  known at the moment the transaction decided it.
- *"An unknown kind is still a dropped row."* It is not any more: a kind this
  build cannot read is a change that carries the reason it cannot be applied,
  and it stays on the card.

**And the five that came with them.** The lifecycle is the domain's (a surface
may not name a status, and the gate says so); the wording of a decision lives
in one place; the reasons live in one store; the stored change is the model's
own shape; and the harness gives the ViewModel its scope.

## What was checked

- **The write path, row by row.** Every row the dispatch writes carries the id
  it will have; the take-back addresses that id; the take-back tolerates a row
  that was never created (and says so, instead of reporting a row that stayed);
  a row that was taken back is not taken back twice.
- **The claim.** The flag is set where the transaction is; a scope that is
  already gone does not claim; a cancellation that arrives while the claim is in
  flight gives the claim back.
- **The codec.** The sealed format round-trips every kind and every value; the
  flat format of the builds before this one still reads; a blob nobody can read
  keeps its payload and gains a reason.
- **The surfaces.** No file under a `presentation` package names a status; the
  three styles are declared in one place; the domain really does answer (the
  gate's positive test).
- **The harness.** The scope the ViewModel gets is the harness's; the test names
  the kind of ViewModel, not the scope.

## What was not verified

- **Room's own cancellation semantics.** The fix does not depend on them — the
  claim's call is made uncancellable, so the transaction's outcome cannot be
  lost on the way back — but the claim is still made inside a database
  transaction whose rollback behaviour was not checked against Room's sources.
- **A device on the previous release.** The sealed format is not readable by a
  build that only knows the flat one: such a build shows the change as a change
  it cannot read, with the reason. That is stated in the ADR as the price of
  the format, and it is paid only between two releases.
- **The flat reader's removal.** The plan is written down; the removal itself is
  a follow-up, after the release that ships this change falls out of support.
