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
   registered as *issue registry covers every change*. It finds three things:
   a change with no record, a record that names a different change, and two
   changes claiming one slug. A record that names another change is worse than
   no record, because it looks like coverage.
3. **The gate covers live and archived changes.** An archived change is still a
   decision, and the registry is where the decision is written down.
4. **The record is matched by slug, not by path.** Archiving a change renames
   it: this repository files a change as `openspec/changes/<slug>` and archives
   it as `openspec/changes/archive/<date>-<slug>` — the move *adds* a date
   prefix to the directory. The registry entry is named after the slug, which
   the date prefix does not change, and its recorded path still names the
   place the change lived before the move. So a record is accepted when the
   path it names sits under `openspec/changes/` (or `openspec/changes/archive/`)
   and ends in a directory whose slug is the slug of the change being checked.
   Comparing the raw path instead would fail on the next ordinary archive
   operation — and the first version of this gate did exactly that.
5. **The gate checks one direction, and says so.** It walks the changes and
   looks up their records. It does not walk the records: most of the registry
   is history — records whose change was archived long ago, under a path the
   repository no longer has — and a reverse check would report hundreds of
   them. A list of hundreds of known-old records is a baseline, not a rule.
   The direction that is checked is the one that can still go wrong for work
   that is happening now.
6. **The gate has a positive control.** Its sabotage points a record at
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
- **Check the registry in both directions.** Rejected, and see point 5: the
  other direction is history, and a baseline of hundreds of known-old records
  is not a rule. The direction that can still go wrong is the one that is
  checked.

## Consequences

- A new change needs one command after its directory appears:
  `python3 scripts/openspec-import-all.py`. A record that names another change
  is rewritten by the same script with `--replace` — without it the importer
  skips a record that already exists, which is exactly the case that needs
  rewriting. The gate's failure message says which command is which.
- A change cannot enter `openspec/changes/` without a record, and a record
  cannot name another change without a red gate. The opposite drift — a record
  whose change is long gone — is history, and is not checked; see point 5.
- The gate is cheap: it reads the change directories and the records that name
  them, and it runs in the static-gate sweep, which is about a minute in total.
- The four gaps this found are closed in the same change, by running the import
  script — the point of the gate is not to catch the past, but the past is now
  in the record too.
- The first version of this gate compared raw paths and promised a tolerance
  for archiving that it did not deliver: the repository's archive convention
  adds a date prefix to the directory, so the next archive operation would
  have been a red CI run. The rule is now the slug, and the test that was
  supposed to cover the case used an already-dated example and covered
  nothing. A reviewer found both; the second version of the gate and the test
  that exercises the real convention are in the same branch.

## Links

- `scripts/check-issue-registry.py` — the gate.
- `scripts/src/sgate/commands/_registry.py` — the gate's registration and its
  positive control.
- `scripts/src/sgate/gate_manifest.py` — why the gate exists, in the place a
  reader of the gate list will look.
- `AGENTS.md` § spec-kit — the rule, and the command that satisfies it.
