---
title: "The probe says what it saw, and not what it guessed"
date: 2026-10-25
status: accepted
tags: [database, startup, logging]
---

## Context

The startup probe reads a candidate database file before it opens it: a file that does
not start with the SQLite header is not a database, and saying so is more useful than
opening it and interpreting the driver's error.

The read had two answers. It returned the header's bytes, or it returned `false` — and
the `false` covered everything: a file that is not a database, a file that is empty, and
a file that could not be opened at all. The record the startup left was the same in all
three cases: *"the file does not start with a database header"*.

That last one is a misdiagnosis. A file the process could not read is not a file that
failed the header test — it is a file the process never looked at. The difference is not
cosmetic: a leftover from an older layout is not a user's data behind a permission the
process does not have, and the two call for different actions from whoever reads the
record.

The spec says the startup "records what it saw instead of deleting anything", so the
record is part of the contract. And nothing could see the record: it was a line in a log,
written by a function that returned nothing but a boolean, so a change to the wording
would have left every test green — the file would still be kept either way.

No test drove the unreadable case either, because the obvious way to make a file
unreadable — a permissions trick — does not work on a process that can read any file,
which is exactly the machine where a mistake would hide.

## Idea

The read has three answers, not two: *a database*, *not a database*, and *I could not
read it* — and the third one carries the reason.

## Decision

1. **The header read says which of the three it is.** The header's bytes are the
   answer for a file that was read; a failure of the read is its own answer, with the
   reason attached.

2. **"Not a database" and "could not read" produce different records.** The first says
   the file is not a database; the second says the file could not be read, and why. The
   file is kept in both cases — the rule about not deleting is unchanged.

3. **The unreadable case is driven by a path that cannot be read as a file, and that
   could have been deleted.** A symlink to a directory: `inputStream()` on it fails
   ("Is a directory"), and a symlink is removed by unlinking it, whatever it points
   at — so the path was deletable all along, and its survival is the probe's decision
   and not the file system's refusal. A mode is the obvious way to make a file
   unreadable, and it is the wrong way: a mode is a suggestion that a process running
   as root ignores, and a test that depends on the process's privileges is a test that
   quietly stops testing anything on the machines where it matters most.

4. **The record is observable.** The probe's outcome is internal rather than private,
   for the same reason the deletion of the companions is: the reason the probe leaves
   is the record the startup makes, and a record no test can read is a record no change
   can be held to.

## Rationale

**Why the reason is carried.** The record exists for whoever reads it next. "The file
could not be read: Permission denied" is an instruction; "the file does not start with
a database header" is a conclusion the reader has to undo before they can act on it.

**Why the file is still kept.** The change is about what the record says, not about
what the probe does. Deleting a file the process cannot read is the one outcome the
rule forbids absolutely, and it stays forbidden.

**Why the path has to be deletable.** A path the operating system refuses to delete
keeps the probe's decision and the file system's refusal indistinguishable: the file
survives either way, and the test proves nothing about the probe. The path has to be
one the probe *could* have deleted, so that its survival is the probe's choice.

**Why a symlink and not a mode.** The rule under test is the branch a read failure
takes, and the symlink reaches that branch on every machine. A mode reaches it only on
a machine that honours the mode, which is not every machine, and which is not a
property of the code.

## Consequences

- The record for a file that is not a database is unchanged: "the file does not start
  with a database header".
- A file that cannot be read now says so, and says why. Nothing is deleted in either
  case, so the behaviour of the probe itself is unchanged.
- The probe's outcome is internal, so a test can read the record it makes. The test
  asserts the two records apart: a file that was read and is not a database, and a file
  that could not be read. Without that seam the change would have been untestable —
  reverting the read to a boolean would have left every test green, because the file
  would still be kept either way.
- `StaleDbCleanerTest` drives the unreadable case with a symlink to a directory — a path
  that cannot be read as a file and that could have been deleted — and asserts that the
  record says the probe could not read it.
- The header read is now three answers instead of a boolean, and every caller of it has
  to say which answer it got. There is one caller.

## Links

- Spec delta `openspec/changes/acp-client-data-safety/specs/core/database-safety/spec.md`
  — `REQ-DBSAFE-002`, the rule the record is part of
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/database/contract/StaleDbCleaner.kt`
  — `readHeader` and `probe`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/StaleDbCleanerTest.kt`
  — the locked and unreadable cases
- Issue [#993](https://github.com/gazon1/sing/issues/993) — the client this probe
  belongs to
