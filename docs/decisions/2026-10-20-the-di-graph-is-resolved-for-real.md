---
title: "The DI graph is resolved for real: the parameterised definitions, and the Android half of it"
date: 2026-10-20
status: accepted
tags: [di, testing, android]
---

## Context

ADR `2026-10-19-the-checks-the-app-did-not-have` recorded that the DI graph was never
resolved: `TestPlatformModuleParityTest` compares the *registrations* of the production
platform module against the test one, and resolves nothing. It recorded the first
consequence — `RewriteNoteUseCase`, bound through `factoryOf`, threw on every
platform — and said the fix was to resolve the graph.

The fix resolved most of it. Two parts of the gap were left, and both are the kind of
gap that looks like coverage on a dashboard.

**Eight definitions that take a runtime parameter were skipped.** A ViewModel that
edits one entity needs that entity's id, so its definition takes a parameter: eight of
them — `CalendarViewModel`, `AttachmentAnnotationViewModel`, `ProjectDetailViewModel`,
`ProjectEditorViewModel`, `TaskDetailCoordinator`, `TaskCreateViewModel`,
`AgendaViewModel`, `SavedAgendaViewModel`. The registry that resolves them is a
registry, not a list: a ninth such definition (`NoteProposalsViewModel`, added by
`2026-10-22-the-write-path-asks-first`) fails the graph test until it is listed
there, which is the behaviour the registry was built for. The graph test could not create them
without a parameter, so it counted them as "needs a parameter" and moved on. Their own
dependencies were never checked — and these are exactly the definitions that reach into
a repository, a use case and a crash reporter at once, so they are exactly where a
missing binding hides. The count went into the test's own output as a *number*, which
reads like a measurement and is actually a hole.

**The Android half of the graph was resolved by no test at all.** The graph test builds
its graph from `testPlatformModule()` — a set of fakes standing in for the platform.
Every Android binding in `PlatformModule.android.kt` was therefore never created by
any test. The compile-time `koin-compiler-plugin` cannot close that gap: it sees the
*types* a definition asks for, not the ones it would get from the platform module, and
it cannot see a cycle, because a cycle is not a compile error — it is a runtime stack
overflow.

There had been a test for the Android half. It was written, and then deleted, because
the *database* could not open under Robolectric: the bundled SQLite native was not on
the test JVM's library path, and the failure (`UnsatisfiedLinkError: no sqliteJni`)
had nothing to do with DI. The build later grew the native extraction that fixed it.
The gap stayed open for a month, and the reason it stayed open was a build problem.

## Idea

Resolve the parameterised definitions with their parameters, and resolve the Android
graph on the Android graph's own terms.

## Decision

**The parameterised definitions are resolved, with their parameters, from a registry.**

`DomainGraphResolutionTest` (desktop) now carries a `runtimeParameters` map: the
definition's type → the values to hand it. The values are irrelevant — what matters is
that each has the type the definition destructures, so the definition can be *created*,
so its own dependencies are resolved.

The registry is checked in both directions:

- a definition that takes a parameter and is **not** in the registry fails the test,
  with the definition named — it is an unverified definition, and the message says so;
- a registry entry that matches **no** definition in the graph fails the test — a stale
  entry hides the fact that the definition it was written for is gone, or has stopped
  needing a parameter.

So a new parameterised ViewModel cannot slip in unverified, and a removed one cannot
leave a hole behind. The parameters are positional (Koin hands them to the
definition's destructuring lambda by position), which is why the registry holds lists
and not maps.

**The Android graph is resolved for real, under a real `Context`.**
`shared/src/androidHostTest/.../AndroidGraphResolutionTest.kt` builds the graph the
way `SingularityApp.onCreate` builds it — `platformModule()` + `coreLoggingModule()` +
`domainModule()` + the gate module, with `androidContext()` — and resolves the Android
bindings: the database and its unit of work, the file and platform ports, the sync
chain, the calendar-sync half, the alarm-backed scheduler.

The order matters and is the point: the sync chain is resolved leaf first
(`SyncDocumentWriter`, then `SyncRepository`, then `SyncEngine`), because `SyncEngine`
takes `() -> SyncDocumentWriter` and the cycle closes only when the writer is asked
for. That is the cycle ADR
`2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`
records as having shipped as a `StackOverflowError` at app start.

**Three Android bindings are deliberately not created, and the exclusion is on
record.** `SecureStoragePort` needs the `AndroidKeyStore` provider, `Haptic` needs an
API 31 vibrator, and `Notifier` creates a notification channel and reads the settings
— none of them exist off a device. The test checks that they are *registered* (a port
that lost its Android binding resolves on the JVM and crashes on the phone) without
creating them. The list is named in the test, so the exclusion is a decision someone
can see rather than a silent hole.

**A production bug fell out of it.** The Android database binding passed
`context.getDatabasePath("todo.db")` to the bundled driver, which creates the *file*
but not the *directory* around it: a missing parent is `SQLITE_CANTOPEN` (error 14),
not a created-on-demand directory. The framework's own database API creates the
databases directory as a side effect; the bundled driver has no such side effect. The
binding now creates the parent directory itself.

## Rationale

**A skipped definition is not a checked definition.** The test's output said "8
definitions need a parameter" and read like a measurement. It was a hole with a number
attached. The registry turns the hole into a checklist: every parameterised definition
is either resolved or named as missing.

**A registry fails in both directions on purpose.** One direction (a new definition is
not covered) is the obvious one. The other — a stale entry — is the one that keeps the
registry honest over time: an entry that outlives its definition hides the moment the
definition changed shape.

**Robolectric, not an emulator.** The graph needs a real `Context`, because
`platformModule()` calls `get<Context>()` inside several `single` bodies. An
instrumented test would need a device; this runs on the JVM in seconds, which is the
whole reason it can sit in the ordinary gate loop.

**The three device-only bindings are named, not skipped silently.** A test that
resolves "everything it can" is only honest if it says what it did not resolve and
why. The list is in the test, next to the reason.

## Consequences

- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/DomainGraphResolutionTest.kt` —
  the parameterised definitions are resolved; the registry is checked in both
  directions.
- `shared/src/androidHostTest/kotlin/com/singularity/todo/core/di/AndroidGraphResolutionTest.kt`
  — new: the Android graph, resolved.
- `shared/src/androidMain/kotlin/com/singularity/todo/core/di/PlatformModule.android.kt`
  — the database binding creates its parent directory.
- `shared/build.gradle.kts` — the Robolectric stack is now *used*: the SQLite native
  extraction and the `java.library.path` wiring exist for this test, not for a future
  one.
- **The Android test opens a real database file** (in the test's own directory), so it
  is tagged `slow` — it crosses the process boundary, like the other tests that touch
  a real file.
- The three device-only bindings are still not *created* by any test. Their
  registrations are checked; their factory bodies are not.
- The `KOIN-W003` warning stays on the Android test's module set: the module list is
  computed at runtime, exactly as the app computes it, so the compile-time check
  cannot see it. The runtime check is what replaces it.

## Links

- `docs/decisions/2026-10-19-the-checks-the-app-did-not-have.md` — the ADR that found
  the gap
- `docs/decisions/2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine.md`
  — the cycle that shipped
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/DomainGraphResolutionTest.kt`
- `shared/src/androidHostTest/kotlin/com/singularity/todo/core/di/AndroidGraphResolutionTest.kt`
