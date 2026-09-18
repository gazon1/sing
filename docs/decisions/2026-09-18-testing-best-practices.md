---
title: "Testing best practices — Tier 1 infrastructure, canonical VM pattern, Fake over mocks"
date: 2026-09-18
tags: [testing, vm, kotlin-test, coroutines]
---

## Context

Tests across the project had accumulated multiple patterns that made them brittle, slow, or tied to implementation details:

1. **`delay(N)` as synchronization** — `runBlocking { delay(500) }` or `testScope.runCurrent()` to wait for async work. This is a time-based hack: too fast → flaky, too slow → slow tests.

2. **`org.junit.*`** — The project migrated to `kotlin.test` as the test framework, but several files still used `org.junit.Assert.*`, `org.junit.Test`, `assertTrue(true)` placeholders.

3. **`runBlocking` in production** — Several VMs used `runBlocking { ... }` at construction or in init blocks, making them impossible to test with `runTest`.

4. **Inline mock repositories** — Test files had private `RecordingBackupRepository`, `InMemoryTaskStore` etc. inline, duplicating logic and violating the "Fake over mocks" rule.

5. **No `Clock` injection** — `Clock.System.now()` hardcoded in VMs, making time-dependent tests non-deterministic.

6. **`viewModelScope` in VMs** — Hardcoded `viewModelScope` makes VM testing impossible without Robolectric or complex WorkManager mocking.

7. **No `IdGenerator` abstraction** — `nextId()` / `UUID.randomUUID()` used directly, breaking deterministic ID assertions.

8. **`stateIn(scope, WhileSubscribed(5000), ...)`** — While this is good for UI, it makes tests wait 5s before the flow starts emitting, slowing tests.

## Decision

### Tier 1 test infrastructure

1. **Fakes in `test/fakes/`** — All test doubles live in `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/`:
   - `FakeClock` — deterministic `Clock` implementation with `advance(Duration)`, `setNow(Instant)`, `today(TimeZone)`
   - `FakeIdGenerator` (alias for `SequenceIdGenerator`) — predictable "prefix-N" IDs
   - `FakeRepositories` — `FakeTaskRepository`, `FakeNotesRepository`, `FakeProjectsRepository`, `FakeTagsRepository`, `FakeSettingsRepository`, `FakeBackupRepository` with recording fields and configurable results
   - `CommonFakes.kt` — `testTask()`, `testNote()`, `testProject()` builder functions with `overrides` lambda
   - `test/helpers/TestAssertions.kt` (commonTest) — `StateFlow.assertIs<reified T>()` for kotlin.test
   - `test/helpers/TestVmInfrastructure.kt` (jvmTest) — `TestVmContext`, `testVmContext()`, `runAndWait()`

2. **`FakeClock`** lives in `commonMain` because `Clock` is a stdlib interface. `testTask()`, `testNote()`, `testProject()` also in `commonMain` since they produce plain data classes.

3. **`TestVmContext`** and `actAndAwait`-equivalent `runAndWait()` live in `jvmTest` because they require `kotlinx.coroutines.test.TestScope` and `advanceUntilIdle()`.

4. **`advanceUntilIdle()`** is an extension on `TestScope`, not a standalone function. Use `scope.advanceUntilIdle()` inside a suspend function.

### VM testing canonical pattern

Canonical VM constructor:
```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: CoroutineScope,      // injected, NOT viewModelScope
    private val clock: Clock,               // injected, NOT Clock.System
) : ViewModel {

    private val _state = MutableStateFlow(MyState())
    val state: StateFlow<MyState> = _state.asStateFlow()

    init {
        // Use scope, NOT viewModelScope
        scope.launch {
            deps.repository.flow.collect { items ->
                _state.update { it.copy(items = items) }
            }
        }
    }
}

// Secondary constructor for Koin DI
class MyViewModel(
    private val deps: MyDeps,
    scope: CoroutineScope,
    clock: Clock,
) : MyViewModel(deps, scope, clock)

// Koin module
val vmModule = module {
    viewModelOf(::MyViewModel)
}
```

