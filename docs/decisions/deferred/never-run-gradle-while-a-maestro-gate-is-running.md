---
title: "Never Run Gradle While A Maestro Gate Is Running"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** [#89](https://github.com/gazon1/sing/issues/89)

**Found in:** 2026-10-04, twice, in one session — the second time it destroyed
the run it was supposed to be checking.

**Symptom:** mid-suite, every flow started failing with
`Package com.singularity.todo is not installed`. A concurrent
`./gradlew :androidApp:installDebug` (or any task touching the same APK) had
uninstalled the app as part of its own install cycle, and the 19-flow suite kept
running against a device that no longer had the binary. 16 failures, none of
them a regression.

**Already known, in this session's own notes:** two Gradle runs in one project
must not overlap. That was learned from `NoSuchFileException` on
`in-progress-results-generic.bin`. It is equally true for the device, and the
failure mode there is much worse: a Gradle build does not fail loudly, it
quietly removes the app that a 20-minute gate is in the middle of exercising.

**Why it is not caught:** `run-maestro.sh` has device-death detection for a
*disconnected* emulator. An uninstalled package is a perfectly healthy device.
The first flow to hit it reports "element not found", which is indistinguishable
from a UI regression, so the retry logic re-runs the whole thing and fails again
for the same reason.

**Do this first:** treat a Maestro gate as owning the device. While one runs,
no Gradle — not even `compileKotlinJvm`, which looks read-only and is not (it
shares the daemon and the APK outputs). If something must be checked
concurrently, run it on a second checkout or accept the serial wait; the whole
point of a gate is that its result means something.

---
