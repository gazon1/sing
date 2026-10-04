---
title: "SyncColumns: which fields the client writes back"
date: 2026-10-03
status: accepted
tags: [sync, database, write-path]
---

## Context

[`SyncColumns`][sync-columns] carries six fields:

```
serverVersion  Long     — monotonic version assigned by the server on each push
syncStatus     String   — LOCAL_ONLY | PENDING | SYNCED | ERROR
syncError      String?  — last error message when sync failed
lastSyncedAt   Long?    — epoch millis of last successful push
deviceId       String?  — originating device
hlc            String?  — Hybrid Logical Clock (causal ordering)
```

The domain models (`Task`, `Note`, `Project`, `Tag`, `TagGroup`) expose only
`serverVersion` and `hlc`. The other four fields are populated **by the server**
and are **never written back by any `@Query` in the codebase**.

[sync-columns]: ../shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt

This creates a paradox: every `toEntity()` call passes
`SyncColumns(serverVersion, hlc)` with the other four fields at their Kotlin defaults
(`"LOCAL_ONLY"`, `null`, `null`, `null`). On an upsert that is not a true create
(e.g. editing a task), those defaults silently overwrite whatever the server wrote.

## Decision

**Keep `serverVersion` and `hlc` as client-written live fields.**
The other four fields are server-owned read-only metadata:

| Field | Reason to keep as server-only |
|---|---|
| `syncStatus` | Computed by sync engine based on push/pull outcome |
| `syncError` | Written by sync engine on failure; client should not clobber |
| `lastSyncedAt` | Updated by sync engine; client has no reliable clock |
| `deviceId` | Set once by the server; client identity is not meaningful |

The domain models will continue to carry only `serverVersion` and `hlc`.
`toEntity()` will always construct `SyncColumns(serverVersion = <value>, hlc = <value>)`
without setting the other fields, relying on the server to populate them.

## Consequences

### Positive
- Sync engine retains full control over `syncStatus` / `syncError` / `lastSyncedAt` without rogue client overwrites
- Domain models stay lean — no need to thread `deviceId` through use cases
- No schema change required

### Negative / Known gap
- `TagEntity.icon` was added in v20 but has no domain-model counterpart. It is stored
  but never restored on read (caught by `EntityMapperCompletenessTest`). This is a
  separate ADR candidate: add `icon` to `Tag`, or drop the column. Tracked as a
  Phase 0 retro finding.

## Links
- Write-path primitives: `write-pipeline` skill
- MR 0.4 `NotesRepositoryImpl.update` guard — same read-before-write pattern
- `EntityMapperCompletenessTest` — catches any future mapper asymmetry
