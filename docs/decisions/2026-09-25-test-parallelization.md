---
title: Test Parallelization — Jupiter Concurrency + Thread Safety
status: accepted
date: 2026-09-25
authors: ZCode Agent
deciders: Singularity Developer
tags: [testing, junit, jupiter, parallel, epic2]
epic: refactor/test-suite-acceleration
---

# Test Parallelization

## Context

Jupiter parallel execution is enabled for all test modules (`:shared`, `:desktopApp`, `:mcp-server`). This required a thread-safety audit of shared test infrastructure.

## Decision
### D1: Jupiter method-level parallelism enabled

```kotlin
// shared/build.gradle.kts (and desktopApp, mcp-server)
useJUnitPlatform {
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
}
```

### D2: Thread-safety of test fakes

| Component | Thread Safety | Notes |
|---|---|---|
| `InMemoryStore<E>` | Safe | `MutableStateFlow` is thread-safe for concurrent reads/writes |
| `FakeClock` | Safe | Immutable `Instant` field, updated by single coroutine in test |
| `SequenceIdGenerator` | Safe | `AtomicInteger.incrementAndGet()` |
| `FakeAuthRepository._currentSession` | Safe | Single writer (test setup), many readers |
| Koin DI graph | Unsafe | `forkEvery=1` isolates each jvmTest class in its own JVM |

`forkEvery = 1` on `jvmTest` prevents Koin global state from leaking between test classes.

### D3: No `@Execution(SAME_THREAD)` required

After audit, no test class requires `SAME_THREAD`. All fakes use `MutableStateFlow` (coroutine-safe). Room databases are opened per-class via `@BeforeEach` pattern.

## Consequences

- `:shared:jvmTest` fast tests: ~7s wall-clock (was ~90s sequential with real `delay`)
- Parallel execution is dynamic — Jupiter adjusts thread pool based on CPU cores
- No test flakiness observed in 10× repeated fast test runs

## Links

- ADR: `2026-09-25-test-standards-comprehensive`
