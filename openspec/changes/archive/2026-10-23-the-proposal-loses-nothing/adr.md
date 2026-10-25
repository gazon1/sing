# The proposal loses nothing — ADR

The decisions this change is built on live in `docs/decisions/`:

- `docs/decisions/2026-10-23-the-id-is-known-before-the-row-is-written.md`
  — the write path names the id of every row it is about to create, and the
  take-back addresses the row rather than the result of the write.
- `docs/decisions/2026-10-23-a-change-this-build-cannot-read-stays-on-the-card.md`
  — a kind this build does not know is a change that carries the reason it
  cannot be applied.
- `docs/decisions/2026-10-23-the-lifecycle-belongs-to-the-domain.md`
  — a surface does not name a proposal's status, and the wording of a decision
  lives in one place.
- `docs/decisions/2026-10-23-the-stored-change-is-the-models-own-shape.md`
  — the stored change is written by the model's own serializer, and the flat
  format is read until it is not needed any more.
- `docs/decisions/2026-10-23-the-harness-gives-the-scope.md`
  — the harness gives the ViewModel its scope, and the test says what kind of
  ViewModel it is testing.

## The one sentence

Every one of them says the same thing in a different place: **a change the user
cannot see is a change nobody decided about.**

## What the decisions have in common

They are all about the *user's* point of view, and none of them is about the
code's convenience:

- the id is known before the write so that a row the user never saw does not
  stay behind;
- the change this build cannot read stays on the card so that the user can
  decide about it;
- the lifecycle belongs to the domain so that three surfaces cannot answer
  differently;
- the reasons live in one place so that a reason the user did not read yet is
  still a reason;
- the stored shape is the model's own so that a value cannot come back as
  something else;
- the harness gives the scope so that a test cannot pass because nothing ran.

The rule from `CODING_STANDARDS.md` — *a change the user cannot see is a change
nobody decided about* — is the acceptance criterion of this whole change.
