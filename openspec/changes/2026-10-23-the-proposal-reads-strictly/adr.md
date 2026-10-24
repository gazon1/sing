# The proposal reads strictly — ADR

The decisions this change is built on live in `docs/decisions/`:

- `docs/decisions/2026-10-23-the-format-is-told-by-its-shape.md`
  — the reader knows which format it holds before it reads it, and the sealed
  reader refuses a key it does not know.
- `docs/decisions/2026-10-23-the-change-says-what-it-is-about.md`
  — every kind declares its target, the target carries the kind, and the
  compiler checks the routing.
- `docs/decisions/2026-10-23-the-decisions-keep-the-reasons.md`
  — the reasons belong to the decisions, and the surfaces show them.
- `docs/decisions/2026-10-23-the-identity-of-a-row-has-a-name-of-its-own.md`
  — the identity of a written row has a name of its own, so the set of the rows
  that are gone cannot hold a missing one.
- `docs/decisions/2026-10-23-a-declared-name-is-a-name-the-reader-knows.md`
  — the two lists of field names are compared by a test, in both directions.
- `docs/decisions/2026-10-23-the-flat-reader-goes-when-the-old-release-goes.md`
  — the removal of the flat reader is a checklist, with the trigger and the
  order.
- `docs/decisions/2026-10-23-the-test-names-the-kind-of-viewmodel.md`
  — the test says what kind of ViewModel it is testing, and the harness builds
  the scope that kind needs.

## The one sentence

The previous round said it as **a change the user cannot see is a change
nobody decided about.** This round says the same thing from the other side:

**a change the user sees as something else is a change nobody proposed.**

## What the decisions have in common

They are all about *agreement* — between two declarations of the same thing,
between the reader and the format, between the change and the route it takes:

- The format and the reader agree, because the reader knows what it holds.
- The kind and the route agree, because the compiler checks it.
- The change and the field name agree, because a test compares them.
- The test and the harness agree, because the test names the kind and the
  harness builds the scope.

Every one of them is a place where two things used to be kept in step by hand.
The hand is gone; the check is in place of it.

## The one that is not about the user's screen

The routing decision is the only one that is invisible to the user. It is here
because the invisible ones are the ones that rot: a change that is routed by a
predicate somebody forgot to update does not fail — it fails *later*, on a
user's device, as a change that was applied to the wrong thing.

## The cost, stated once

A change written by a newer build is refused by an older one. It is not
applied, and it is not read as something else. The user sees it on the card
with the reason, and can turn it down or wait for an update.

That is the price of a format that is honest about what it does not know, and
it is paid only between two releases.
