# backup-include-remaining-tables

## What

Extend the backup payload so that restore round-trips the eight user data sets
that are currently dropped: reminders, checklist items, tag groups, project-tag
group assignments, saved searches, time entries, and profiles.

## Why

A backup that omits data is indistinguishable, to the user, from a backup that
worked. Restore completes, reports success, and produces an app missing the data
the user backed up. The omission is only discoverable after a device loss or a
reinstall — the moment the backup existed for.

Agenda views were added to the payload on 2026-10-03. The same sweep found eight
more tables absent. Each was left out individually, and none of the omissions
produced a failure.

## Scope

### In scope

- Seven data sets carried in the payload with an explicit ordering that respects
  parent-before-child: reminders, checklist items, tag groups, project-tag group
  assignments, saved searches, time entries.
- Restore reporting: the manifest states what the backup contains, and restore
  states what it restored, so an absent data set is visible rather than silent.
- The profile question — see design.md, this is the one part that needs a
  decision rather than a DTO.

### Out of scope

- Conflict resolution when the backup contains rows that already exist locally.
- Sync reconciliation after a restore.
- Attachment file contents. The payload carries references; the files themselves
  are out of scope and are unchanged by this proposal.
- Schema version policy beyond the single bump this change requires.

## Why this needs a spec and not just a change

Restore is the one operation where the user expects a before/after equivalence
with no exceptions. A data set that silently does not survive it is a defect
class, not a missing feature, and the requirement is therefore about the
*report*, not only about the copy.

## References

- `docs/decisions/deferred-backlog.md#agenda-views-not-in-backup`
- `openspec/changes/baseline-write-pipeline` (backup/restore is explicitly
  out of scope for the write pipeline spec)
- Issue #77

## Status

**Proposed.** The profile sub-decision is unresolved — see design.md.
