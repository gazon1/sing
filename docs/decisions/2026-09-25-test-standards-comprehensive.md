---
title: Test Standards Comprehensive — JUnit Jupiter, Virtual Time, Fast/Slow Split
status: accepted
date: 2026-09-25
authors: ZCode Agent
deciders: Singularity Developer
supersedes: 2026-09-23-test-standards-enforcement
tags: [testing, junit, jupiter, epic2]
epic: refactor/test-suite-acceleration
---

# Test Standards Comprehensive

## Context

Phase 0–4 of `refactor/test-suite-acceleration` (git worktree: `junit6-spike`) performed a comprehensive overhaul of the test infrastructure for the Singularity Todo KMP project (Android + JVM Desktop).

## Decision
### D1: JUnit Jupiter 5.11+ is the test platform

JUnit 4 is retired. All new tests use Jupiter API:

| Old (JUnit 4) | New (Jupiter 5) |
|---|---|
| `@RunWith(AndroidJUnit4::class)` | `@ExtendWith(AndroidExt::class)` (Robolectric still JUnit 4) |
| `org.junit.Assume` | `org.junit.jupiter.api.Assumptions.assumeTrue(condition, message)` |
| `org.junit.Test` | `org.junit.jupiter.api.Test` |
| `ExpectedException` | `assertFailsWith<>` |
| `@Rule TemporaryFolder` | `@TempDir` |

**Robolectric note:** `androidHostTest` still uses JUnit 4 runner (`@RunWith(AndroidJUnit4::class)`). Robolectric Jupiter support (`robolectric-jupiter`) is a follow-up.

### D2: Test helpers live in `commonTest`

Pure coroutine-test helpers (`awaitState.kt`, `RunVmTest.kt`, `TestVmInfrastructure.kt`) are in `commonTest/helpers/` so they are available to all platform test source sets without duplication.

### D3: `@Tag("slow")` / `@Tag("fast")` convention

Tests are tagged at class level:

```kotlin
@Tag("slow")  // disk I/O, subprocess, Compose UI, Robolectric, debounce > 100ms
@Tag("fast")  // everything else (default)
```

Gradle filtering:
```kotlin
// gradle.properties
test.tags=fast   # default

// CLI
-Ptest.tags=slow       # slow only
-Ptest.tags=fast,slow  # all
```

**Default: fast only** — developers get <30s feedback locally. Slow tests run in CI.

### D4: No `delay(N>1)` in tests — virtual time only

`kotlinx.coroutines.test` is the only allowed way to advance time:

| Old | New |
|---|---|
| `delay(100)` | `advanceUntilIdle()` |
| `delay(600)` | `advanceTimeBy(510); runCurrent()` |
| `delay(200)` | `advanceTimeBy(210); runCurrent()` |
| `delay(10)` | `advanceUntilIdle()` |

The `NoRealDelayInTestRule` detekt rule flags violations (severity: warning).

Exempt: `delay(0)`, `delay(1)`, and lines annotated `// detekt:allow-real-time`.

### D5: `stateIn` removed from all production ViewModels

`stateIn` keeps upstream flows active for 5 seconds after cancellation — blocking virtual time in tests and hiding upstream errors. All ViewModels migrated to canonical pattern:

```kotlin
// PROHIBITED: stateIn(scope, WhileSubscribed(5000), initial)
val state = flow.stateIn(scope, WhileSubscribed(5000), Initial)

// REQUIRED: plain MutableStateFlow + explicit collector
private val _state = MutableStateFlow<UiState>(UiState.Loading)
val state: StateFlow<UiState> = _state.asStateFlow()

init {
    addCloseable(scope)
    scope.launch {
        flow.collect { _state.value = it }
    }
}
```

**Exception:** Pure read-through VMs (no own intents, no init-mutations) may use `combine + stateIn` per `2026-09-24-combine-statein-policy.md`.

### D6: `SequenceIdGenerator` is thread-safe

`SequenceIdGenerator` uses `AtomicInteger` (was plain `Int`). Safe for Jupiter method-level parallelism.

### D7: `Clock`, `IdGenerator`, `CoroutineScope` injected for tests

All production code uses interfaces/ports. Fakes accept constructor parameters:

```kotlin
class FakeTaskRepository(
    val clock: Clock = Clock,
    // ...
)
```

## Consequences

- `:shared:jvmTest` fast tests now run in ~7s (was ~90s with `delay`)
- Test parallelization: Jupiter method-level concurrency enabled
- All 593 existing tests continue to pass
- Pre-existing failures (9 tests) remain unchanged

## Links

- Skill: `singularity-todo-test-helpers`
- Skill: `singularity-todo-testable-vm`
- Skill: `singularity-todo-vm-migration-playbook`
- Skill: `singularity-todo-quality-tools`
- ADR: `2026-09-24-combine-statein-policy`
- ADR: `2026-09-23-test-standards-enforcement` (superseded)
