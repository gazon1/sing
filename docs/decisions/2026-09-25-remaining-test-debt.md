---
title: Remaining Test Debt — post JUnit/suite-acceleration audit
status: open
date: 2026-09-25
authors: ZCode Agent
deciders: Singularity Developer
tags: [testing, junit, detekt, epic2]
epic: refactor/test-suite-acceleration
---

# Remaining Test Debt

## Context

After Phase 0–7 of `refactor/test-suite-acceleration`, a follow-up audit identified remaining issues that require either a fix or an ADR decision.

## Fixed in this branch (commit `f8d7a031`)

- `AppVersionGateViewModel` — `viewModelScope.launch` in `onCheckAgain()` and `check()` → replaced with injected `scope.launch`
- `ProjectsViewModelTest:113` — redundant `delay(50)` before `advanceUntilIdle()` → removed
- `SyncRepositoryCoalescingTest:48,68` — `delay(5)` → `runCurrent() + advanceUntilIdle()`

---

## Open Issues

### O1: `Dispatchers.Unconfined` in `NotesListViewModel` (3 sites)

**File:** `shared/src/commonMain/kotlin/com/.../notes/presentation/viewmodel/NotesListViewModel.kt:172,185,194`

These are dispatchers used for `launch` calls inside `init {}` blocks. The canonical approach is to accept a `CoroutineDispatcher` as a parameter and use that instead.

```kotlin
// Current (hardcoded Unconfined)
private fun loadNotes() {
    CoroutineScope(Dispatchers.Unconfined).launch {
        repository.observe().collect { ... }
    }
}

// Better (inject dispatcher)
class NotesListViewModel(
    private val repository: NotesRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val scope: CoroutineScope,
) {
    private fun loadNotes() {
        scope.launch(dispatcher) { ... }
    }
}
```

**Decision needed:** Either migrate to injected dispatcher, or document why `Unconfined` is acceptable here.

---

### O2: `runBlocking` in production code (4 real usages)

These are **defensible bridges** — they exist at platform boundaries where suspend→blocking conversion is unavoidable:

| File | Line | Context |
|---|---|---|
| `core/di/KoinBridge.kt` | 17 | `koinBridge { runBlocking { block() } }` — bridging suspend→blocking for Koin scope |
| `core/settings/SettingsDataStoreMigration.kt` | 89 | `runBlocking { run() }` — DataStore migration at startup |
| `core/log/FileLogWriter.kt` | 56 | `runBlocking { writeJob.join() }` — blocking wait for log write |
| `core/log/FileLogWriter.kt` | 63 | `runBlocking { withTimeoutOrNull(...) { flush() } }` — blocking flush |

**Decision:** These 4 usages are acceptable as-is. They are at platform boundaries (Koin initialization, app startup, log file I/O) where coroutine context is not yet or no longer available. The `NoRunBlockingRule` should NOT flag these specific locations.

**Action:** Add `// detekt:allow-run-blocking` comments to these 4 sites to suppress false positives.

---

### O3: Robolectric tests still use JUnit 4 (`@RunWith(AndroidJUnit4::class)`)

**File:** `shared/src/androidHostTest/kotlin/com/.../core/di/AndroidDiGraphTest.kt:27`

```kotlin
@RunWith(AndroidJUnit4::class)
class AndroidDiGraphTest { ... }
```

Robolectric does not yet support JUnit Jupiter natively. Options:

1. **Keep JUnit 4 runner** — acceptable until `robolectric-jupiter` is stable
2. **Migrate to `robolectric-jupiter`** — experimental, requires `RobolectricExtension`

**Decision:** Defer to follow-up issue. JUnit 4 Robolectric tests are `@Tag("slow")` and excluded from fast suite by default.

---

### O4: `NoRunBlockingRule` — service loader registration missing

**Status:** The `NoRunBlockingRule` source exists in `detekt-rules/` but is **not registered** in `META-INF/services/dev.detekt.api.RuleSetProvider`. The ServiceLoader file only has:

```
com.singularity.todo.detekt.NoRunBlockingProvider
com.singularity.todo.detekt.NoViewModelScopeInProductionProvider
com.singularity.todo.detekt.NoRealDelayInTestRuleProvider
```

Note: `NoRunBlockingProvider` is referenced but the actual provider class `NoRunBlockingProvider` was not created — only `NoRunBlockingRule` exists. This means `NoRunBlockingRule` is **not currently active**.

**Action:** Create `NoRunBlockingProvider` class that registers `NoRunBlockingRule`, or remove the reference from the ServiceLoader file. This is a build-system bug, not a runtime blocker.

---

## Summary

| Issue | Severity | Action |
|---|---|---|
| O1: `Dispatchers.Unconfined` in NotesListVM | **medium** | Migrate or document per-follow-up |
| O2: `runBlocking` at platform boundaries | **low** | Suppress with `// detekt:allow-run-blocking` |
| O3: Robolectric JUnit 4 runner | **low** | Defer to `robolectric-jupiter` follow-up |
| O4: `NoRunBlockingProvider` missing | **medium** | Create provider class or remove stale reference |

## Links

- ADR: `2026-09-25-test-standards-comprehensive`
- ADR: `2026-09-25-detekt-test-rules`
