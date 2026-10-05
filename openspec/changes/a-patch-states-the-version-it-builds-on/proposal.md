# a-patch-states-the-version-it-builds-on

**Status:** proposed · **Issue:** #178

## What

A patch's `baseVersion` now comes from the version the server reported, recorded per
entity in the shadow, instead of from a field on the entity that nothing ever wrote.
Requirement REQ-OS-025.

## Why

The client parsed `newVersion` out of every push response and then dropped it. Every
patch it ever sent carried `baseVersion = 0`, which is the protocol's way of saying "the
server has never seen this row" — on the tenth edit to a row the server had been applying
for days.

Reading the entity's own `syncServerVersion` could not have fixed it. That field is
written by the local repositories, which have no way to learn what the server said, so it
was 0 everywhere too. The shadow already holds the state the server is known to hold, and
a version without the state it belongs to would be half a fact — so the version lives
there, and acknowledging a push writes it on the same guarded statement that promotes the
state.

Two things had to be got right for this to work at all, and both were found by a test
failing rather than by reading:

- **The shadow write replaced the whole row.** A fresh `SyncShadowEntity` defaulted the
  version to 0, so the version was wiped by the very next local edit — the patch before
  it would be right and every one after it wrong. The builder now carries it.
- **A null version had to mean "leave it alone".** Binding null into the column stores a
  null rather than skipping the assignment, so the confirm query uses `COALESCE`. Without
  it, a response that legitimately carries no version would reset the client to 0 and the
  next patch would be refused — reintroducing the defect one step later.

## Scope

**In scope:** the column and its migration; the base version source; recording the
version on confirmation; and the three tests that pin the round trip.

**Out of scope, deliberately:**

- **Delete patches.** The other half of #178, and not the same change: a delete is not a
  field change, so the builder has nothing to diff, and the signal that a row was removed
  has to come from the repository that removed it. `enqueue` takes an entity and has no
  way to be told the entity is gone.
- **The entity's `syncServerVersion`.** Six types carry it and nothing writes it. It is
  now dead, and removing it is six edits with a migration; leaving it is a smaller change
  than removing it correctly, and it is worth a follow-up rather than a drive-by here.
- **A row recreated after a delete.** Version history across a delete needs the delete to
  exist first, and gets the same treatment.
- **What the version is used for.** Per-field merge is ordered by the field clock, so a
  stale base is a refusal rather than a lost edit. Fixing the base removes the refusals;
  it does not by itself make the merge smarter.
