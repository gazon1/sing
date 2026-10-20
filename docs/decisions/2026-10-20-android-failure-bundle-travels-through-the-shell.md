---
title: "The instrumented failure bundle travels through the shell's directory"
date: 2026-10-20
status: accepted
tags: [testing, android, ci, tooling]
---

# The instrumented failure bundle travels through the shell's directory

## Context

The desktop Compose tests have had a failure bundle for a while
(`desktopApp/src/jvmTest/.../test/helpers/FailureBundle.kt`): on failure they write a
screenshot, the semantics tree, the database state, the log ring buffer, the coroutine dump
and the step log into `build/diagnostics/<TestClass>/attempt-N/`, and CI uploads that
directory as an artifact.

The Android instrumented suite had no equivalent. A red `android-device-tests` run produced a
JUnit XML with a message and a stack trace, plus a logcat that is a wall of lines from every
process on the device. The question every failing instrumented test raises — *what was on
screen, and how far had the test got* — had no answer.

The obvious implementation is to write the bundle into the app under test's own storage and
let AGP collect it. It does not work, and the reason is structural:

**AGP uninstalls the app under test when the run ends.** An app's private directory is deleted
with the app. Anything the test wrote there is gone before CI can look at it. The desktop
bundle survives because the desktop test writes to the *host's* `build/diagnostics` — there is
no uninstall step on a desktop JVM.

AGP 9.x has no "additional test output" collection mechanism either: the `additional-output`
convention belongs to older AGP/UTP versions and the string does not appear anywhere in the
9.x artifacts this project builds against. It was checked rather than assumed.

## Decision

The bundle is written to the app's **external** directory and then copied to
`/data/local/tmp`, which belongs to the shell user and survives the uninstall.
The copy runs through `UiAutomation.executeShellCommand`, because the
instrumentation has no other way to write outside its own sandbox.

The host side is `scripts/run-android-ui-tests.py`: after the test run it pulls
`/data/local/tmp/singularity-diagnostics` into `androidApp/build/diagnostics/<TestClass>/attempt-N/`,
the same layout the desktop bundle uses, and the workflow uploads it as
`android-failure-diagnostics-<run id>`.

Three details that are not obvious, and all three were measured rather than
assumed:

- **The copy is `sh -c cat<source>>destination`, not `cp`.**
  `UiAutomation.executeShellCommand` does not run the command through a shell:
  the string is split on whitespace and the pieces become the arguments of the
  program. There is no redirection, no pipe and no `&&` — `echo hi > /some/file`
  prints `hi > /some/file` and writes nothing, and quoting does not help
  either, because the quotes arrive as part of the argument and the path is
  looked up with a quote in its name. `sh -c` is the way back to a shell, and
  the script it takes has to be a single argument, so the script may not
  contain a space. `cat` with its input redirected from the source and its
  output redirected to the destination copies the file byte for byte. The `<`
  is not optional: without it `cat/source` is the name of a program that does
  not exist, and the redirection still creates an empty file — which is what
  the first version of this did.
- **The source is the app's external directory, not its private one.** The shell
  cannot traverse `/data/user/0/<pkg>`; the app's external directory is group
  `ext_data_rw` and the shell is in that group. The files are flat in that
  directory rather than in a subdirectory, because a directory the app creates
  there is mode 0700 — the app's umask is 0077 — and the shell cannot traverse
  that either.
- **The bundle directory is emptied before the run.** An emulator that ran a
  previous suite would otherwise hand this run diagnostics that belong to it,
  and a bundle from another run is worse than no bundle.

Two more details about the artifact itself:

- **The bundle is pulled whether the run passed or failed.** A green run may
  still have captured something — a flaky test that retried — and the artifact
  is unconditional so the question "what did this run see" always has an
  answer.
- **The failure message names the bundle.** `addSuppressedTo` adds the artifact
  names to the throwable, and JUnit prints suppressed exceptions under the
  failure. The message is the part of a failed run that reaches a reader
  without opening a file; the screenshot and the tree are files, and the path
  is the pointer.

## Rationale

The alternative — not shipping a bundle at all — is what the suite has lived with, and it is
why the red `main` runs of October 2026 had to be re-read by hand from a logcat.

The alternative of pulling the bundle *during* the test run is not available either: the
pull has to happen before the uninstall, and the only code that runs before the uninstall is
the test itself.

MediaStore was considered and rejected: an app's own media files are removed on uninstall
too, so a bundle written there does not outlive the app either.

## Consequences

- `FailureBundle.kt` and `StepRecorder.kt` live in the androidTest source set, next to the
  suite that produces them. The step recorder prints the desktop format, deliberately: two
  layers that record steps should print them the same way.
- The instrumented suite's waits are recorded as steps (`awaitShell`, `awaitObject`), so the
  bundle says *where* the test was, not only that something was missing.
- The bundle is a best-effort artifact. Every capture is independent: a screenshot that
  cannot be taken must not cost the tree, the steps and the environment.
- `run-android-ui-tests.py` grew a step that can fail on its own. It is written so that a
  missing bundle never turns a failed run into a different failed run.

## Links

- `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/FailureBundle.kt`
- `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/StepRecorder.kt`
- `scripts/run-android-ui-tests.py` — the pull
- `.github/workflows/android-device-tests.yml` — the upload
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/FailureBundle.kt` — the
  pattern this mirrors
