---
title: "Bulk Import Port"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#82](https://github.com/gazon1/sing/issues/82) · OpenSpec change `bulk-import-port` (proposed)

**Found in:** MR-1, `BackupImporter` class KDoc and architecture review.

`BackupImporter` writes directly to DAOs to bypass `assertCanWrite` guards, targeting
`options.targetUserId` without going through repositories. This is documented
technical debt.

**Fix:** Create a `BulkImportPort` interface that takes an explicit `targetUserId: UserId`
and routes writes through repositories. Replace DAO calls in `BackupImporter` with
`BulkImportPort.import(payload, targetUserId)`. Track in `docs/decisions/2026-09-27-write-layer-soundness.md`.

---
