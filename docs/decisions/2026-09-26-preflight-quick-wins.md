---
title: Pre-flight Quick Wins — techdebt roadmap phase 0
date: 2026-09-26
status: accepted
tags: [tech-debt, detekt, sync, auth, preflight]
epic: refactor/techdebt-preflight
---

# Pre-flight Quick Wins

## Context

First phase of the tech-debt roadmap (`refactor/techdebt-preflight` worktree,
branched from `main` @ `90c8c3bb`). Seven quick-win items were planned (Q1–Q7);
re-verification against current HEAD showed three had already landed via
parallel work (MR-6/MR-6c/MR-6d MVI migration wave):

| Item | Plan | Outcome |
|---|---|---|
| Q1 `NoRunBlockingProvider` missing | create it | **Already done** — provider exists as inner class of `NoRunBlockingRule.kt:47`; the real gap was detekt.yml activation (see Decision 1) |
| Q2 allow-run-blocking markers | add markers | Done differently — see Decision 2 |
| Q3 `ConflictResolver.merge()` dead code | delete | Done — Decision 3 |
| Q4 `PomodoroRepository` + `TaskEditorDeps.clock` dead | delete | **Already done** by parallel session (files no longer exist) |
| Q5 `debounce { 500L }` deprecated lambda API | fix | Found in `DraftMviViewModel.kt:105` (not TaskCreateViewModel) — Decision 4 |
| Q6 `OAuthTokenRefresh` real-time clock | inject Clock | Done — Decision 5 |
| Q7 stale ADR test-class refs | amend | Done — Decision 6 |

## Decision

### 1. Activate `no-runblocking` + `no-viewmodel-scope` rulesets in detekt.yml

Both providers were registered via ServiceLoader but their rulesets had **no
config block in `config/detekt/detekt.yml`** — detekt silently skips unconfigured
custom rulesets. Verified by positive control: a temp file with a raw
`runBlocking` produced exactly 1 finding; removed after verification.
`NoViewModelScopeInProduction` reports 0 violations (consistent with codebase scan).

### 2. runBlocking at platform boundaries — refactor, not just markers

Five raw production `runBlocking` sites (ADR O2 listed 4; scan found 2 more in
PlatformModule; `migration.runBlocking()` dot-call does not match the rule):

- `PlatformModule.jvm.kt` + `PlatformModule.android.kt` → rewritten to the
  canonical `koinBridge { }` helper (its KDoc asks for exactly this — "so the
  bridge is greppable"); unused `kotlinx.coroutines.runBlocking` import removed.
- `KoinBridge.kt`, `SettingsDataStoreMigration.runBlocking()`, `FileLogWriter`
  (×2, process-exit drain paths) → precise `@Suppress("NoRunBlocking")` with a
  justification comment on the narrowest enclosing declaration.

### 3. `ConflictResolver.merge()` deleted

Dead code (LWW remote-wins lives in `SyncEngine`; only `checksum()` is used).
Removed `merge()` + the 4 merge tests; **kept the 2 checksum tests** (they cover
surviving code — stricter than the ADR's "delete the file"). Object KDoc updated
to describe the checksum role.

### 4. `debounce { autosaveDebounceMs }` → `debounce(autosaveDebounceMs.milliseconds)`

Deprecated lambda-selector overload in `DraftMviViewModel` replaced with the
Duration overload. (TaskCreateViewModel no longer has its own debounce — the
autosave loop moved into `DraftMviViewModel`.)

### 5. `OAuthTokenRefresh` — injectable clock

`System.currentTimeMillis()` replaced by `nowMs: Long` parameter defaulting to
`Clock.System.now().toEpochMilliseconds()` (`kotlin.time.Clock` — the project
convention under kotlinx-datetime 0.8.0; see retro findings). Test rewritten to
a fixed `NOW` constant, deterministic; `@Tag("slow")` removed; boundary test
documents the strict-inequality semantics (`expired ⟺ now > expiresAt − margin`);
zero-expiresIn case covered.

### 6. ADR hygiene

- `2026-09-26-production-readiness-findings.md`: list corrected 17 → 14 (3
  classes deleted in `b6b426fe`), Amendment section added.
- `2026-09-25-remaining-test-debt.md`: O4 resolved — provider exists; actual
  gap was detekt.yml activation.
- AGENTS.md: removed the nonexistent `:shared:commonTest` Gradle task from the
  test table (commonTest sources run inside `:shared:jvmTest`).

## Consequences

- Detekt now actually enforces runBlocking/vmScope bans in `:shared` (report-only
  until `ignoreFailures = false` in PR 3.3).
- 0 raw production `runBlocking` outside suppressed boundaries; 2 fewer than
  before via `koinBridge` consolidation.
- `OAuthTokenRefreshTest` leaves the slow suite; 13 slow classes remain.
- Detekt reports 21 pre-existing `VmCloseable` findings + ~340 mostly-formatting
  findings — inputs to Epic 2 PR 2.3 and PR 3.3 respectively.

## Links

- Plan: tech-debt roadmap V2.1 (Pre-flight)
- `2026-09-25-remaining-test-debt` (O2, O4)
- `2026-09-25-repository-architecture-gaps` (ConflictResolver)
- `2026-09-26-preflight-retro-findings` — retrospective output
