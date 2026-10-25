---
title: "The two changes are archived in the order their deltas ask"
date: 2026-10-25
status: accepted
tags: [openspec, process, contracts]
---

## Context

Two changes stayed active long after their work was done.

`acp-client-data-safety` made three promises — a probe that never deletes a file it
cannot read, a profile selector that refuses what it cannot obey, and a classification
that says how every tool writes. Its review found one thing open: the promise that
every write asks first was verified for two of the twelve proposal-gated tools, and
the other ten were declarations with no test.

`agent-surface-follow-ups` was the change that closed that finding: the classification
is published in the exported tool surface, the check reads the declaration against the
tools' behaviour in both directions, and every proposal-gated tool is driven through
the round trip. Its own review passed.

Neither change was archived, and an unarchived change is a change that is still
happening. Worse, the first change's verdict still said `fail` — a verdict that named
a finding the second change had closed.

The two changes are also not independent. The second one's spec delta *modifies* a
requirement the first one *adds*: the write classification exists as a requirement
only because the first change wrote it. A delta that modifies a requirement cannot be
applied before the requirement exists, so the archive order is not a matter of taste.

## Idea

Close what is left, say in the records what actually happened, and archive in the
order the deltas require — the order the dependency asks for, not the order the
changes were filed in.

## Decision

1. **The checklist names the files that exist.** A change's task list is a record of
   the work; a line that names a file the repository does not have is a finding in its
   own right. The work that moved to another module, or that another change finished,
   is named where it landed.

2. **A verdict whose finding was closed by a later change is re-issued, and it names
   the change that closed it.** The review that found the finding was a real review
   and is not rewritten; what changes is the verdict's standing, and it says on what
   the finding rests. The re-issued verdict names the commit the closing work landed
   in, and the closing work is reviewed in its own right before the verdict is
   re-issued.

3. **The archive order follows the delta dependency.** The change that adds a
   requirement is archived before the change that modifies it, whichever was filed
   first.

## Rationale

**Why the checklist is rewritten rather than left alone.** The tasks of a change move
while the work is done, and a checklist that has drifted from the tree tells the next
reader that work is missing where there is none — and hides the work that is there
under a path that no longer exists.

**Why the verdict is re-issued rather than left as a `fail`.** An archived change with
a `fail` verdict is a change that was reviewed and refused. The finding was closed,
the closing work was reviewed, and the record that says `fail` would send the next
reader looking for a problem that is not there. The re-issue does not erase the
review: the round that found the finding stays in the file, next to the round that
found the closing work sound.

**Why the order is the dependency's.** The archive applies a delta to the
specifications. A `MODIFIED` delta applied before the requirement it modifies exists
has nothing to modify, and the tool that applies it refuses. The order is therefore
not a preference — it is the only order in which both archives can happen.

## Consequences

- The two changes are archived, and the specifications they wrote are published.
- The data-safety change's verdict is re-issued as a `pass`, and the file says which
  change closed the finding and which commit the closing work landed in.
- Four scenarios that the data-safety change's own spec deltas named had no test that
  would fail if the code broke. They have one now — and that work is part of this
  change, not part of the archive.
- The archive moves the change directories and republishes the issue records, so the
  records under `.specify/issues/` point at the archived paths.
- A change that another change finishes is not archived alone: the archive says which
  change finished it, so the two records can be read together.

## Links

- Change `openspec/changes/archive/acp-client-data-safety/` — the change this
  decision archives first
- Change `openspec/changes/archive/agent-surface-follow-ups/` — the change that
  closed the finding, and that modifies the requirement the first one adds
- Verdict `.specify/reviews/acp-client-data-safety.md` — the review that found the
  finding, and the re-issue that closes it
- Verdict `.specify/reviews/agent-surface-follow-ups.md` — the review of the closing
  work
- ADR `2026-10-25-the-probe-says-what-it-saw` — the probe's record, which is one of
  the four scenarios this decision closes
