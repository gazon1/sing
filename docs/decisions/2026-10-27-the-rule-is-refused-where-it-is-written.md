---
title: "A rule is refused where it is written"
date: 2026-10-27
status: accepted
tags: [tasks, series, recurrence, sync, backup, database]
related: 2026-10-25-a-recurring-series-carries-the-rule-on-every-row
---

# A rule is refused where it is written

## Context

ADR `2026-10-25-a-recurring-series-carries-the-rule-on-every-row` settled what a
recurring series is: the rows carry the rule, the dates are the rule's own
occurrences, and a row that stands alone **must not** carry a rule. The two UI
doors upheld that: the create screen materialised a series, and setting a rule on
a task went through `StartRecurringSeriesUseCase`.

The doors are not the only writers. The AI tools, the MCP server, a sync pull and
a backup restore all reach the storage layer without passing them. Between such a
write and the next launch the database held the forbidden shape — and the launch
repair existed to adopt it later, which made the invariant a promise about
tomorrow rather than a rule about the write.

Two consequences surfaced only when the invariant moved down:

1. **The pull path retries forever.** A document from a device that predates the
   series model carries a rule and no series id. Refusing that write is right,
   but a storage-style failure makes the sync engine hold the cursor and
   re-deliver the same bytes every cycle. The account stalls on one row no
   local pass can repair, because there is no local row.
2. **A gated repair can miss restored rows.** The launch repair runs once per
   profile behind a stored version marker. A backup restored after that run
   writes rows straight through the DAO, so a legacy archive lands with the
   marker already saying "repaired".

## Idea

**The refusal belongs where every writer passes, and the two paths that cannot
repair the row immediately must not pretend they did.**

- The repository refuses a rule with no series id: `create`, `update`,
  `createSeries`, `updateAll` and `upsert`.
- A pull that receives such a document **skips** it with a named outcome instead
  of failing the event. The cursor advances, nothing is stored, and the sender's
  next push — after its own repair — carries the series.
- The launch repair keeps its per-profile marker, and any path that can restore
  legacy rows clears the marker for the profile it wrote into.

## Decision

1. `TaskSeriesInvariants.requireRuleLivesInSeries` is the single statement of the
   invariant. The repository calls it for every row it is asked to store, and the
   sync writer reads the same predicate to decide that a document is
   inadmissible.
2. `SyncDocumentWriter.upsert` raises `InadmissibleTaskDocumentException` for a
   task document whose merged row would carry a rule outside a series.
   `PullEventHandler.classify` maps that exception to `ApplyOutcome.Skipped`: the
   cursor advances and the event is recorded as dropped.
3. `RepairRecurringSeriesUseCase` stores a version per profile and returns before
   the candidate query when the stored version is current. A failed pass leaves
   the marker unset, so the next launch retries.
4. `BackupImporter` clears the marker for the profile it restored into, so the
   next launch visits the restored rows. Both restore entry points funnel through
   the importer and stamp the active profile id on the options.

## Rationale

A rule written on a standalone row is not a malformed document — it is a true
statement about the sender's data and a false one about the receiver's model. The
receiver cannot materialise the window from a document that carries no series id
without inventing the series' position, and it cannot hold the cursor for a row
that may never come back in a form it can accept. Skipping is the only answer
that is both honest and non-blocking: the row is not stored, the sync keeps
working, and the upgraded sender is the one that owns the repair.

The marker is a performance decision, not a correctness one. The repair is
idempotent; the marker only keeps a repaired profile from paying for a query on
every launch. That is why the failure mode of a missing marker is "one extra
query", while the failure mode of a stale marker is "legacy rows never visited".
The restore hook exists because the second is recoverable and the first is not.

## Consequences

- An AI/MCP writer that sets a rule outside a series now receives a failure
  rather than a stored row. It must mint the series id and materialise the
  window, the same way the create door does.
- A legacy document from an unupgraded device is not applied on this device; it
  is dropped with a warning and comes back once the sender repairs and re-pushes.
  The task is absent, not corrupt, for that window.
- A backup restore costs one repair pass on the next launch, even when the
  archive held no legacy rows.
- The repair marker is a per-profile settings value, not user-visible state. It
  is not exported with the settings snapshot and does not travel between
  profiles.
- The observable behaviour is already described by the archived change
  `a-repeating-task-is-a-series-of-occurrences` (`tasks` and `task-series`
  capabilities), so this record changes no spec delta. It records where the
  invariant is enforced and what the two non-UI writers do when they meet it.

## Links

- ADR `2026-10-25-a-recurring-series-carries-the-rule-on-every-row` — the model.
- ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say` — why the sync
  writer merges a document against the local row before judging it.
- Issues #1192, #1197, #1198; PRs #1167 and #1201.
- Specs: `openspec/specs/task-series/spec.md` REQ-TS-010,
  `openspec/specs/tasks/spec.md` (a rule behind every occurrence).
