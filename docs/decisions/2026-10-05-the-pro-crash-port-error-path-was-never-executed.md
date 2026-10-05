---
title: "The pro port's error path was never executed, and the SDK throws on the path a tokenless build takes"
date: 2026-10-05
tags: [pro, crash-reporting, testing, open-core, vendor-sdk]
status: accepted
---

## Context

`pro/` exists for one reason: `ru.ok.tracer` (AppTracer) is a proprietary SDK and
the Apache-2.0 core may not carry it. `TracerCrashReportingPort` is the class
that wraps it, and its KDoc makes a specific claim about why every call is in a
`runCatching`:

> This class runs on the error path. `report` is called from inside
> `MviViewModel.catchTo`'s failure arm, so an exception escaping the SDK here
> would kill the process *while handling an error*.

That reasoning was written from reading the SDK's source and had never been
executed. `pro/` had no tests at all until
`ProObservabilityModuleTest` (2026-10-05), and that one tests the DI rebinding —
whether the right class is behind `CrashReportingPort` — not what the class does
when called.

So the claim was plausible, load-bearing, and untested. `pro/build.gradle.kts`
sets `isDisabled = tracerAppToken.getOrElse("").isBlank()`, which means every CI
run and every contributor's checkout is in exactly the state that had never been
exercised.

## Idea

Read the SDK's bytecode rather than its docs, then write a test that runs the
real classes in the real state, and then check whether the test can fail.

## Decision

`TracerCrashReportingPortRuntimeTest` — five tests that call the port against the
actual `ru.ok.tracer` classes with no app token configured.

`javap -c` on `TracerCrashReport.reportCaughtException` gives the whole contract:

```
if (isDisabled) { Logger.d("Tracer is disabled"); return }
checkNotNull(crashLoggerInternal)   // throws IllegalStateException
```

The natural reading is that a build without a token takes the first branch and is
a clean no-op.

**That reading is wrong, and it took running the test to find out.**

Removing `runCatching` from `TracerCrashReportingPort.report` fails four of the
five tests, on `IllegalStateException("Required value was null.")`. The reason is
in the definition of `isDisabled`: it is `isConfigDisabled || Tracer.isDisabled()`,
and *neither* term is set by a build that never ran the vendor plugin's generated
configuration. The SDK is therefore neither disabled nor initialised. It is in a
third state — one the SDK's own source does not name — in which the
`checkNotNull` is reached and throws.

Which means: on every tokenless build, every call to `report` throws. The
`runCatching` is not defensive programming around a theoretical risk; it is the
only thing between a handled error and a process crash, on a path that runs
whenever the app is built without a secret.

## Rationale

The control is the finding. A test that passes on a broken port proves nothing,
and this project's standing rule is that a rule nobody has seen fire may not
fire. Running it changed the claim in the KDoc from "would kill the process" to
"does throw, on the path every tokenless build takes" — the second is checkable
and the first was not.

**What this still does not prove.** A real report reaching AppTracer's backend
needs an app token and a device. That remains unverified and the release commit
says so. The test covers the half that needs neither: the port's behaviour in
every state a build without a secret can produce.

There is a second, smaller assertion in the same file, and its reason is
specific. `ru.ok.tracer` is `api` on `:pro` only because `ProSingularityApp`
implements `HasTracerConfiguration`. Remove that dependency and the class fails
to *load*, not to run — `NoClassDefFoundError` at instantiation, which a `try`
around the *call* would not catch. The test asserts the class is present when the
SDK is on the classpath and that the failure mode when it is not is specifically
a load failure, so a future change that breaks the dependency says what broke.

## Consequences

- `pro` now has 11 tests: 6 for the rebinding, 5 for the port's behaviour.
- The `runCatching` in `TracerCrashReportingPort` is load-bearing in a way that
  is now demonstrated rather than asserted. A future refactor that "simplifies"
  it away will fail a test rather than ship a crash in every CI build.
- The release note's claim about the pro path is now narrower and accurate: the
  wiring and the error path are tested; the vendor's upload is not.
- The remaining gap is genuinely a gap and is not closable here. It needs a token
  and a device, and it is tracked rather than described as pending in a comment.

## Links

- `pro/src/test/kotlin/com/singularity/todo/pro/observability/TracerCrashReportingPortRuntimeTest.kt`
- `pro/src/test/kotlin/com/singularity/todo/pro/observability/ProObservabilityModuleTest.kt`
- `pro/src/main/kotlin/com/singularity/todo/pro/observability/TracerCrashReportingPort.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/observability/CrashReportingPort.kt`
  — the contract that "implementations must not throw"
- `docs/decisions/2026-10-05-provenance-audit.md` — why the SDK is in `pro/` at all