Test:
```kotlin
@Test
fun `load succeeds`() = runTest {
    val repo = FakeTaskRepository()
    val clock = FakeClock(Instant.fromEpochMilliseconds(0))
    val vm = MyViewModel(MyDeps(repo), backgroundScope, clock)
    val ctx = testVmContext(vm, MyViewModel::state, this)

    ctx.act { vm.onIntent(MyIntent.Load) }
    ctx.assertIs<MyState.Loaded>()
}
```

### What we forbid

- **`delay(N)`** in tests — use `runAndWait { }` or `advanceUntilIdle()` instead
- **`assertTrue(true)`** — delete or replace with real assertions
- **`assertTrue(loading = true)`** at the start of a test — state starts at loading by definition
- **`viewModelScope`** in VM code — inject `CoroutineScope` instead
- **`Clock.System.now()`** in production — inject `Clock`
- **`nextId()` / `UUID.randomUUID()`** directly — inject `IdGenerator`
- **`runBlocking`** in VMs or production code — use `MutableStateFlow` + `scope.launch`
- **Inline test doubles** — add to `test/fakes/` instead

### What we always do

- `MutableStateFlow` (NOT `stateIn`) in VMs — simpler to test, no `WhileSubscribed` timeout
- Pass `CoroutineScope` as constructor parameter, not `viewModelScope`
- Pass `Clock` as constructor parameter, use `FakeClock` in tests
- Pass `IdGenerator` as constructor parameter, use `SequenceIdGenerator` in tests
- Use `testTask()`, `testNote()`, `testProject()` for fixture creation
- Use `kotlin.test.*` (NOT `org.junit.*`)
- Use `kotlinx.coroutines.test.runTest` (NOT `runBlocking`)

## Rationale

**Why not `stateIn` in VMs?** `stateIn(scope, WhileSubscribed(5000), initial)` creates a `SharedFlow` that waits 5 seconds before emitting if no subscribers. In tests, there are no UI subscribers, so every VM with `stateIn` starts by waiting 5 seconds. `MutableStateFlow` emits immediately, no timeout.

**Why not `viewModelScope`?** It is provided by the Android framework. In JVM tests, there is no `viewModelScope`. Injecting `CoroutineScope` makes the VM framework-agnostic and trivially testable.

**Why `Fake` over `MockK`?** Mocks encode expectations about implementation: "verify `repo.getTasks()` was called 2 times". This breaks when the implementation changes (e.g., caching layer added) even if behaviour is identical. Fakes test behaviour: "given repository returns [t1, t2], when load, then state is `Loaded([t1, t2])`". Behaviour is stable across refactorings.

**Why `kotlinx.coroutines.test.runTest` over `runBlocking`?** `runTest` virtualizes time — `advanceUntilIdle()` runs pending coroutines synchronously without real delays. `runBlocking` uses real wall-clock time, making tests slow and flaky.

## Consequences

- **Never** use `delay(N)` in tests — use `scope.advanceUntilIdle()` or `runAndWait { }`
- **Never** use `viewModelScope` in VM code — inject `CoroutineScope` instead
- **Never** use `Clock.System.now()` — inject `Clock` and use `FakeClock` in tests
- **Never** use `UUID.randomUUID()` or `nextId()` directly — inject `IdGenerator` and use `SequenceIdGenerator` in tests
- **Never** use `runBlocking` in production code — use `MutableStateFlow` + `scope.launch { }`
- **Never** use `stateIn` in VMs — use `MutableStateFlow` for testability
- **Never** write inline test doubles — add to `test/fakes/`
- **Never** use `org.junit.*` — use `kotlin.test.*`
- **Never** use `assertTrue(true)` placeholders — delete or write real assertions
- **Always** use `testTask()`, `testNote()`, `testProject()` for fixtures
- **Always** use `kotlinx.coroutines.test.runTest` for VM tests
- **Always** keep `FakeClock` and `FakeIdGenerator` in `commonMain/test/fakes/`
- **Always** keep `TestVmContext` and `runAndWait` in `jvmTest/test/helpers/`

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeClock.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeIdGenerator.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/CommonFakes.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/TestAssertions.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/test/helpers/TestVmInfrastructure.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ids/IdGenerator.kt`
- `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`
- `docs/decisions/2026-09-08-task-detail-critical-fixes.md`
