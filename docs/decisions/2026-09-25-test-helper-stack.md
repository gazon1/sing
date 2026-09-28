---
title: "Test helper stack — kotest assertions, @ParameterizedTest, hand-rolled fakes"
date: 2026-09-25
tags: [testing, quality, kotlin, junit5]
status: accepted
---

> **Superseded in part (2026-09-28):** the "~28 tests using real `delay()`" and the
> baseline entry covering them are stale. The slot suite (45 tests) and
> `DraftMviViewModelTest` (16) were moved to virtual time. Three sites keep real time
> on purpose, each documented in-file: `SyncRepositoryCoalescingTest` (freezes a
> coroutine to prove the coalescing guard), `WriteToolsTest` (waits for a timestamp),
> `AgendaViewModelTest` (the infinite `todayFlow` means the scheduler never drains).
>
> The rule itself was found not to hold: `NoRealDelayInTest` has a `value <= 500`
> cutoff, so it could not have flagged any of the 109 sites — they were `delay(20)`,
> `delay(100)`, `delay(400)`. The threshold is the defect.
> See [2026-09-28-mr1-test-virtualization-retro.md](2026-09-28-mr1-test-virtualization-retro.md).

## Context

After the test-suite audit (2026-09-25) we identified 167 test files / 1158 `@Test` methods with the following problems:
- ~13 tests with zero assertions (no-op smoke tests)
- ~7 tests with weak `assertTrue(x.isNotEmpty)` instead of specific values
- ~28 tests using real `delay()` instead of virtual time
- ~30 tests with multiple acts per test (anti-pattern per Khorikov)
- ~12 tests asserting on error message strings instead of typed errors
- 2 test files with shared mutable state across tests
- 56 Fake* classes with no dedicated test coverage needed
- `@ParameterizedTest` available in `libs.versions.toml` but unused

We needed a minimal, KMP-safe test helper stack.

## Ideas Considered

### Burst (`com.squareup.burst:burst`)
**Rejected.** Burst 2.13.0 requires Kotlin 2.4.0; the project uses Kotlin 2.3.21. Burst is effectively unavailable without a Kotlin upgrade.

### kotlin-faker (`io.github.serpro69:kotlin-faker`)
**Rejected.** JVM-only — cannot be added to `commonTest` (KMP multiplatform source set). Would work only in `jvmTest`.

### Kotest assertions — matchers only (`io.kotest:kotest-assertions-core`)
**Accepted.** Multiplatform-compatible (KMP). Available as `5.9.1`. We use **only** the matchers (`shouldBe`, `shouldNotThrowAny`, `shouldContain`) — not the full Spec styles. Drop-in alongside existing `kotlin.test.Test`.

### `@ParameterizedTest` from JUnit 5 Jupiter
**Accepted.** Already declared in `libs.versions.toml` (`junit-jupiter-params = "5.11.4"`) with `useJUnitPlatform()` configured in `build.gradle.kts`. Unused until now. Use `@EnumSource` and `@MethodSource` for parameterization.

### Hand-rolled test data builders on `kotlin.random.Random`
**Accepted.** For faker-equivalent data (ULIDs, names, paragraphs), hand-rolled builders in `test/helpers/TestData.kt` are KMP-native and have zero external dependencies.

## Decision

### 1. Kotest matchers alongside `kotlin.test.Test`

In `shared/build.gradle.kts`:
```kotlin
commonTest.dependencies {
    implementation(libs.kotest.assertions.core)
}
```

Import via `io.kotest.assertions.shouldBe`, `io.kotest.assertions.shouldNotThrowAny`, `io.kotest.assertions.shouldContain`. No Spec classes, no `behaviorSpec`, no `should`. Existing tests continue using `kotlin.test.*` unchanged.

### 2. `@ParameterizedTest` for repetition

Use `@EnumSource` for enum-based test cases:
```kotlin
@ParameterizedTest
@EnumSource(FilterCase::class)
fun `matchesFilter by enum value`(case: FilterCase) = runTest { ... }
```

Use `@MethodSource` for complex input:
```kotlin
@ParameterizedTest
@MethodSource("calendarDateTestCases")
fun `addMonths handles edge cases`(input: CalendarDateTestCase) = ...
```

### 3. No external faker — hand-rolled builders

```kotlin
// test/helpers/TestData.kt
fun randomUlid(): String = UlidIdGenerator.next()
fun randomParagraph(): String = words(kotlin.random.Random.nextInt(20, 100))
```

### 4. `advanceUntilIdle()` for virtual time in coroutine tests

Replace every `delay(N)` in test files with `advanceUntilIdle()` (for no-debounce) or `testScheduler.advanceTimeBy(N)` (for debounce) after PR-3.1 injects `CoroutineDispatcher` into `FakeProfileAwareCurrentUser`.

### 5. `NoDelayInTests` detekt rule

New rule in `:detekt-rules` catches real `delay()` calls in `*Test.kt` files. Baseline covers existing 28 violations.

## Rationale

- **KMP safety**: all choices work in `commonTest` (multiplatform)
- **No Kotlin version bump**: Burst was the only appealing option that required it
- **Minimal dependency footprint**: one new dep (`kotest-assertions-core`) vs many
- **Incremental adoption**: `kotlin.test.Test` stays, kotest is additive only
- **No new language features**: `@ParameterizedTest` already in the toolchain

## Consequences

- **Always** use `kotest.assertions.shouldBe / shouldNotThrowAny / shouldContain` as matchers alongside `kotlin.test.*` assertions in new tests.
- **Never** add Burst or kotlin-faker to the project.
- **Never** use real `delay()` in test files — use `advanceUntilIdle()` after PR-3.1.
- **Always** parameterize repeating tests with `@ParameterizedTest @EnumSource/@MethodSource` when the SUT is the same.
- `detekt NoDelayInTests` baseline will be generated after PR-2 cleanup.

## Links

- `gradle/libs.versions.toml` — `kotest-assertions = "5.9.1"`, `junit-jupiter-params = "5.11.4"`
- `shared/build.gradle.kts` — `implementation(libs.kotest.assertions.core)`
- PR-1 commit: `b6b426fe` — kotest added, 13 files deleted
- `:detekt-rules/NoDelayInTests.kt` — new rule (PR-2)
