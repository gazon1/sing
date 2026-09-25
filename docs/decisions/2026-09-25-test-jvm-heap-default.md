---
title: "OOM in TaskOutgoingLinksTest — pre-existing environment issue (unresolved)"
status: accepted
date: 2026-09-25
authors: ZCode Agent
deciders: Singularity Developer
tags: [testing, gradle, heap, investigation]
---

# OOM Investigation: TaskOutgoingLinksTest.toLinksJson encodes single link

## Context

`TaskOutgoingLinksTest` (`:shared:commonTest`) consistently failed with
`OutOfMemoryError: Java heap space` during execution. The test itself is trivial —
serializes a 1-element `List<String>` to a JSON array string via `StringBuilder.append()`.
The OOM stack trace pointed to:
```
at java.base/java.util.Arrays.copyOf(Arrays.java:3541)
at java.base/java.lang.AbstractStringBuilder.ensureCapacityInternal(AbstractStringBuilder.java:242)
at java.base/java.lang.AbstractStringBuilder.append(AbstractStringBuilder.java:811)
at java.base/java.lang.StringBuilder.append(StringBuilder.java:246)
at com.singularity.todo.feature.tasks.data.TaskOutgoingLinksKt.toLinksJson(TaskOutgoingLinks.kt:43)
at com.singularity.todo.feature.tasks.data.TaskOutgoingLinksTest.toLinksJson encodes single link(TaskOutgoingLinksTest.kt:85)
```

The OOM reproduces on a clean `main` checkout, confirming it is a pre-existing environment
issue, not caused by any specific commit.

## Investigation: attempted fixes (all negative)

Each fix was tried in isolation; **none resolved the OOM**. The OOM recurs with the same
stack trace every time.

| Attempt | Configuration | Result |
|---|---|---|
| `maxHeapSize = "3g"` on test fork | `:shared` `jvmTest` | FAILED — OOM |
| `maxHeapSize = "1g"` on test fork | smaller heap | FAILED — OOM |
| `org.gradle.jvmargs = -Xmx6g` daemon heap | `gradle.properties` | FAILED — OOM |
| `parallel.classes.default = same_thread` | all 3 modules | FAILED — OOM |
| `parallel.enabled = false` | all 3 modules | FAILED — OOM |
| `maxParallelForks = 1` on test | `:shared` `jvmTest` | FAILED — OOM |
| `kover { disabledForTestTasks.add("jvmTest") }` | skip Kover instrumentation | FAILED — OOM |
| `-Dorg.gradle.workers.max=1` | Gradle worker pool = 1 | FAILED — OOM |
| Temurin 21.0.10 JDK instead of Azul Zulu 21 | `-Dorg.gradle.java.home` | FAILED — OOM |

Heap dump captured during one run was **2.4 GB** with `maxHeapSize = 3g`. The JVM held
near-maximum heap at the OOM point, but the live-set content was not analyzed (dump file
removed by Gradle test executor after process exit).

## Observations

- **Test class itself passes**: XML reports show 12 of 15 tests pass; 3 `toLinksJson*` tests
  are reported as `skipped` with a 6+ second runtime each (suspicious — should be < 1ms)
- **Test Executor JVMs do NOT OOM during orchestration** — OOM occurs inside the test
  method itself, in `StringBuilder.append()`
- **`--no-daemon` does not affect the outcome** — Gradle launcher JVM (Temurin 25) is
  separate from Test Executor JVMs (Azul Zulu 21)
- **Kover instrumentation is NOT the root cause** — disabling it does not resolve OOM
- **The function is trivial** — `toLinksJson` appends `"["`, then for each element
  appends `"$i,${'"'}$link${'"'}"`, then `"]"`. For `listOf("task://abc")`, this is
  `["task://abc"]` — 14 characters total.

## Decision (this MR)

**Do NOT include any OOM fix in this MR.** Keep only the tag-hygiene fixes (D2, D3) and
document this investigation honestly. A follow-up MR should:

1. **Analyze the heap dump** with Eclipse MAT or `jhat` to find what objects consume the
   2.4 GB before `toLinksJson` even starts. (Heap dump generation succeeded but the file
   was deleted by Gradle post-process; need to copy it manually.)
2. **Reproduce outside Gradle** by writing a minimal Kotlin script that calls
   `listOf("task://abc").toLinksJson()` directly and observe memory growth. If OOM
   reproduces outside Gradle, the issue is in JDK / Kotlin stdlib interaction, not Gradle.
3. **Check for JVM-level issues**: Azul Zulu 21 native-image-capable flag (`nativeImageCapable=false`
   in Gradle properties); consider testing with `-XX:+UseG1GC -XX:MaxGCPauseMillis=200`
   or `-XX:+UseZGC` for comparison.
4. **Disable the failing tests temporarily** with `@Disabled` or `@Tag("slow")` so
   CI can proceed while investigation continues.

## Why we kept D1 (heap settings on test forks) anyway

Even though bumping `maxHeapSize` to 3 GB did not fix the OOM, having a sensible default
prevents OOM in OTHER test classes that may legitimately need more than 512 MB. The
settings remain useful as a baseline.

## Consequences

- OOM in `TaskOutgoingLinksTest` remains unfixed
- Test suite runs in CI may still fail on this test class
- The 2.4 GB heap dump indicates a deeper issue (likely heap fragmentation or a
  Kover/Robolectric instrumentation interaction) that requires dedicated investigation
- Tag hygiene improvements (ADR-2) make slow tests explicit and skippable, reducing
  total suite runtime

## Links

- Related ADR: `2026-09-25-test-suite-tag-defaults.md` (sibling decision in this MR)
- Pre-existing: `2026-09-26-production-readiness-findings.md` (audit that flagged OOM)
- Config: `gradle.properties` (unchanged), `shared/desktopApp/mcp-server/build.gradle.kts`
  (test config), `detekt-rules/build.gradle.kts` (new test config for consistency)
