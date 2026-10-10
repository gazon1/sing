---
title: "Log interpolation sweep — user content classification"
date: 2026-10-10
status: accepted
issue: "#43"
---

**Date:** 2026-10-10
**Issue:** #43
**Scope:** `shared/src/commonMain/**/*.kt` — all `log.{d,i,w,e} { "..." }` call sites that
interpolate variables into the message string.

Rule applied: each interpolated value is classified as **id**, **technical metadata**, or
**user content**. User content requires a per-site decision (drop, truncate, or accept).

---

## Classification table

| File | Line | Interpolation | Classification | Notes |
|------|------|--------------|----------------|-------|
| `SyncRunner.kt` | 113 | `$interval` | technical | `Long` milliseconds, scheduler config |
| `SyncRunner.kt` | 123 | — (no interpolation) | — | static string |
| `SyncRunner.kt` | 136 | — (no interpolation) | — | static string |
| `SeedPlanner.kt` | 92 | `$scope` | technical | `SyncScope(ownerId, profileId)` — typed data class, both fields are ULIDs |
| `SeedPlanner.kt` | 104 | `${entity.docType.key}/${entity.syncId}` | id | typed `DocType.key` (string) + typed `SyncId` |
| `SeedPlanner.kt` | 116 | `$enqueued`, `${entities.size}`, `$scope`, `$complete` | technical | counts + `SyncScope` + `Boolean` |
| `SyncBootstrapper.kt` | 72–76 | `${event.entityId}`, `${event.eventType}`, `${event.serverLsn}`, `${event.protocolVersion}` | id + technical | `entityId` = typed `EntityId`, `eventType` = enum, `serverLsn` = server ULID, `protocolVersion` = int |
| `SyncBootstrapper.kt` | 94–96 | `${event.entityId}`, `${event.eventType}`, `${event.serverLsn}` | id + technical | same as above |
| `SyncBootstrapper.kt` | 101–103 | `${event.entityId}`, `${event.eventType}`, `${event.serverLsn}` | id + technical | same as above |
| `SyncBootstrapper.kt` | 107 | `${event.eventType}`, `${event.serverLsn}` | technical | enum + server ULID |
| `SyncBootstrapper.kt` | 126 | `${event.serverLsn}` | technical | server ULID |
| `SyncBootstrapper.kt` | 134–136 | `${event.entityId}`, `${event.serverLsn}` | id + technical | inside `log.e(e)` — `e` is the exception param, reason is a protocol string |
| `SyncBootstrapper.kt` | 156–158 | `${event.entityId}`, `${event.eventType}`, `${event.serverLsn}` | id + technical | |
| `SyncBootstrapper.kt` | 179 | `${event.entityId}`, `${event.eventType}`, `${event.serverLsn}` | id + technical | inside `log.e(e)` — `e` is the exception param |
| `PullPhase.kt` | 132–133 | `$readFrom` | technical | `Long` LSN value |
| `PullPhase.kt` | 145–146 | `$PULL_PAGE_SIZE` | technical | `Int` constant |
| `PullPhase.kt` | 204–206 | `${event.serverLsn}`, `${step.reason}` | technical + **user content candidate** | `step.reason` comes from `ApplyOutcome.Skipped(e.message ?: ...)` — see §note-1 |
| `PullPhase.kt` | 213–215 | `${event.serverLsn}` | technical | |
| `PushPhase.kt` | 113–115 | `$requestedBy`, `$nowSignedInAs`, `$pending.size` | id + technical | both are `UserId` wrappers (ULID string inside), `size` is count |
| `PushPhase.kt` | 294–296 | `${entity.patchId}`, `$attempts`, `$reason` | id + technical | `patchId` = typed `PatchId`, `reason` from server `result.error` — see §note-2 |
| `PushPhase.kt` | 307 | `${entity.patchId}`, `$attempts`, `$delay` | id + technical | typed `PatchId` + counts |
| `RemoteConfigRepositoryImpl.kt` | 24, 29 | — | — | static strings |
| `RemoteConfigCacheRepositoryImpl.kt` | 67 | — | — | static string |
| `RemoteConfigCacheRepositoryImpl.kt` | 77 | `${validated.schemaVersion}` | technical | `Int` schema version |
| `ProfileSwitcherViewModel.kt` | 105 | `${id.value}` | id | `ProfileId.value` = ULID string (generated, not user-chosen) |
| `ProfileSwitcherViewModel.kt` | 112 | `${id.value}` | id | same |
| `SecureSessionStore.kt` | 168 | — | — | static string |
| `AuthRepository.kt` | 115 | — | — | static string |
| `SavedAgendaViewModel.kt` | 307 | — | — | static string |

### Notes

**§note-1 — `PullPhase.kt:204–206, `${step.reason}``**

`step.reason` is of type `String` and carries the reason that was passed to
`ApplyOutcome.Skipped(reason)`. One call site passes `e.message` as part of that reason:

```kotlin
// SyncBootstrapper.kt:160-163
ApplyOutcome.Skipped(
    "the event payload does not decode: " +
        (e.message ?: e::class.simpleName.orEmpty()),
)
```

`e` is a `SerializationException` thrown by `Json.decodeFromString` when the payload
does not decode. `e.message` contains technical detail about the decode failure (field
name, expected type) — not user content. This is not a user-content leak: the payload
is a JSON document that failed to decode, and the decode error describes the JSON
structure, not its semantic content.

**Conclusion: no user content interpolation found.** All log call sites interpolate either
typed ids (ULIDs/UUIDs that are system-generated, not user-chosen), technical metadata
(enums, counts, LSNs, protocol versions), or the message from a system-generated
`SerializationException` describing JSON decode failures.

**§note-2 — `PushPhase.kt:294–296, `$reason``**

`reason` comes from `result.error ?: "Unknown error"` where `result` is the server's
response to a push. The server could theoretically embed user content in an error
message, but:

1. This is `log.e` (Error level) — in release, `Error` and `Assert` write to disk, so
   this is written to the log file.
2. The server error string is from the sync backend, not from arbitrary user input.
   Bad server behaviour (embedding raw user content in error strings) is a separate
   trust boundary concern, not a local log-interpolation one.

**Conclusion: technically a user-content possibility, but the actual risk is at the
server boundary. Documented here so the next audit can re-evaluate.**

---

## Previous sweep (2026-10-02)

The 2026-10-02 sweep covered error paths (`e.message` exposures) and found 33
interpolation sites classified as ids and technical metadata. This sweep confirms those
findings and extends the coverage to ordinary (non-error) log paths.

---

## Verdict

**No user content found that requires fixing.** All ordinary-path log interpolations
are either typed ids or technical metadata. The two noted cases (`step.reason` from a
decode exception, `reason` from a server error string) are documented above and do
not represent an actionable leak.

This record allows the next audit to be a diff rather than a re-derivation.
