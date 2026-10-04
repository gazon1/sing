# Tasks — backup-include-remaining-tables

## Phase 0 — decisions before code

- [ ] **Decide the orphan policy.** A dependent row whose parent is absent from
      the backup: drop-and-report, create a placeholder parent, or fail the
      restore. Recorded in design.md. Blocks the importer work.
- [ ] **Decide the profile question**, or split it out. design.md recommends
      shipping the seven row data sets now and treating profile restore as its own
      change. Confirm or overrule before Phase 1.
- [ ] **Verify restore is a replacement, not a merge.** design.md's rollback
      analysis depends on this. If restore merges into an existing database, the
      ordering requirement becomes a transaction boundary and the proposal needs
      rewriting, not just reordering.

## Phase 1 — payload

- [ ] **shared/**: add the seven data sets to the backup payload, each with an
      explicit dependency on the sets it references.
      Test: a payload round-trip test asserting row counts per data set, failing
      if any set is absent from the payload.
- [ ] **shared/**: bump the backup format version once for all seven sets.
      Test: a test that reads a payload written by the previous version and
      reports the sets it cannot restore, rather than failing.
- [ ] **shared/**: record the active profile in the manifest.
      Test: manifest round-trip preserves the active-profile field.
      *Blocked on the Phase 0 profile decision.*

## Phase 2 — export and import

- [ ] **shared/**: export reads each data set and counts it in the manifest.
      Test: export-then-import round trip per data set.
- [ ] **shared/**: import inserts in the parent-before-child order in design.md.
      Test: a test that asserts ordering by observing insert order, not by
      asserting the final state — a merge-style import would pass the final-state
      assertion while still being wrong.
- [ ] **shared/**: import drops and reports rows whose parent is absent.
      Test: a backup with a dangling child restores successfully and reports the
      dropped count.

## Phase 3 — the report

- [ ] **shared/** + **androidApp/** + **desktopApp/**: surface the restore report
      to the user. Test: a view-model test asserting the report is produced on
      success, on partial restore, and on failure.
- [ ] **shared/**: restore with an empty backup reports zero for every set rather
      than reporting nothing.
      Test: empty-payload import asserts the report shape.

## Phase 4 — verification

- [ ] **Maestro/**: extend the backup round-trip flow to assert a second data set.
      One hand-written flow covers one data set; that does not scale to eight, so
      at least one data set that is *not* the obvious one is covered here.

---

**Verification command:**

```bash
./gradlew :shared:jvmTest --tests "*Backup*"
```

**Blocked tasks** (Phase 0) are decisions, not code. They are listed first on
purpose: the rest of the plan is correct under any answer, but the orphan policy
changes the import's structure and the profile question changes whether profiles
are in this change at all.
