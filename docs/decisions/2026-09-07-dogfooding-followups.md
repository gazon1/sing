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
This ADR also covers the Phase 1–7 refactoring work completed in `60262ce`.

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

## Phase 1–7 Refactoring (2026-09-08, `60262ce`)

### Phase 1 — Quick wins

- **`DeleteTagUseCase` removed** — VMs call `TagsRepository.delete()` directly. No more pass-through use case.
- **`TaskMutationsUseCase` simplified** — deleted `delete`, `toggle`, `togglePin` (pure repo pass-throughs). Kept `bulkComplete` and `bulkDelete` (atomicity enforcement).
- **`RefineTaskTool` dead code removed** — duplicate `try/catch` block with identical branches deleted.
- **`SearchScreen` `/* TODO */` clicks removed** — replaced with empty lambdas.
- **`BackupViewModel.restore()` no-op removed** — stub method deleted.
- **`IdGenerator` in `SyncEngine`** — `UUID.randomUUID()` replaced with `idGenerator.next()` in two places. `IdGenerator` injected via DI.

### Phase 2 — Either for validation

- **`Either.kt` added** — `Either.Left/Right`, `fold`, `getOrElse`, `map`, `mapError`, `toResult()`.
- **`TasksDomain.validateTitle`** — now returns `Either<AppError.Validation, String>` instead of throwing.
- **`TasksDomain.createInput`** — now returns `Either<AppError.Validation, CreateTaskInput>`.
- **`CreateTaskUseCase`** — uses Either-based validation internally, returns `Result<TaskId>` to caller.
- **`TaskEditorViewModel`** — uses Either instead of `try/catch` for input validation.
- **`TasksDomainTest`** — updated to use `assertIs<Either.Left/Right>` assertions.

### Phase 3 — DSL

- `BackupDsl` with `@DslMarker` already existed in `BackupOptions.kt` — no new DSLs added.
- `SearchFilters`, `SettingsUpdate`, `Profile`, `Prompt` DSLs were aspirational (would require significant new code with unclear ROI at this time).

### Phase 4 — Parallel MutableStateFlow

- Deferred — high risk to public API. The current pattern of separate `_selectedIds`, `_filter`, etc. is verbose but stable.
- A future iteration should unify `NotesViewModel.state` and `TasksViewModel.state` into single derived flows.

### Phase 5 — Koin cleanup

- **`@IntoSet` for AI tools** — Deferred. 30-line `listOf(get<X>()...)` in `AiToolsDiModule` is verbose but readable and type-safe. Moving to `@IntoSet` would require careful migration of the existing `List<Tool<*, *>>` registration.
- **`AiToolsModule.android` / `jvm` byte-identical** — Kept as-is (attempted consolidation failed due to platform import asymmetry). Identical actuals are acceptable Koin patterns.
- **Layer violation in `Mappers.kt`** — `internal` helper functions importing feature types are not a real violation (entities don't expose domain types).

### Phase 6 — Test infrastructure

- **`CommonFakes.kt` added** — `FakeClock` (controllable time for tests), `testTask(...)` fixture with correct `Task` field names.
- **`FakeClock`** — not a `Clock` subtype (can't extend `expect object`), but works as a standalone controllable time source.
- **`SequenceIdGenerator`** — already existed, confirmed in use.

### Phase 7 — Pure-logic tests

New tests added:
- `EitherTest` — 11 tests for `Either`, `fold`, `getOrElse`, `map`, `mapError`, `toResult`.
- `BackupOptionsTest` — 5 tests for DSL builders and `BackupId.fromPath`.
- `BackupFileNamerTest` — 3 tests for `DefaultBackupFileNamer`.
- `IdGeneratorTest` — 5 tests for `UlidIdGenerator` and `SequenceIdGenerator`.
- `TaskMutationsUseCaseTest` — 6 tests for `bulkComplete` and `bulkDelete` (atomicity, failure cases).

Total new tests: **~30**. Existing `BackupDomainTest` (commonTest) already had 9 tests covering `sha256Hex`, `extractUserIdHash`, `buildManifest`, `validateManifest`.

## Remaining Work (non-blocking)

### A. Full `kotlin.time.Instant` UI migration
- 11 deprecation warnings remain in UI display files
- Requires adding `kotlinx.datetime` dependency to UI layer OR creating `DateTimeFormatter` helpers
- Estimated: 1 day, low value

### B. `@IntoSet` for AI tools
- 30-line `listOf(get<X>()...)` could be replaced with `@IntoSet factory`
- Estimated: half-day, moderate value

### C. Parallel `MutableStateFlow` unification in VMs
- NotesViewModel, TasksViewModel, SearchViewModel could derive single `StateFlow`
- High risk to public API — deferred

### D. New DSLs (SearchFilters, SettingsUpdate, Profile, Prompt)
- Would require significant new code
- Low-priority at this stage

## Decision

All Phase 1–7 items that were feasible to complete in one session have been addressed.
Remaining items (A–D) are non-blocking and tracked separately.

## Links

- `60262ce` — Phase 1–7 refactoring commit
- `bb7c270` — fix followups commit
- `DataStoreSessionStore`: `core/auth/SessionStore.kt`
- `ProfileAwareSecureStorage`: `core/security/ProfileAwareSecureStorage.kt`
- `NotesViewModel`: `feature/notes/NotesViewModel.kt` (flatMap fix)
- `Either.kt`: `core/error/Either.kt`
- `CommonFakes.kt`: `test/fakes/CommonFakes.kt`
- `testTask(...)`: `test/fakes/CommonFakes.kt`
