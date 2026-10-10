---
title: "Direct Dispatchers Mostly Sit In Platform Ports Where They Are Correct"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04, when `NoDirectDispatchers` was made able to fire. It
reported 21 sites and the plan proposed constructor-injecting a
`CoroutineDispatcher` into each, with a Koin change per module.

**Status: OPEN**

**Tracked as:** #455

**Symptom:** sampling the 9 baselined `shared` sites shows most of them are the
**platform port implementations** the `expect`/`actual` section of AGENTS.md
describes:

| File | Nature |
|---|---|
| `AndroidSecureStorage.kt`, `JvmSecureStorage.kt` | `SecureStoragePort` implementations |
| `JvmNotificationPort.kt` | `NotificationPort` implementation |
| `JvmFileRevealer.kt` | `FileRevealer` implementation |
| `BackgroundScope.jvm.kt` / `.android.kt` | `actual fun createBackgroundScope()` — the factory, defined to return `Dispatchers.Default` |
| `AlarmReceiver.kt`, `AndroidCalendarProvider.kt`, `AndroidCalendarAppQueries.kt` | Android platform glue, not ports |

**Why injecting is the wrong fix here.** A port implementation is precisely the
layer that *should* know it does blocking I/O — that is what the port is for.
Making the caller supply the dispatcher pushes threading decisions back up to every
call site, which is the coupling the port boundary exists to remove. The rule
already has the right precedent: it whitelists `FileLogWriter` **by file path**
precisely because ordered writes are a legitimate reason to name `Dispatchers.IO`.

**The two genuinely non-port sites** are `AlarmReceiver`,
`AndroidCalendarProvider` and `AndroidCalendarAppQueries`, and 2 desktopApp entries
that were in *test* files (now excluded — see below). Those three Android classes
are ordinary classes and could take an injected dispatcher, but each is constructed
by the Android framework (`AlarmReceiver` is instantiated by the system, the other
two are Koin singletons), so "inject a dispatcher" means changing how the framework
constructs them. That is a design question, not a mechanical edit.

**Partly resolved in the 2026-10-04 cycle:** the rule's KDoc promised to "skip all
/test/ directories" and no such filter existed, so it flagged
`CoroutineDiagnosticsTest` and `TaskDetailCoordinatorGraphTest` — tests that
legitimately build a scope on a real dispatcher because they drive a real Compose
runtime. The filter now exists and is tested.

**Try next:**

1. **Extend the path whitelist to the port layer**, mirroring the `FileLogWriter`
   precedent: a `Dispatchers.*` reference inside a `*Port` implementation or a
   documented platform factory is the design, not a violation. That removes ~6 of
   the 9 without touching a constructor.
2. **Decide the platform-factory question explicitly.** `createBackgroundScope()`
   is documented in AGENTS.md as returning `Dispatchers.Default`. Either the rule
   exempts platform factories by name, or the KDoc changes. Right now the KDoc and
   the rule disagree.
3. Only then consider the three Android framework classes, and treat each as an ADR
   — "how does a framework-constructed class get a dispatcher" is a real question.

Do **not** do a 21-site constructor sweep. It would touch DI bindings across four
modules to fix sites that are architecturally correct, and the plan's own warning
applies: enabling a rule reddens the build, but so does obeying it literally.

---
