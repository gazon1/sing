---
title: A change is registered, or the next session cannot find it
date: 2026-10-22
status: accepted
tags: [process, openspec, gates]
---

# A change is registered, or the next session cannot find it

## Context

`AGENTS.md` has said for a while that `.specify/issues/<slug>.yaml` is the
**source of truth** for a change: the record says what the change was, what it
decided, and where the spec and the tasks live. Nothing checked that the record
existed.

Four changes reached `main` without one:

- `close-rule-consolidation` — the consolidation of the close rule;
- `every-close-refuses-in-its-own-write`;
- `a-closed-project-is-reachable`;
- `the-backup-keeps-the-verb`.

Nothing failed while it happened. The specs validated, `openspec validate` was
green, CI was green, and the work was good — and the registry simply did not
mention any of it. The failure is not loud because it is not a failure of the
work; it is a failure of the record. A later session that opens the repository
to find out what a change was reads the registry, finds nothing, and starts
from the diff.

The gate-wiring rule of this repository already says it: *a gate nobody has
tried to break is a gate nobody knows can fail*. The same is true of a rule
nobody checks — it is not a rule, it is a hope that costs context load in
`AGENTS.md` and buys nothing.

## Decision

A change is registered when it is created, and the registry is checked.

1. **The record is created with the change**, by the command that already
   creates it: `python3 scripts/openspec-import-all.py`. A change is not
   "recorded on a change" as a later bookkeeping step; the record is part of
   the change.
2. **A blocking gate covers the fact**: `scripts/check-issue-registry.py`,
   registered as *issue registry covers every change*. It checks both
   directions — every change has a record, and every record points back at its
   change. A record that points at another change is worse than no record,
   because it looks like coverage.
3. **The gate covers live and archived changes.** An archived change is still a
   decision, and the registry is where the decision is written down.
4. **The recorded path is compared by its last segment.** Moving a change into
   `archive/` is a rename the registry does not follow: the archived
   `the-search-asks-the-index` still records the path it had before the move.
   Requiring that path to resolve would make every archive operation a second,
   unrelated edit — and the record's meaning does not change when the change
   is filed away.
5. **The gate has a positive control.** Its sabotage points a record at
   another change, and the gate has to fail. A gate that is only ever run
   against a clean tree is a gate whose failure nobody has seen.

## Alternatives

- **Leave it as prose in `AGENTS.md`.** Rejected: the rule was already prose
  for four changes' worth of work. Prose does not fail; it is read once and
  then not read again.
- **Make the gate advisory.** Rejected for the same reason the other gates are
  blocking: an advisory gate is a gate nobody has to satisfy, and the four
  changes were not the result of a red advisory line — they were the result of
  no line at all. The gate is green on the tree as it stands, so blocking costs
  nothing today and buys the fact tomorrow.
- **Compare the recorded path in full.** Rejected, and see point 4: it turns
  every archive into a second edit and makes the gate's answer depend on where
  a change was filed rather than on what the record is about.

## Consequences

- A new change needs one command after its directory appears:
  `python3 scripts/openspec-import-all.py`. The gate's failure message says
  so.
- The registry and `openspec/changes/` cannot drift apart without a red gate,
  in either direction.
- The gate is cheap: it reads 96 directories and 96 small files, and it runs in
  the static-gate sweep, which is about a minute in total.
- The four gaps this found are closed in the same change, by running the import
  script — the point of the gate is not to catch the past, but the past is now
  in the record too.

## Links

- `scripts/check-issue-registry.py` — the gate.
- `scripts/src/sgate/commands/_registry.py` — the gate's registration and its
  positive control.
- `scripts/src/sgate/gate_manifest.py` — why the gate exists, in the place a
  reader of the gate list will look.
- `AGENTS.md` § spec-kit — the rule, and the command that satisfies it.
