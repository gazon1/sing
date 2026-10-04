---
title: "AppTracer integration: a Koin port on the MVI error path"
date: 2026-10-04
status: accepted
tags: [observability, android, kmp, koin, logging, security]
---

## Context

The project had no crash reporting of any kind. `grep -riE 'sentry|bugsnag|crashlytics|firebase|ok\.tracer'`
over the code, the version catalog, and every Gradle file returned **zero hits** — the only
matches were documentation, and that documentation pointed at Firebase Crashlytics as if it
were configured (`2026-09-26-observability-production.md:41,61`) and instructed agents to open
a Crashlytics console (`debugging-investigation/SKILL.md`). A fatal crash was visible only as a
one-star review, and a handled failure left no trace at all.

The error surface already existed and was well built; what was missing was a place for it to
go. `MviViewModel.catchTo` receives every `emitError`/`catchTo` call site's failure — 34 sites
across ten ViewModels — and `Throwable.toMessage()` was the only shared funnel, feeding the UI
and nothing else.

## Idea

1. **A Koin port, bound in both platform modules.** Follow the existing convention exactly.
2. **A process-wide service locator** (`object CrashReporting { install(); current() }`),
   justified by "an uncaught-exception handler must reach it without a DI graph".
3. **A Kermit `LogWriter` that forwards every `Logger.e`** — a third option considered and
   rejected, because the existing `RedactingLogWriter` clones the throwable and loses the stack
   trace, so a writer inserted behind it would upload useless frames.

## Decision

Option 1 — an ordinary port, like `NotificationPort`, `BackupCodec`, `SecureStoragePort` and
`FileSystem`:

```kotlin
interface CrashReportingPort {
    fun report(error: Throwable, issueKey: String)
    fun addBreadcrumb(message: String)
}
```

Bound `single<Port> { Impl(get()) }` in `PlatformModule.android.kt` and
`PlatformModule.jvm.kt`. The JVM implementation is inert — AppTracer is Android-only and the
desktop keeps its Kermit file log. The JVM binding still exists, for the reason the `Haptic`
binding exists: a missing definition throws `NoDefinitionFoundException` *inside* composition,
which Compose retries every frame.

**The funnel is one function.** `MviViewModel` gained a trailing defaulted `crashReporter`
parameter and one `report` call per arm of `catchTo`'s existing fold. Every one of the 34
call-site errors now flows through it with no per-call-site change.

**Why option 2 was rejected.** The justification does not hold: the uncaught-exception handler
is installed in `Application.onCreate`, where Koin is already available. The project has also
gone out of its way to remove statics — `NoStaticProfileAwareCurrentUserRule` exists solely to
ban one — so a global would have needed an ADR of its own to justify. Its only real cost was
saving ~22 mechanical edits, which is not worth a hidden dependency that a reader cannot see.
The cost was paid instead: the ten ViewModels that own funnel call sites now each declare the
parameter and pass `get()` in their Koin binding, so the dependency is visible in both the
constructor and the wiring. `CrashReportingWiringTest` fails the build if one of them is
removed — a defaulted parameter would otherwise restore the silent-drop failure mode with no
compiler error.

**The default is an explicit no-op value, not a global lookup.** `NoOpCrashReportingPort()`
means a ViewModel built in a test is silent without any global to reset.

## The SDK placement

The runtime goes in **`shared/androidMain`**, beside `AndroidNotificationPort`, so the Android
implementation sits with its siblings. The Gradle plugin applies only to **`androidApp`**,
because it injects `resValues` and uploads mappings and therefore must target the application
module. `androidApp` repeats the runtime dependency, because it implements
`HasTracerConfiguration` and `implementation` dependencies of `:shared` are not visible to it
at compile time. The duplication is deliberate and commented at both sites.

Two build details that are not obvious:

- **`resValues = true` is mandatory.** The SDK embeds resources at build time and AGP 9
  disabled the feature by default; omitting it fails at *runtime*, not at build time. This is
  the repo's **first** `resValues` use, so it sets the pattern for every future SDK.
- **No BOM.** The vendor docs specify `platform("ru.ok.tracer:tracer-platform:1.4.0")`, but
  `platform()` is deprecated in a KMP source set (KT-58759) and only one Tracer module is
  consumed, so there is nothing to align. Both coordinates take the version from one catalog
  ref, which is also what the plugin resolves against.

Tokens are read through `providers.gradleProperty` / `providers.environmentVariable`, never
`System.getenv`: a raw environment read is snapshotted by the configuration cache and silently
goes stale, the same reason `desktopApp/build.gradle.kts` forwards its test switches via
`providers.systemProperty`. With no token, `isDisabled` keeps the SDK inert so CI and any
secretless checkout still produce an installable APK.

## Uncaught exceptions and the log tail

`Application` installs a handler that drains the Kermit writer and then **always delegates** to
the previously installed handler. Android initializes a process as ContentProvider.onCreate →
attachBaseContext → onCreate, and the SDK reads its configuration in the attachBaseContext..onCreate
window, so it installs first and `previous` is its handler. Delegating is what lets the crash
actually be uploaded; swallowing would silently lose every CRASH event while still looking
correct. Ordering is confirmed on-device, not assumed.

