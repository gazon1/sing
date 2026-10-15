---
title: StubAuthRepository — why it stays in androidMain
adr: 2026-10-14-stubauthrepository-jvmmain-boundary
status: accepted
---

## Context

`StubAuthRepository` (in `shared/src/androidMain/kotlin/com/singularity/todo/test/stubs/`) is an `AuthRepository` stub used by E2E smoke-test builds. It returns a permanently signed-in session and supports failure simulation via Android system properties (`debug.stub_auth.*`).

`FakeAuthRepository` (in `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/`) is the JVM-unit-test counterpart in `commonMain`.

The question: should `StubAuthRepository` be available in `jvmMain` (or `commonMain`) for JVM E2E coverage?

## Decision

`StubAuthRepository` stays in `androidMain`. It is not moved to `jvmMain` or `commonMain`.

## Rationale

**Android system properties are not portable.** `StubAuthRepository` reads `debug.stub_auth.unconfirmed_signup`, `debug.stub_auth.signin_failure`, and `debug.stub_auth.attach_failure` via `Runtime.getRuntime().exec(arrayOf("getprop", ...))`. This is the Android shell (`adb shell setprop`), not a JVM mechanism. Moving to `jvmMain` would require a parallel mechanism (environment variables, a config file) with its own implementation surface and test.

**There is no JVM E2E target.** Maestro flows run against an Android APK installed in an emulator. Desktop E2E is not a current requirement. JVM tests that need a stub `AuthRepository` use `FakeAuthRepository`, which is already in `commonMain` and covers all `AuthRepository` operations.

**The existing boundary is correct by design.** The test doubles follow the established pattern:
- `FakeAuthRepository` in `commonMain` → JVM unit tests (pure Kotlin, no Android dependencies)
- `StubAuthRepository` in `androidMain` → Android E2E tests (uses Android system properties)

**The `FakeAuthRepository`/`StubAuthRepository` distinction is intentional**, not a gap. `Fake` is for deterministic unit-test behaviour; `Stub` is for property-driven failure simulation in E2E. The difference is the runtime configuration mechanism, which is platform-specific by nature.

## Consequences

- `StubAuthRepository` is only available in the Android DI module (`testAuthModule`). JVM tests that need auth injection use `FakeAuthRepository`.
- If a future JVM E2E target is added (e.g., a desktop Compose smoke test harness), a `jvmMain` variant of `StubAuthRepository` would need environment-variable-based configuration instead of `getprop`.
- The `AttachAccountScreen` happy-path Maestro flow (`smoke/03-attach-account.yaml`) is driven by `StubAuthRepository.migrateAnonymousTo` in the Android E2E environment.

## Links

- `StubAuthRepository` — `test/stubs/TestAuthStubs.kt` (androidMain)
- `FakeAuthRepository` — `test/fakes/FakeRepositories.kt` (commonMain)
- `testAuthModule()` — `core/di/TestDiModule.kt`
