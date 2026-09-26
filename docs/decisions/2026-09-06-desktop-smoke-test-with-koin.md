---
title: "Desktop smoke test: Koin initialization pattern for Compose Multiplatform UI tests"
date: 2026-09-06
tags: [desktop, testing, compose, koin, ui-test]
status: accepted
superseded-by: 2026-09-26-ui-testing-deferred
---

## Context

`AppSmokeTest` in `desktopApp/src/jvmTest/` uses `runDesktopComposeUiTest` to verify the desktop shell renders the sidebar without a window. When Koin is not started, `koinInject()` calls inside `AuthGuard` and various ViewModels throw `KoinApplicationNotStartedException`.

Additionally, `runDesktopComposeUiTest` uses a separate JVM process per test method, so `@Before` (instance-level) initialization is called once per test — but Koin is a process-wide singleton. Tests 2 and 3 would fail with `KoinApplicationAlreadyStartedException` after the first test started Koin.

## Idea

Three options were considered:

1. **Start Koin in `@Before` (instance method)** — fails because tests 2 and 3 see `KoinApplicationAlreadyStartedException` since Koin was started by test 1 in the same process.
2. **Start Koin in `@BeforeClass` (static)** — works but still risks `KoinApplicationAlreadyStartedException` on re-run within the same JVM process. Requires `stopKoin()` before `startKoin()` in `setUp()`.
3. **Use a separate test-specific module** — possible but adds indirection without benefit since the desktop `platformModule()` + `domainModule()` are exactly what `main.kt` uses.

## Decision

Use `@BeforeClass` with `stopKoin()` guard to allow re-run in the same process:

```kotlin
companion object {
    @JvmStatic
    @BeforeClass
    fun setUp() {
        stopKoin()                           // allow re-run in same process
        startKoin {
            modules(
                platformModule(),
                domainModule(),
            )
        }
    }

    @JvmStatic
    @AfterClass
    fun tearDown() {
        stopKoin()                           // clean up for next test class
    }
}
```

The test itself only verifies `DESKTOP_SIDEBAR` test tag exists — avoids navigation tests that trigger Compose Navigation lifecycle issues (`LifecycleRegistry` state machine errors on `runDesktopComposeUiTest`).

## Consequences

- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest`
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest`

## Links

- Compose Multiplatform `ui-test` module: `org.jetbrains.compose.ui:ui-test`
- Koin `startKoin`/`stopKoin`: `org.koin.core.context`
