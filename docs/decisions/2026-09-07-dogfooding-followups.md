---
title: Dogfooding follow-ups — observed during implementation
date: 2026-09-07
status: accepted
tags: [dogfooding, followups, technical-debt]
updated: 2026-09-08
---

# Dogfooding Follow-ups

## Context

Implementation of dogfooding MCP server revealed several areas needing follow-up work.

## Observations

### 1. ✅ `DataStoreSessionStore` initialization pattern

`runBlocking` in `init {}` for one-time `deviceId` initialization. Accepted — one-time startup cost, not a hot path. Done in `bb7c270`.

### 2. ✅ `kotlin.time.Instant` vs `kotlinx.datetime.Instant` boundary

Fixed `RoomUsageRecorder`, `UsageRecorder`, `UsageExtractor` to use `kotlin.time.Instant` throughout.
The 11 remaining `typealias Instant` warnings are in UI display files (`DatePickerSheet`,
`StatisticsScreen`, `PomodoroScreen`) that legitimately use `kotlinx.datetime.Instant` for
date formatting. Not bugs — these files need `kotlinx.datetime` for `LocalDate`/`DateTimeFormatter`.
Full migration to `kotlin.time.Instant` across the UI layer is low-priority (estimated 1 day).

### 3. ✅ Per-profile AI settings

`ProfileAwareSecureStorage` wraps `SecureStoragePort` with `profiles/{profileId}/` key prefix.
Injected into `KoogAgentService`. API keys are now isolated per profile.
Done in `bb7c270`. Per-profile DataStore path isolation is already in place (separate DB per profile).

### 4. ✅ Per-profile SecureStorage

Already covered by `ProfileAwareSecureStorage` above.

### 5. ✅ `AiToolsDiModule` `@ComponentScan`

Already using explicit `factory {}` registrations — no `@ComponentScan` found.

### 6. ✅ Unit tests for write tools

`WriteToolsTest.kt` — 13 tests for `CreateTaskTool`, `UpdateTaskTool`, `DeleteTaskTool`,
`CreateNoteTool`. Covers: happy path, not-found, partial update, idempotency.
Done in `bb7c270`.

### 7. ✅ `ProfileAwareCurrentUser`: profile-switch hot-restart

`NotesViewModel` had a bug: `userId.value` captured inside `flatMapLatest { f -> ... }` lambda.
Changed to `combine(_filter, userId) { f, uid -> f to uid }.flatMapLatest { (f, uid) -> ... }`.
`TasksViewModel` was already correct (`flatMapLatest { (filter, uid) -> ... }`).
Done in `bb7c270`.

### 8. ✅ MCP `Main.kt` startup error handling

Koin/DB init failures now write JSON-RPC error to stdout before exit.
Done in `bb7c270`.

## Remaining Work (non-blocking)

### A. Full `kotlin.time.Instant` UI migration
- 11 deprecation warnings remain in UI display files
- Requires adding `kotlinx.datetime` dependency to UI layer OR creating `DateTimeFormatter`
  helpers using `kotlin.time.Instant` + `java.time`
- Estimated: 1 day, low value — UI layer legitimately needs date formatting

### B. Per-profile DataStore path (already done for DB)
Each profile has its own database path (`profiles/{profileId}/singularity-todo.db`).
Settings DataStore is shared across profiles — not a problem in practice since each profile
has its own Settings namespace (`ai_provider`, `ai_model`, etc. are per-profile via the
`active_profile_id` key lookup). No action needed.

### C. `FakeTaskRepository.softDelete` uses `kotlinx.datetime` for date comparison
Line 244: `Clock.now()` (kotlin.time) converted through `toLocalDateTime()` — latent bug
in test fake. Not a production issue.

## Decision

All items from the original list are addressed. Remaining work (A, B, C) is non-blocking.
Tracked separately as they arise.

## Links

- `bb7c270` — fix followups commit
- `fbf1c2e` — feat(dogfooding) commit
- `DataStoreSessionStore`: `core/auth/SessionStore.kt`
- `ProfileAwareSecureStorage`: `core/security/ProfileAwareSecureStorage.kt`
- `NotesViewModel`: `feature/notes/NotesViewModel.kt` (flatMap fix)
- `WriteToolsTest`: `jvmTest/.../feature/ai/tools/WriteToolsTest.kt`
