---
title: "The Android Graph Is Never Resolved"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracking:** none — #382 does not exist in the issue tracker

**Reopened from:** #227, which was closed as "a separate concern from the non-sync issues
in scope for this session" — not fixed. The entry below predicted the exact failure that
has now happened: `KoinGraphValidationTest.all singletons resolve without missing bindings`
fails on `gazon1/main` with `StackOverflowError`, and the Android app dies at cold start
with the same signature. Re-tracking rather than opening a third record.

**Found in:** 2026-10-07, while restoring the desktop graph's resolution test.

`SyncDiGraphResolutionTest` resolves the JVM graph. The Android side has no equivalent and
cannot grow one from where it stands: `shared/src/androidHostTest/` contains only
`AndroidManifest.xml`, and `shared/build.gradle.kts` records that the "Koin graph test" its
comment referred to "is also gone". `testAndroidHostTest` therefore runs zero tests.

`PlatformModuleMirrorTest` compares declared binding *names* between the two platform
modules and resolves nothing, so it cannot see anything that only fails when the
definitions run: a body that resolves a type nobody binds on Android, a resolution cycle
(a `StackOverflowError` at app start with no application frame in the stack — ADR
`2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`),
or a `databaseBuilder` handed the wrong context under a harness. `koin-compiler-plugin`
covers none of it, for the same reason it missed the sync cycle.

**Not done here.** Resolving the Android graph needs Robolectric, and `androidHostTest`
declares no test stack — the build file lists exactly what has to be added, and warns that
the Vintage engine is required because Robolectric is a JUnit 4 runner. Declaring a stack
that no test has ever run would produce a green task that executes nothing, which is the
defect class `2026-10-06-ci-single-gate-registry-and-leaf-split.md` already records.

**Try first:** declare the stack as the build-file comment lists it and add exactly one
test — the `SyncDiGraphResolutionTest` equivalent over `PlatformModule.android.kt` plus
`domainModule()`. If Robolectric cannot run on this host, that is the finding; record it
rather than substituting a fake. Then put `testAndroidHostTest` into
`check-test-runs.py --require`, which today would pass a source set that executes nothing.

---
