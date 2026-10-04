# Backup Coverage — Observable Behavior

**capability:** `backup-coverage` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-BC-001

A backup **SHALL** contain every user-owned data set the application stores,
excluding attachment file contents.

**Rationale:** the requirement is written as "every", not as an enumeration,
because an enumeration is what produced the current gap. The list of what is
excluded is closed and short; everything else is included by default.

**Excluded by design:** attachment file contents (references are carried, files
are not) and the profile container (see REQ-BC-010).

#### Scenario: Enumerated data sets survive a round trip
- A backup is taken on a device containing reminders, checklist items, tag
  groups, project-tag group assignments, saved searches, time entries, and saved
  agenda views
- The backup is restored into a fresh install
- Each data set is present with the same row count as at backup time

---

### Requirement: REQ-BC-002

A restore **MUST** report, to the user, which data sets it restored and which it
could not.

**Rationale:** a restore that reports only success is indistinguishable from a
partial restore. The user cannot act on a gap they were never told about, and
this is the property the current omission lacked.

#### Scenario: Partial restore is visible
- A backup contains a data set whose parent row is absent from the same backup
- Restore completes
- The user is told which rows were not restored and how many

---

### Requirement: REQ-BC-003

Restore **MUST** place a data set's rows only after the rows they reference are
present.

**Rationale:** the data sets reference each other. Restoring a dependent before
its reference either fails on a constraint or produces an orphan that outlives
the restore.

#### Scenario: Dependent data set is not orphaned
- A backup contains both a task and checklist items belonging to it
- Restore completes
- Every restored checklist item references a task that exists in the restored
  state

---

### Requirement: REQ-BC-010

A backup that contains profile data **MUST** record which profile was active when
the backup was taken, and restore **MUST** make that profile active afterwards.

**Rationale:** a backup may contain several profiles. Landing the user in an
arbitrary one makes every other restored data set look empty, which is worse
than not restoring them at all. Recording the active profile is what makes the
choice recoverable rather than accidental.

**Blocked:** this requirement is contingent on the product decision recorded in
design.md. If the decision is to require the user to choose, this requirement is
replaced rather than implemented.

#### Scenario: Restore lands in the profile the user left
- A backup is taken while profile A is active
- A second profile B exists and is also present in the backup
- Restore completes
- Profile A is active, and B's data is intact
