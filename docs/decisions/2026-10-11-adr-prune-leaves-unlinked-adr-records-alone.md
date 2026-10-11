---
title: "adr prune is a report, not a delete — unlinked ADR records are left alone"
date: 2026-10-11
status: accepted
tags: [adr, tooling, backlog, python]
issue: 537
---

## Context

`adr prune` shows 208 ADR records that are not cited by any other ADR or by `DIGEST.md`. The command prints them and exits. It does not delete them.

The original impulse was to clean up the corpus: records that nobody links to are, by definition, records nobody found useful. But citation is not the same as usefulness. A record can be:
- Correct and sound, but never needed again because the decision it records was never revisited
- The result of work that was superseded but not yet formally linked
- Simply unloved by coincidence — the right person never needed to cite it

## Decision

`adr prune` **reports** unlinked ADR records. It does not delete them.

To close a record, an ADR must be superseded: write a new ADR naming the old one in `supersedes:`, then call `update_adr` on the old one with `status=superseded`. This creates a citation link and closes the record through the proper chain.

## Rationale

A command that can destroy a record of a decision must earn that capability separately from one that merely reports what it found. Deletion is irreversible; reporting is not. The distinction matters because a record that looks unloved may be:
- The only evidence of a decision that was made correctly and simply never needed to be revisited
- A record that someone spent real time writing, and whose deletion would erase that work silently

The corpus grows slowly and mostly correctly. A growing corpus is healthier than a cleaned one where the cleaning erased the evidence of work that was done.

## Consequences

- `adr prune` continues to print unlinked ADR slugs as a report
- No automatic deletion, archival, or status change occurs
- A record that is truly dead (superseded by a newer decision) must be closed through the supersession workflow
- The 208 unlinked records remain available, findable, and resolvable
