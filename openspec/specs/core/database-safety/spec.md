# core/database-safety Specification

## Purpose

What the start-up probe may delete, what it does when it cannot be certain that a
file is not the application's database, and what a failed start-up leaves behind.

## Requirements

### Requirement: REQ-DBSAFE-001

The startup probe **SHALL** delete a database file **only** when the file is readable, is a
structurally valid database, and does not carry the application's own bookkeeping marker.

**Rationale:** the probe's job is to recognise a file that provably is not the application's,
so that a leftover from an older layout can be cleaned up. "Provably" is the operative word.
Any other outcome is a doubt, and a doubt is not evidence.

#### Scenario: A database that is not the application's is cleaned up
- The file is a valid database and carries no application bookkeeping marker
- The probe removes the file and its companion files
- The application recreates the database with the current schema

#### Scenario: The application's own database is left alone
- The file is a valid database and carries the application's bookkeeping marker
- The probe leaves the file untouched
- The application's data is intact

---

### Requirement: REQ-DBSAFE-002

When the probe cannot determine with certainty that a file is not the application's database,
it **SHALL** leave the file and its contents **UNCHANGED**, and **SHALL NOT** treat deletion
as a resolution.

**Rationale:** a corrupted file may be the user's only copy of their data. A locked file is
the user's data, busy. An empty file is a file somebody's process is about to write. The
cost of leaving a stale file is one confusing startup; the cost of deleting the user's data
is the user's data.

#### Scenario: A corrupted file is kept
- The file is not a valid database
- The probe leaves the file byte-for-byte as it found it
- The startup records what it saw instead of deleting anything

#### Scenario: A locked file is kept
- The database is held by another process
- The probe leaves the file and its companion files untouched
- The startup records that the database was busy

#### Scenario: An empty file is kept
- The file has no content
- The probe leaves it in place
- The startup does not treat emptiness as proof of anything

#### Scenario: The companion files survive with the database
- The probe did not delete the database file
- The companion files are still present
- Nothing is removed that the probe did not remove for a stated reason

---

### Requirement: REQ-DBSAFE-003

A startup failure **SHALL** leave the application in a defined state: no live dependency
container, no open database handle, and no half-started process, and the caller **SHALL**
receive a diagnostic.

**Rationale:** a process that failed halfway is a process whose failure mode depends on
timing. A tool server that is running with a broken container answers some requests and
refuses others, which is worse than a tool server that is simply not there.

#### Scenario: The tool server fails to start
- The startup fails
- No container is left running and no database handle is left open
- The caller receives a diagnostic that says what happened
