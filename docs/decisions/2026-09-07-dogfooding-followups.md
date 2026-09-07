---
title: Dogfooding follow-ups — observed during implementation
date: 2026-09-07
status: draft
tags: [dogfooding, followups, technical-debt]
---

# Dogfooding Follow-ups

## Context

Implementation of dogfooding MCP server revealed several areas needing follow-up work.

## Observations

### 1. `DataStoreSessionStore` initialization pattern

`DataStoreSessionStore` uses a `Mutex` + `MutableStateFlow` for lazy one-time `deviceId` initialization. This works but is relatively complex. A simpler alternative: expose `deviceId` as a `StateFlow` initialized eagerly in `init {}` using `kotlinx.coroutines.GlobalScope.launch`, or use a `Lazy` property.

Current trade-off: chosen for correctness (no `runBlocking` in init, no blocking on first read). Acceptable.

### 2. `AdrTools` encoding inconsistency

`AdrTools` uses `kotlin.io.path.Path.readText(Charsets.UTF_8)` for reading and `java.io.File.writeText(Charsets.UTF_8)` for writing. These are technically different APIs but both use UTF-8. Not a bug for desktop/JVM, but inconsistent with the `FileSystem` port pattern used everywhere else. Should be unified in a follow-up.

### 3. `AiUsageViewModel` StateFlow→Flow coercion

`profileRepository.activeProfileId` is a `StateFlow<ProfileId>`. It's used with `flatMapLatest` which expects `Flow`. Koin provides `StateFlowAsFlow` extension automatically, so it works. But this implicit coercion should be documented or made explicit.

### 4. No unit tests for write tools

17 write tools + ADR tools have no dedicated unit tests — only `JvmAiDiGraphTest` which checks DI resolution. Each tool's `execute()` method should have a `commonTest` covering idempotency key, dry-run, and error cases.

### 5. MCP `Main.kt` has no startup error handling

If `Koin` fails to start or the database can't be opened, the MCP server exits silently with no error message to the agent. Should catch and report initialization errors via JSON-RPC `initialize` response.

### 6. `@ComponentScan` in `AiToolsDiModule`

`AiToolsDiModule` uses `@ComponentScan("com.singularity.todo.feature.ai.tools")` to auto-register all `SimpleTool` beans. This is fragile if files are renamed/moved. Better: explicit `intoSet { }` registrations or a naming convention scan.

### 7. Profile-switching invalidates in-memory state

When a user switches profiles at runtime, `ProfileAwareCurrentUser.scopedUserId` updates correctly. But existing `StateFlow` collections in ViewModels (tasks, notes) still hold the old profile's data until `viewModelScope` is recreated. Hot restart or manual refresh is needed.

## Decision

All items above are non-blocking for dogfooding. Estimated total effort: 1-2 days.

## Consequences

Track as separate issues/PRs after initial dogfooding is stable.

## Links

- `DataStoreSessionStore`: `core/auth/SessionStore.kt`
- `AdrTools`: `feature/ai/tools/AdrTools.kt`
- `AiUsageViewModel`: `feature/ai/usage/AiUsageViewModel.kt`
- `AiToolsDiModule`: `core/di/AiToolsDiModule.kt`
- `ProfileAwareCurrentUser`: `feature/profile/ProfileAwareCurrentUser.kt`
