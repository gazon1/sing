---
title: "The Android Graph Test Runs But Cannot Open A Database"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN — Robolectric is wired and the test executes; Room's native SQLite does not load**

**Tracking:** tracked here rather than as a GitHub issue because the remaining work is a
single bounded step with a known failure mode — extract `libsqlite3.so` for linux-x86_64
from the bundled SQLite artifact into `shared/src/androidHostTest/jniLibs`, or point the
task's `java.library.path` at it, then re-run the one class. Everything else is already in
place: the stack, the detekt source entry, the tag rule, the task-filter exemption and the
ADR correction. It does not need a queue position; it needs someone with a spare
afternoon and the artifact on disk.

**Found in:** 2026-10-07, attempting the fix recorded in the entry above. The stack now
works far enough to produce an answer, and the answer is not the one the entry expected.

**What is done and verified.**

- `shared/src/androidHostTest` declares `robolectric`, `androidx-test-core`,
  `androidx-test-junit` and `junit-vintage-engine` (RuntimeOnly).
- `src/androidHostTest/kotlin` is in `detekt.source`, so detekt now reports on it.
- `TestTagCoverageTest` lists the source set, which applies the `-Ptest.tags` filter.
- `AndroidSyncDiGraphResolutionTest` compiles and **runs** under
  `:shared:testAndroidHostTest -Ptest.tags=fast,slow`. Getting there required two fixes
  recorded in ADR `2026-10-07-a-default-argument-that-is-wrong-for-every-caller`: the test
  needed a `@Tag`, and the task needed an exemption from `includeTags`, because the Vintage
  engine does not map Jupiter's `@Tag` onto Platform tags and so a Robolectric class can
  never be selected by a tag filter at all. Before those, the task was green over 172
  classes with this one absent.

**The finding.**

```
java.lang.UnsatisfiedLinkError: no sqliteJni in java.library.path:
  …:…:…:…:…/shared/src/androidHostTest/jniLibs
  at WrappingDriver_androidKt$wrappingDriver$1.open(WrappingDriver.android.kt)
  at PlatformPragmas.applyOnceToFile(PlatformPragmas.kt:45)
  at AppDatabaseFactory.build(AppDatabaseFactory.kt:67)
  at PlatformModule_androidKt.platformModule$lambda$0$0(PlatformModule.android.kt:101)
  at AndroidSyncDiGraphResolutionTest…
```

Room's `androidx.sqlite:sqlite-bundled` ships an Android `.so`; Robolectric looks for
`sqliteJni` under `src/androidHostTest/jniLibs` and does not find a loadable one. So every
definition that reaches the database — which includes `GoogleSyncEngine` and its four DAOs,
i.e. exactly the cycle this test was written for — cannot be constructed here.

**Why the test is not committed green.** The only assertions worth having are the ones
that resolve DB-backed definitions; anything less resolves nothing and asserts nothing.
Substituting a fake database would test the fake, which is what the entry above warned
against, so the honest state is: no test in that source set yet, and a known reason.

**Try next, in this order.**

1. **Put the native library where Robolectric looks.** Extract `libsqlite3.so` for
   linux-x86_64 from the bundled SQLite artifact into `shared/src/androidHostTest/jniLibs`,
   or set `java.library.path` for the task. Smallest change, and it keeps the test's scope
   honest. Verify by running the test alone before re-running the task.
2. **Assert the cycle without a database.** `koin.checkModules()` with a definition check
   that does not instantiate factories would catch the *shape* of a cycle, which is what
   `koin-compiler-plugin` missed — but it would not catch a wrong context or a missing
   DataStore. Weaker, and it should say so in the class name.
3. **Keep the Robolectric stack for the next Android test and leave this open.** The
   wiring cost is paid once now rather than by the next person who needs a real `Context`.

**Not to do:** mark the test `@Disabled`, or catch the `UnsatisfiedLinkError` and pass.
`check-test-runs.py` rejects the first and it would be a lie in the second — a graph test
that cannot resolve its own database has verified nothing about the graph.
**Try first:** have the test task write a run manifest next to the XML — task path,
whether `--tests` was passed, source-set class count — and have the gate read one field
from it, so it can say "this evidence came from a filtered run" instead of "a suite
stopped running". Gradle leaves no such marker today, which is why this is not a
five-line fix to the gate itself.
