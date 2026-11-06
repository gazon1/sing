---
title: "The stored spellings are contracts, not the names of the constants"
date: 2026-11-06
status: accepted
tags: [backup, naming, domain, database, agenda]
---

# The stored spellings are contracts, not the names of the constants

## Context

The repository kept the name of an enum constant in the places where the value is
*stored*: a backup payload spelled a task's priority `High` and its workflow state
`NextUp`, a saved agenda view spelled its date bucket `Today` and its status
`Active`, and the database columns spelled the same values the same way.

That is a promise nobody made. The constant is the code's word for the thing and is
free to change; the stored value is read back by a build that may not know the
constant at all. As long as the two are the same string, a rename silently rewrites
the format — and the data written before the rename stops being readable by the code
written after it. Nothing fails at the rename: the archive simply stops restoring,
and the symptom ("my backup is empty") is a long way from the cause.

The repository has already paid for this once, in a smaller way: the page cursor of
the AI read tool was fingerprinted by the *constant's* name, so renaming the
selection broke the cursors. The fix was to fingerprint by the wire name — the name
the enum encodes under — which is the rule this ADR makes general.

## Idea

Say the stored spelling out loud, next to the enum, and read both spellings back.

## Decision

`WireNamed` (`core-domain`, `core/serialization`) declares the spelling a value is
stored under, and `parseWireName` reads a value back. The reader accepts the declared
name **and** the constant's current name, so a payload written before a rename is
still read, and one written after it is read under the name it was given.

Two things follow from the split, and both are decisions:

- **A format this app owns names its values.** The backup payload moved to schema
  version 8: a task's priority is `high`, its kind `task`, its workflow state
  `next_up`, a note's kind `plain` — lower case, like every other name that travels
  off the device. The `v7 → v8` step rewrites the old spellings, so an archive taken
  in March still restores in April, and an older client refuses a v8 archive outright
  rather than restoring values it reads differently.
- **A schema this app does not own keeps its spelling.** The database columns
  (`tasks.priority`, `tasks.kind`, `tasks.workflow_state`, `notes.kind`) keep the
  constant's name: a column is schema, and changing what a column holds is a
  migration like any other column change — it needs a table rewrite and a version,
  not a lenient reader. The sync document keeps it too, because it is the protocol
  the server speaks.

The saved agenda views are the third case, and they are the one the lenient reader
was built for: the blob is a JSON document this app writes and reads back, so the
serializer writes the declared names and reads both. No migration is needed — a view
saved before the change still opens.

## The survey this decision is based on

Every place where a value is stored or keyed by the name of an enum constant:

| Where | What is stored | Decision |
|---|---|---|
| Backup payload: `tasks.priority`, `tasks.kind`, `tasks.workflowState`, `notes.kind` | the constant's name | **Moved** to the declared names (schema v8, `v7 → v8` step); the reader takes both |
| Saved agenda views (`agenda_views.sections_json`, and the same blob inside a backup) | the constant's name, in `DateBucket`, `Statuses`, `Priorities`, `WorkflowState` | **Moved** to the declared names; the reader takes both; no migration |
| Database columns: `tasks.priority`, `tasks.kind`, `tasks.workflow_state`, `notes.kind` | the constant's name (Room's enum converter) | **Kept** — a column is schema; a rename there is a migration |
| Sync document (`Task.priority`, `Task.kind`, `Task.workflowState`) | the constant's name (kotlinx) | **Kept** — it is the protocol the server speaks; changing it is a protocol change, not a rename |
| `Attachment.type`, `Attachment.syncStatus` | the constant's name | **Kept** — same as the other columns |
| `ToolCallLog.source`, `ToolCallLog.outcome` | the constant's name | **Kept** — a log line, and a log line that cannot be read is a line that is still there |
| `SelectorTemplate`, `SelectorParameterSheet` option ids | the constant's name | **Kept** — session-local, never persisted |
| `ProposalKindCodec` | the constant's name, read case-insensitively | **Already tolerant**, and left as it is |

## Rationale

The value is the thing that has to survive, and the constant is not. A registry of
names solves one half of that problem — it stops two layers from answering the same
word with different questions — but a stored value is a third kind of name, and it
was the one nobody wrote down.

Writing the spelling next to the enum costs a constructor argument and a parser, and
it makes the format readable on its own: a payload says `next_up`, and the code that
reads it does not have to know what the constant is called.

The reader is lenient on purpose. A payload that meets the reader under either name
is read as what it says; a payload that meets it under no name is refused rather than
guessed at, because a wrong value restored as a different value is worse than a
restore that fails loudly.

The split between "a format we own" and "a schema we do not" is what keeps the change
small. The backup payload and the saved views are documents this app writes and reads
back, so they can be made self-describing. The database columns and the sync
document are contracts with something outside the app — one with an older build of
the same app, one with the server — and changing those is a migration or a protocol
change, not a rename.

## Consequences

- A value that travels in a format this app owns is written under a name that is
  declared, not derived. Renaming the constant no longer changes the format.
- The backup payload is at schema version 8. A v8 archive is refused by a v7 client
  (it says so rather than misreading it), and a v1–v7 archive is migrated into v8 and
  restored.
- The saved agenda views need no migration: the reader takes the old spellings, and
  the blob keeps working either way.
- A new enum that travels in one of these formats must declare a wire name and be
  read with `parseWireName`. The drift is caught by the tests that read an old
  payload and a new one.
- The database columns and the sync document are knowingly still coupled to the
  constants. That coupling is now written down instead of being discovered.

## Links

- `core-domain/src/commonMain/kotlin/com/singularity/todo/core/serialization/WireName.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupMigrations.kt` — the `v7 → v8` step
- `core-domain/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/SelectorSerializer.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/backup/BackupWireNameTest.kt`
- [2026-11-06-one-name-one-question.md](2026-11-06-one-name-one-question.md) — the registry that checks the names
- [2026-11-04-inbox-names-the-unscheduled-tab.md](2026-11-04-inbox-names-the-unscheduled-tab.md) — the naming split that started this
