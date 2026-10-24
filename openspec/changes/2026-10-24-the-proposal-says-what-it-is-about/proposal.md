# The proposal says what it is about, and reads strictly

## Why

Three things about a proposal were said twice, and a place where they disagreed
was a place a user could feel.

**The kind of target.** The change says what it is about — a task, a note, a
project, a tag — and the proposal was addressed by a string written beside it.
Thirteen places wrote that string by hand. Nothing checked it against the change
next to it, and the builder said so itself: "a caller that gets the kind wrong
writes a proposal the confirmation screen will not find". A proposal addressed
to a task that holds a note's change is a proposal the user never sees — a
change that was proposed and cannot be found.

**The status of an item.** The reader filled the gap: a status it could not
name became `Pending` — the one status that says "this change can still be
applied". A row written by a build that knew a status this one does not was
therefore read as waiting, and a change that had already been applied was
applied a second time. The same status was read in two ways in two places: the
mapper filled the gap, and the repository that recomputes a proposal's aggregate
status did `valueOf` and threw. An unknown status is now neither waiting nor
decided: the change cannot be applied, the card names the status it was written
with, and the user can still turn it down — the compare-and-set expects the
status the change was read with.

**The status of a proposal.** It was a column of its own, written on a
best-effort path, and the surfaces believed it: the review screen asked the row
for "pending" and then filtered the answers by the changes. When the row and the
changes disagreed, the row decided which proposals arrived and the changes
decided which were shown.

## What changes

- **`ProposalTargetKind`** — a type for the kind of target, carrying the values
  the row already carries, and `Unknown(raw)` for a name this build does not
  know. `ProposalTarget` declares it, and a creation declares what it brings
  into being. The builder reads it from the change; the caller cannot write it.
  A change offered a task id but is about a note fails loudly.
- **`ProposalItemStatus` becomes a hierarchy.** The four known states, plus
  `Unknown(raw)` for a name this build does not know. `Unknown` is neither
  waiting nor decided: the change cannot be applied, the card says why, and the
  user can still turn it down. The name it was found with is kept, so the row is
  written back unchanged.
- **The status of a proposal is derived from its changes.** The column stays and
  is still written — it is a cache — but nothing reads it into the domain. The
  port asks the domain's question (`watchWaitingProposals`), and the surfaces ask
  the proposal (`isWaiting`). The gate that already forbade a surface from
  naming an item's status now forbids a proposal's status as well.

## What does not change

- **The stored format.** The four status names are the four the rows carry, the
  target kinds are the strings the rows carry, and the columns are the columns
  there were. There is no migration.
- **What a proposal is addressed to.** Every one of the thirteen hand-written
  strings was the one the derivation now produces.
- **What the user sees.** The proposal card looks the same, the review screen
  shows the same proposals, and the tools build the same proposals. What changes
  is what happens when a row was written by a build that knew more than this one:
  the change stays on the card with a reason instead of being applied again or
  never shown at all.

## Cost

- 1 new type, 1 type that stopped being an enum, 1 field that stopped being
  stored.
- 13 call sites that lost a line, 2 port methods that lost their reason to
  exist, 1 rule added to an existing gate.
- The written format does not change, so there is no migration and no
  compatibility window.

## Out of scope

- **The versioned wrapper for the blob.** The reader is still strict about the
  *shape* of a change, and still cannot tell that a field with a default was
  dropped by a newer build. Recorded as a follow-up in
  `docs/decisions/2026-10-23-the-format-is-told-by-its-shape.md`.
- **The flat reader.** It goes when the old release goes out of support — the
  checklist is in
  `docs/decisions/2026-10-23-the-flat-reader-goes-when-the-old-release-goes.md`.
- **The source of a proposal.** It is still read leniently: it gates nothing, and
  a wrong one mis-attributes the proposal in the feedback loop without changing
  what happens to it.
