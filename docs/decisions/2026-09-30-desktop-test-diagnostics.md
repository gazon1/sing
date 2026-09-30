---
title: "Desktop test diagnostics: per-test Kermit ring, FailureBundle, awaitTag explainer"
date: 2026-09-30
status: accepted
tags: [testing, desktop-compose, koin, kermit, ui-tests]
---

# Desktop test diagnostics: per-test Kermit ring, FailureBundle, awaitTag explainer

## Context

Desktop Compose UI flow tests (`runDesktopAppTest`) run in a forked JVM with an
in-memory `FakeAppDatabase`. When a test fails, CI reports showed only the
stack trace — no semantics tree, no screenshot, no database state. Diagnosing a
failure required running the test locally under a debugger or with verbose logging
already enabled.

The second problem was `awaitTag` timeouts: a missing tag produced "could not find
any node" with no hint about what tags existed or what the selector was close to.

## Decision

### Per-test Kermit ring buffer via DI

`RingBufferLogWriter` is a `single` in the test Koin module, installed into the
global Kermit logger before the test body runs, and drained into `FailureBundle`
on failure.

```kotlin
// TestKermitModule.kt
fun testKermitModule() = module {
    single { RingBufferLogWriter(capacity = 2_000) }
}

// DesktopAppHarness.kt — before test body
val ringBuffer: RingBufferLogWriter = app.koin.get()
installRingBuffer(ringBuffer)   // adds to global Kermit writer list
initTestLogging()               // adds PlainStdoutWriter if property set
```

Why not a global singleton? Kermit's `Logger.setLogWriters()` is process-global.
`installRingBuffer` maintains a `MutableList<LogWriter>` and calls
`Logger.setLogWriters(*list.toTypedArray())` so both the ring buffer and the
stdout writer coexist.

Why a ring buffer? A test that runs for minutes with verbose logging produces
unbounded output. The ring discards oldest entries at 2 000, keeping memory
bounded.

### FailureBundle

On any exception from the test body, `FailureBundle.capture()` writes:

| File | Content | Available in 1.12.0 |
|---|---|---|
| `screenshot.png` | `DesktopComposeUiTest.captureToImage()` | ✅ |
| `db-state.txt` | `FakeAppDatabase.dumpAll()` | N/A |
| `kermit.log` | `RingBufferLogWriter.drain()` | N/A |
| `tree-merged.txt` | semantics tree | ❌ `toTree()` not in desktop jar |
| `tree-unmerged.txt` | unmerged tree | ❌ `onRoot()` not in desktop jar |

The bundle path is added as a suppressed exception so it appears in CI reports.
Each `runCatching` inside `capture` is independent — one failed artifact does not
prevent the others.

### awaitTag explainer

`awaitTag(tag)` collects all tags from the semantics tree on every poll cycle
and, on timeout, throws `AssertionError` with:

```
Tag 'nonexistent-tag' is not in the semantics tree.
Nearby tags: 'agendaSection', 'taskItem', 'No Date'
All available tags (N total):
  ...
```

The explainer uses `TAG_PATTERN = Regex("""testTag=[^\s,\]]+""")` against
`SemanticsNode.toString()` — `toString()` is available in desktop 1.12.0 even
though `toTree()` is not.

## Known limitations

`toTree()` and `onRoot()` are **not** in `ui-test-desktop 1.12.0`. The
semantics tree dumps are not captured. The screenshot alone is usually sufficient
for visual diagnosis. For selector debugging, run with
`-Dsingularity.ui.dumpTree=true` — `DesktopAppBootTest` uses
`onRoot().printToString()` successfully (proving the import is available), so the
gap is specific to the `toTree()` format.

## Retry policy

Retry is handled by Gradle's `--rerun-tasks` / test task retry, not by the
harness. Each attempt writes to `attempt-N/` so retries do not overwrite each
other.

## Consequences

- A test failure in CI now produces a directory path in the suppressed exceptions
  that contains screenshot + DB state + Kermit log.
- A timeout on `awaitTag` names the missing tag and suggests nearby alternatives.
- The per-test ring buffer is bounded (2 000 entries) and isolated per test.
- Tree dumps are a known gap — documented in `FailureBundle.kt` and the skill.

## Links

- `RingBufferLogWriter.kt` — lock-free ring buffer `LogWriter`
- `FailureBundle.kt` — artifact capture on failure
- `TestKermitModule.kt` — DI provider for the ring buffer
- `TestLogging.kt` — `installRingBuffer`, `resetKermitWriters`
- `DesktopAppHarness.kt` — integration point
- `DesktopNavigation.kt` — `awaitTag` explainer
- Skill `singularity-todo-desktop-compose-ui-tests` — harness and debugging docs
