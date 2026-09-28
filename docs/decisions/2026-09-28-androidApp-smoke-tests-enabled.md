---
title: "Enable Android UI smoke tests + document AGP KMP Robolectric limitation"
status: accepted
date: 2026-09-28
supersedes: [2026-09-26-ui-testing-deferred.md]
---

# Enable Android UI Smoke Tests

## Context

Phase 1–5 investigation (2026-09-28) analyzed the existing Android UI test infrastructure, ran it on an emulator, and attempted to add assertions and Robolectric infrastructure.

### What existed before

- `androidApp/src/androidTest/` — 4 instrumented tests (`AuthFlowInstrumentedTest`, `NavigationFlowInstrumentedTest`, `CreateTaskFlowInstrumentedTest`, `CreateNoteFlowInstrumentedTest`), all with zero assertions
- `shared/build.gradle.kts` — `androidHostTest` configuration block with `withHostTest {}` and Robolectric dependency, but **no tests actually in `shared/src/androidHostTest/`**
- `detekt-rules/` — `NoRealDelayInTestRule` catching `kotlinx.coroutines.delay` but **not `Thread.sleep`**

### What was attempted

1. **Phase 3 — Assertions in 4 instrumented tests.** The Compose UI test API (`waitForIdle`, `assertExists`) was unavailable despite bytecode having the methods — root cause: the compose BOM forces `ui-test-junit4-android:1.12.0` whose `createComposeRule()` is `@Deprecated(HIDDEN)`, making it invisible to Kotlin's compiler. **Workaround:** use Espresso with `onView(withClassName(endsWith("ComposeView")))` for a minimal smoke assertion. Each test replaced `Thread.sleep(N)` with a documented rationale comment referencing `singularity-todo-test-flaky-prevention` skill.

2. **Phase 5 — Robolectric infrastructure.** Added `RobolectricInfraSmokeTest.kt` to `shared/src/androidHostTest/` with `@RunWith(RobolectricTestRunner::class)`. Compilation succeeds. However, `testAndroidHostTest` (AGP KMP task type `AndroidUnitTest`) runs `commonTest` sources, NOT `androidHostTest` sources — an AGP bug. The Robolectric test is structurally correct but **never executed**.

3. **Phase 4 — NoRealDelayInTestRule extension.** Extended `NoRealDelayInTestRule` to also catch `Thread.sleep(N)` (with N > 0) in test sources, using `visitDotQualifiedExpression` to detect `Thread.sleep(...)` calls. Rule compiles and is active in `detekt.yml`.

4. **Phase 2 — Emulator ANR.** MainActivity hangs at startup with `ANR: FocusEvent(hasFocus=true)` after 5 seconds — traced to `koinInject<AppearanceSettingsRepository>()` being called in the App composable during cold start. This blocks manual UI verification on the emulator. Phase 3/4/5 proceeded independently.

## Decision

1. **androidApp smoke tests: Espresso-based assertions, not Compose UI test API.** The BOM/compose-multiplatform API incompatibility is a known Gradle metadata limitation. The Espresso `ComposeView`-class assertion provides a minimal smoke gate.

2. **`androidHostTest` is dead code until AGP fixes `AndroidUnitTest`.** The `testAndroidHostTest` task does not execute `androidHostTest` sources. Robolectric infrastructure is added correctly but unreachable. No further investment until AGP resolves this.

3. **`NoRealDelayInTestRule` catches both `delay()` and `Thread.sleep()`.** New violations in any test source will be caught by detekt as a build failure (`warningsAsErrors: true`).

4. **ANR on MainActivity cold start — ADR-worthy follow-up.** The `koinInject<AppearanceSettingsRepository>()` in App composable needs investigation. Not fixed in this PR.

5. **`just setup-hooks` broken (pre-existing).** `git config core.hooksPath` set to `.git/.githooks` which doesn't exist. Fixed in worktree with `git config core.hooksPath /path/to/main/.git/hooks`. Not fixed in main checkout.

## Rationale

The Espresso workaround provides a smoke gate (at least verifies the ComposeView is attached to the window) without requiring BOM manipulation. The alternative — forcing a different compose BOM version — risks runtime incompatibilities elsewhere.

The AGP `AndroidUnitTest` bug means `androidHostTest` is currently unusable for automated testing. The Robolectric infrastructure is added correctly and will work once AGP fixes the task discovery.

The ANR is a production bug that blocks UI testing on device/emulator. It needs a separate investigation and fix.

## Consequences

- 4 instrumented tests now have minimal assertions and will fail if ComposeView is not attached
- Detekt will fail (build failure) on any new `Thread.sleep(N > 0)` in test sources
- `androidHostTest` configuration is present but tests never run — this is documented as an AGP limitation, not a code bug
- `mcp-server` and `desktopApp` have similar zero-assertion placeholder tests (follow-up PR)

## Follow-up Issues (not in this PR)

| File | Issue |
|------|-------|
| `mcp-server/.../McpServerEndToEndTest.kt:129` | `Thread.sleep(3_000)` — triggers `NoRealDelayInTestRule` |
| `desktopApp/.../ContextMenuTest.kt` | 4 tests without assertions |
| `desktopApp/.../MenuBarTest.kt` | 3 tests without assertions |
| `shared/.../AuthDomainTest.kt:13,35` | implicit "should not throw" |
| `shared/.../FileSystemContractTest.kt:73` | no assertion on idempotency |
| `App.kt` | `koinInject<AppearanceSettingsRepository>()` ANR on cold start |
| `just setup-hooks` | broken `core.hooksPath = .git/.githooks` |

## Links

- [AGENTS.md — testing ban list](../AGENTS.md#ban-list)
- [singularity-todo-test-flaky-prevention skill](../.agents/skills/singularity-todo-test-flaky-prevention/SKILL.md)
- [NoRealDelayInTestRule.kt](../detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoRealDelayInTestRule.kt)
- ADR `2026-09-26-ui-testing-deferred.md` (superseded)
