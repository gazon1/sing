---
title: "Test suite tag defaults and Khorikov testing principles"
status: accepted
date: 2026-09-26
authors: ZCode Agent
deciders: Singularity Developer
tags: [testing, junit, gradle, detekt]
supersedes: 2026-09-26-production-readiness-findings
---

# Test Suite Tag Defaults and Testing Principles

## Context

Two issues were found alongside the OOM fix:

1. `mcp-server/build.gradle.kts` used `includeTags("fast")` as the default tag filter — the same
   bug that was fixed in `shared` and `desktopApp` (per `2026-09-26-production-readiness-findings`),
   but the fix was not applied to `mcp-server`. This silently skips `McpServerEndToEndTest`
   (the only `@Tag("slow")` test in that module) on default `./gradlew :mcp-server:test`.

2. `AndroidPomodoroTimerTest` runs 9 Robolectric tests without any `@Tag`. Robolectric tests are
   Component-tier (slow by design — they boot a simulated Android framework). Running them in the
   default fast suite increases heap pressure and wall-clock time for every smoke run.

Additionally, the project's existing testing practices were formalised against Khorikov's four
attributes of good unit tests and the AAA pattern.

## Decisions

### D1: Fix `mcp-server` default tag filter

Changed `includeTags("fast")` → `excludeTags("slow")` in `mcp-server/build.gradle.kts`, matching
the convention in `shared` and `desktopApp`:

```kotlin
val tags = (project.findProperty("test.tags") as String?)
    ?.split(",")?.orEmpty() ?: emptyList()
if (tags.isNotEmpty()) {
    includeTags(*tags.toTypedArray())
} else {
    excludeTags("slow")  // was: includeTags("fast")
}
```

Now `McpServerEndToEndTest` is included by default (it has no tag, so it is not "slow") and
runs as a smoke test on `./gradlew :mcp-server:test`.

### D2: Tag `AndroidPomodoroTimerTest` as `@Tag("slow")`

Added `@Tag("slow")` to the class declaration. This moves all 9 Robolectric tests out of the
default fast suite. They are still run via `./gradlew :shared:testAndroidHostTest` or when
`-Ptest.tags=fast,slow` is passed.

### D3: Document Khorikov testing principles as project conventions

The following principles are now recorded as project-wide conventions for all test authors:

**AAA structure (Arrange / Act / Assert):**
Every `@Test` should have three clearly separated sections. If the Act section has more than one
statement, the SUT API likely needs encapsulation. Name the system under test `sut`.

**Four attributes of a good unit test:**
1. Bug protection — catches real regressions with minimal false positives
2. Refactoring resilience — does not break when implementation details change (only when observable behaviour changes)
3. Fast feedback — completes in milliseconds
4. Maintainability — easy to read and modify

**State testing vs sequence testing:**
For VM tests, use `MutableStateFlow.value` assertions, never Turbine-style `awaitItem()`. One
behaviour per `@Test`. If a VM has 3 states, write 3 separate tests.

**Fakes and stubs (CQS — Command Query Separation):**
- Queries (methods returning data, no side effects): use **fakes/stubs** (`FakeRepositories`, `FakeClock`)
- Commands (methods with side effects): verify with **mocks** only for OUTGOING interactions
  (DB writes, analytics, network). Never mock incoming data or internal state.

**Private method testing:**
Tests must not depend on private methods. Test the public surface only; private behaviour is
verified indirectly through observable outcomes.

**Testing pyramid:**
- Unit tier (JVM, fakes): fast, no platform dependencies. Default suite.
- Component tier (Robolectric, instrumented, screenshot): slow, boots platform. Must be
  tagged `@Tag("slow")` and excluded from the default suite.

## Consequences

- `McpServerEndToEndTest` runs on default `./gradlew :mcp-server:test`
- `AndroidPomodoroTimerTest` is excluded from the default suite, reducing fast-suite heap pressure
- All future tests that boot a platform (Robolectric, Android instrumented, screenshot) must be
  annotated `@Tag("slow")`
- All unit tests follow AAA structure, use `sut` naming, and use fakes for state assertions
- MockK is used **only** for verifying outgoing command interactions (DB writes, analytics, network).
  For state testing (queries), always use `Fake*` from `test/fakes/`
- Do not use Turbine `awaitItem()` for VM state testing; use `MutableStateFlow.value` assertions

## Links

- ADR: `2026-09-26-production-readiness-findings` (original audit; superseded by this entry for tag-defaults)
- ADR: `2026-09-25-test-jvm-heap-default` (sibling ADR for heap configuration)
- Shared config: `shared/build.gradle.kts`, `desktopApp/build.gradle.kts`, `mcp-server/build.gradle.kts`
- Test file: `shared/src/androidHostTest/kotlin/com/singularity/todo/feature/pomodoro/AndroidPomodoroTimerTest.kt`
- Test helpers: `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
- AGENTS.md ban-list (MockK rule)
