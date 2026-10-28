---
title: Disable per-class JVM forking in :shared:jvmTest
status: accepted
date: 2026-10-28
tags: [testing, gradle, koin, performance]
---

# Disable per-class JVM forking

## Context

`forkEvery = 1` made every test class start its own JVM. Three reasons were
cited; two had already stopped being true (BackgroundFailureHandler is now a
value; FileSystemContract temp paths use a per-JVM UUID plus an atomic counter).
The remaining reason (Koin global state) is handled by stopping Koin per class.

## Decision

- Set `forkEvery = 0` in `:shared:jvmTest`.
- Add `KoinIsolationExtension` (AfterAllCallback) calling `stopKoin()`, registered
  globally via META-INF/services.
- Raise `maxParallelForks` cap from 4 to 8.
- Add `NoGlobalKoinInJvmTestsTest` to guard that jvmTest classes do not touch
  the global Koin context (which would race with the extension).

## Consequences

- `:shared:jvmTest` no longer pays one JVM start per class.
- Any jvmTest class that needs global Koin must be refactored to `koinApplication`
  (local context) first.
- Memory budget: each fork reserves `maxHeapSize`; cap 8 means up to ~12 GB.

## Links

- `docs/decisions/2026-09-25-test-parallelization.md`
- `shared/build.gradle.kts` jvmTest block