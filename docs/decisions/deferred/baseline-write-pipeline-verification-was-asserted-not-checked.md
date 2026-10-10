---
title: "Baseline Write Pipeline Verification Was Asserted Not Checked"
date: 2000-01-01
status: OPEN
tags: ["deferred", "archive`"]
---

**Status: OPEN**

**Tracked as:** #448

**Found in:** the OpenSpec backlog pass, 2026-10-04, while closing out
`navigation-open-policy` and noticing that `openspec/changes/archive` was empty
while four changes sat active.

`baseline-write-pipeline` is a *baseline* spec — it documents behaviour the system
already has, with a 13-item verification checklist. Every item named a covering
test. Checking the names against the suite: `FakeRepositoryFidelityTest` contains
no reference to the outbox, `enqueue` or an affected-row count (all 14 of its tests
are about read isolation and soft-delete), and `EntityMapperCompletenessTest` never
reads a `@Query` at all — it compares mapper field access against a hand-maintained
table. **Five attributions were wrong**, and REQ-WP-050's premise had quietly
stopped holding: its `FIELD_ALLOWLIST` is empty and `BackupImporter` appears in
neither the entity table nor the mapper table.

The checklist is now rewritten with two states instead of one — `verified` with the
asserting test quoted, and `not covered` with the gap named. Seven requirements
have no assertion at all: REQ-WP-002 (affected-row return values), 012 (narrow
updates re-read before enqueueing), 020/021 (outgoing-link persistence and
atomicity), 030 (note tool routing — the existing test would also pass against a
DAO bypass using the same id), 031 (canonical HTML storage), 041 (id-only writes
rely on the DAO layer).

**Already ruled out:** not an OpenSpec process problem. The change is correctly left
unarchived — a baseline spec whose verification is 6/13 is not finished work, and
ticking the remaining boxes without assertions would recreate the defect.

**Try next:** close them in `ScopedWriteQueryIsolationTest` (new, 2026-10-04 — it
already owns the SQL-level write invariants and has an allowlist that requires a
reason per entry) rather than in a new file. REQ-WP-002 and REQ-WP-041 are the two
worth doing first: a DAO mutation that returns zero rows silently is exactly the
shape of defect that survives every other gate in this repo.

---
