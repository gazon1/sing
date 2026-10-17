---
title: "workingDir rewrites relative file paths — and silently breaks tests that use them"
date: 2026-10-17
status: accepted
tags: [build, testing, jvm, process]
---

# workingDir rewrites relative file paths — and silently breaks tests that use them

## Context

`main` went red on run 39015188390. The `test-runs` gate reported:

```
mcp-server:test: 3 skipped tests, ceiling 0 — a @Disabled class or a failing
assumptionGuard is the only legitimate skip; others are dead code or a
miswired test task.
```

The three skipped tests were in `McpServerEndToEndTest`. They all hit:

```kotlin
val jar = File("build/libs/mcp-server.jar")
assumeTrue(jar.exists())   // ← false; test silently skipped
```

The JAR existed at `mcp-server/build/libs/mcp-server.jar`. It was never missing
on disk. The culprit was `workingDir`.

## The mechanism

Gradle (via the Allure plugin on this project) sets `workingDirectory` for
JVM test tasks to `build/allure-results`. Every relative `File(...)` path in
the test process now resolves relative to that directory:

```
# what the test asked for:
File("build/libs/mcp-server.jar")
  → resolve("build/libs/mcp-server.jar")
  → build/allure-results/build/libs/mcp-server.jar   ← does not exist

# what was actually there:
projectDir / "build/libs/mcp-server.jar"
  → /path/to/mcp-server/build/libs/mcp-server.jar   ← exists
```

`assumeTrue(jar.exists())` returned `false` and JUnit recorded the test as
**skipped** — not failed, not errored. The test count in the floor check
(`tests=7`) was still met, but three tests silently did nothing.

The `check-test-runs.py` ceiling is 0 skipped tests, so the gate caught this.
Without that ceiling, the bug would have been invisible: green CI, broken
coverage, zero test evidence.

## The fix

Do not use relative paths for files that live outside `workingDir`. Pass
**absolute paths as system properties** from the Gradle build:

```kotlin
// mcp-server/build.gradle.kts
val jarPath = layout.buildDirectory
    .dir("libs/mcp-server.jar")
    .get()
    .asFile
    .absolutePath

tasks.withType<Test>().configureEach {
    systemProperty("mcp-server.jar.path", jarPath)
}
```

```kotlin
// McpServerEndToEndTest.kt
val jarPath = System.getProperty("mcp-server.jar.path")
    ?: error("mcp-server.jar.path system property not set")
val jar = File(jarPath)
```

The same pattern was already in use for other paths in the project:
`commonMain.root`, `jvmTestSources.root`, `jvmTestResources.root`. The JAR
path was missed because `McpServerEndToEndTest` was the only test that reads
a file from `build/` at runtime.

## Decision

1. All relative `File(...)` paths in `jvmTest` that point to build artifacts
   must use **system properties** set from `build.gradle.kts` using absolute
   paths via `layout.buildDirectory`.
2. Do not use `projectDir` in test code — it is not available in the test
   classpath context the same way; `layout.buildDirectory` in `build.gradle.kts`
   is the reliable equivalent.
3. The `check-test-runs.py` `max-skipped=0` ceiling is the **last line of
   defence** for this class of failure. Any run with `skipped > 0` fails CI.

## Consequences

- `McpServerEndToEndTest` baseline updated from `7 0` to `10 0` (3 previously
  skipped tests now execute).
- `check-gate-wiring.py` correctly detected that `test-runs` fails on a clean
  tree — the meta-gate worked as designed; the failure was real.
- All future tests that read build artifacts must follow the system-property
  pattern. The `workingDir` contract is: relative paths are relative to
  `build/allure-results`, and nothing outside that tree is reachable without
  an explicit absolute path.

## Links

- Commit `b9e0bf1e` — fix(mcp-server): resolve JAR path relative to workingDir
- `scripts/check-test-runs.py` — `max-skipped` ceiling implementation
- `docs/decisions/2026-10-04-configuration-cache-hardening.md` — workingDir
  was moved as part of fixing configuration cache; this is the regression it
  introduced for relative-file-read tests.