The drain exists because `Application.onTerminate` never fires on a real device, so nothing
else ever empties the writer's queue. `FileLogWriter` is held inside the log module behind
`flushLogs()` rather than returned from `initLogging`: it extends Kermit's `LogWriter`, and
`androidApp` has no Kermit on its compile classpath, so naming the type there would not compile.

## Why the reporter receives the original throwable

Grouping and diagnosis both depend on the real stack trace. `RedactingLogWriter` rebuilds a
throwable to redact it — and in doing so **discards the original frames**, so a reporter
inserted behind it would upload redaction frames instead of the throwing site. The reporter
therefore takes the throwable unmodified.

The trade-off is that the raw message may embed content. It is accepted for now because every
instrumented site produces identifiers and labels rather than user prose, and the *grouping
key* is always machine-shaped — an `AppError` code like `error.not_found`, or a fixed
call-site label. Revise if a report is ever seen carrying content.

## The redaction fix (bundled)

`RedactingLogWriter` had two defects, both pre-existing and both invisible to its own test:

1. It replaced the throwable with an anonymous `object : Throwable(...)` constructed *inside the
   writer*. Kotlin captures the stack at construction, so **every throwable in the log file
   recorded the redaction frame** — a shared log bundle was useless for stack traces, which is
   the main reason a user sends one.
2. It passed `original.cause` through **unredacted**, so a credential nested in a cause's
   message reached Logcat and the log file in the clear, contradicting its own documentation.

The fix copies the original's `stackTrace` onto the redacted copy and walks the whole cause
chain, with a seen-set so a self-referential cause terminates. The existing test asserted only
on `toString()` and passed green throughout; it now asserts on the frames and on a redacted
cause. This was the highest-value item in the change — a live credential leak, sitting in the
exact path this feature depends on.

## Also bundled

- **`androidApp` was completely unlinted.** Its detekt block pointed at a non-existent source
  root (`src/androidAndroidTest/kotlin`), *and* the `just lint` recipe never invoked
  `:androidApp:detekt` at all. Even invoked manually, `ignoreFailures = true` made it
  report-only while `shared` and `desktopApp` were enforcing. Corrected, added to the recipe,
  and flipped to enforcing with a baseline — the module this change edits most was the one
  nobody was checking.
- **The calendar-sync collector was unguarded.** It runs on `SupervisorJob() + Dispatchers.Default`
  with no `CoroutineExceptionHandler`, and its body makes three Room `.first()` reads, so a
  storage error killed the process from a background dispatcher — or, in the non-fatal case,
  silently killed the collector and left sync dead for the session. Each pass is now caught and
  reported. Its KDoc also claimed idempotency that no code enforced; a second `start()` would
  have thrown on an already-consumed channel. Code and documentation now agree.
- **`debugInfo()` was dead code** the unwired-surface audit structurally cannot see (that
  script matches uppercase-initial names only, so a camelCase function is invisible to it). It
  is now wired as a startup breadcrumb, which is what it was always for.
- **The rolling-log file count was duplicated** between `FileLogWriter` and `LogBundleExporter`.
  The duplication was documented as a deliberate trade-off, but raising the writer's count
  would have made exported bundles silently drop the oldest files. One shared constant now.

## Consequences

- Android gets crash, ANR, non-fatal and crash-free visibility. Desktop is unchanged.
- `AppError` gained a stable `code` and an optional `cause`. The cause half fixes a real defect:
  `runCatchingResult` flattened every throwable to its message, losing the stack trace at
  exactly the point where it would be read. The code half is a grouping affordance and could be
  approximated by `error::class.simpleName`; it is the lower-value half of that change.
- `NoOpCrashReportingPort` and `JvmCrashReportingPort` are registered in
  `find-unwired-surfaces.py`'s `DECLARED_INTENT` allowlist. The no-op is reached through a
  default parameter, which a static scan cannot follow; without the entry the audit fails on a
  class that is unreferenced by design.
- The reporter is called on the error path, so **its SDK calls are wrapped in `runCatching`.**
  An exception escaping here would escalate a handled error into a process crash — the exact
  failure the reporter exists to observe.
- Debug builds upload. That is deliberate, so the integration can be verified before the first
  release, and it is the one thing to turn off when release traffic starts.
- `androidApp` detekt is now enforcing. Later changes there can fail the build.
- Breadcrumbs are deliberately sparse: the SDK's buffer is 64 KB and the observability ADR
  forbids logging user content. Only state transitions are recorded.

## Not done, deliberately

- The ~139 silent `runCatching` sites in `commonMain` still fail invisibly. Instrumenting them
  is a per-call-site review, not a change that can be made safely in the same pass.
- The reported throwable's *message* is not sanitized. See the trade-off above.
- No consent or opt-out UI, and no store privacy-manifest changes: the payload carries no
  personal data by construction.

## Links

- `core/observability/CrashReportingPort.kt` — the port
- `core/ui/MviViewModel.kt` — the funnel
- `core/log/RedactingLogWriter.kt` — the redaction fix
- `core/log/LogBootstrap.kt` — `flushLogs()`
- `androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt` — SDK config + crash handler
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/CrashReportingWiringTest.kt` — the guard
- `2026-09-26-observability-production.md` — amended: Crashlytics rows and the `traceId` claim
- `2026-09-23-analytics-port.md` — deferred crash reporting to a dedicated ADR; this is it
- `2026-10-03-android-shutdown-log-tail-lost.md` — the tail-loss problem `flushLogs()` addresses
