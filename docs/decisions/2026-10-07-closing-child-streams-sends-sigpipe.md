---
title: "Closing a child's streams kills it with SIGPIPE, and these exit codes are capabilities"
date: 2026-10-07
status: accepted
tags: [desktop, reminders, subprocess, testing]
---

## Context

WS4 shipped Desktop reminders built on two subprocess capabilities:

- `notify-send --version == 0` → `Notifier.isSupported`
- `systemd-run --user --version == 0` → half of `ReminderScheduler.isSupported`

Neither exit code is logged and discarded. Both are **the decision**. `isSupported == false`
means the platform genuinely cannot schedule a reminder, callers refuse before persisting,
and the feature is deliberately inert.

The runner behind them did this:

```kotlin
val process = ProcessBuilder(argv).redirectErrorStream(true).start()
process.inputStream.close()
process.errorStream.close()
process.waitFor()
```

It looked like tidiness — discard the output, take the code.

## The defect

Closing the read end of a pipe before the child has written to it delivers **SIGPIPE**.
The child dies with exit code **141**. Whether it writes before or after the close is a
race, and it usually loses:

```
$ java Probe     # notify-send --version, streams closed right after start()
exit=0
exit=141
exit=141
exit=141
```

141 is not zero, so the probe reported that this host cannot display notifications and
cannot schedule anything. `JvmReminderScheduler.isSupported` — a conjunction, so one false
half is enough — returned `false`, and **Desktop reminders refused to arm at all**. No
error, no unit, no notification, and a UI that correctly believed the platform has no
scheduler.

The feature looked like it worked, because it worked on the runs where the race was won.

## Why 2461 tests did not catch it

Every test of `JvmNotifier` and `JvmReminderScheduler` injected a recording runner in place
of the real one. That is the correct choice for asserting argv — it is how a test can claim
an exact argument list without depending on what is installed — and it is exactly why the
defect was invisible: the bug lives only in the default no test reached.

This is the third time in this repository that "the test uses a double" and "the double is
the only thing tested" have diverged. The seam guard, the fake sync scheduler, and now the
subprocess runner are the same shape: a port whose JVM half was inert, invisible precisely
because it was bound and called.

## Decision

1. **One runner, in one place.** `core/process/Subprocess.kt`. It existed as two identical
   copies, in `JvmNotifier` and `JvmReminderScheduler`, and both were wrong the same way.
   How this process talks to the operating system is not a decision two files make
   independently.

2. **`Redirect.DISCARD`, never `close()`.** Discarding to the null device removes the pipe,
   so there is nothing to close and nothing to race. It is also cheaper than reading output
   nobody wants and does not block on a child that writes more than a pipe buffer holds.

3. **Test the real thing, at the boundary where the decision is made.** `SubprocessTest`
   runs real commands — `sh -c "echo hello"` twenty times, asserting a stable `0`, plus
   argv-not-shell-injection and missing-binary cases. `JvmNotifierTest` builds a notifier
   **with no injection** twelve times and asserts the probe gives one answer.

   The repetition is not decoration. The old implementation passed a single run about a
   third of the time; at twelve iterations a regression cannot pass by luck.

## Consequences

- Desktop reminders can arm on a machine that can arm them.
- `isSupported` now means what it says on every host, including a container.
- The two capability probes are covered by tests that would fail if the runner regresses,
  not only tests that assert the argv a double was handed.
- `Subprocess.runQuietly` returns `-1` for a missing binary rather than throwing: every
  caller is asking "did this work?", and a missing binary is an answer.

## The other bug this run found

The same end-to-end session found that `JvmReminderFireCommand.parse` read `args[0]` as
the command. `fun main(args)` receives the **program path** at index 0, so `fire-reminder`
arrived at index 1, nothing ever matched, and every unit silently did nothing while
exiting 0.

Both defects shipped in the same feature, and both were invisible to a suite that only
ever asserted what it had constructed itself. That is the argument for
`JvmReminderFireCommandTest`'s round trip, which feeds the scheduler's **real** emitted
argv back through the parser.

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/process/SubprocessTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/notifications/JvmNotifierTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/reminders/JvmReminderFireCommandTest.kt`
- `docs/decisions/2026-10-07-desktop-reminders-systemd-user-timers.md` — the argv trap
- `docs/decisions/2026-10-07-three-copies-of-reminder-delivery.md` — the refactor this rode in on