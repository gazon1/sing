---
title: The Android platform is not reinstalled on every run
status: accepted
date: 2026-11-10
tags: [ci, android, performance]
---

# Context

Three CI jobs need the Android platform the app compiles against
(`compileSdk 37`, minor 1 — `platforms;android-37.1`): the affected-test job,
the JVM-suite job and the Android job. Every one of them installed it with
`sdkmanager` on every run.

Measured on run `42720173415` (head `157e4a194`, branch `fix/red-base-gates`),
the `JVM tests` job took 1435 s, and the step that installed the platform took
**626 s** of it — 44 % of the job, and the job is the critical path of the pull
request. The log says where the time went: `sdkmanager` sat on "Fetch remote
repository" and "Still waiting for package manifests" for nine minutes before
it downloaded anything.

The runner image already had the platform. Measured on run `42728418622` (head
`b472b5dbb`), the same step took **1 s**: the cache was a miss ("Cache not
found for input keys: android-platform-37.1") and the platform directory was
there anyway. The `Android (free)` and `Android (pro)` jobs of that run
compiled and packaged APKs against it and passed, so the copy on the image is
complete, not a stub.

So the 626 s were spent reinstalling a package the runner already had.

# Idea

The step should install the platform when the platform is missing, and do
nothing when it is there.

# Decision

**`.github/actions/setup-android-sdk` checks before it installs.**

The step asks whether `$ANDROID_SDK_ROOT/platforms/android-37.1/android.jar`
exists. If it does, the step says so and exits; if it does not, `sdkmanager`
installs the platform exactly as before. `android.jar` is the thing the build
compiles against, so its presence is the question worth asking — a directory
that is there but empty would fail the compile later and name the wrong cause.

Nothing else changes: the same three jobs, the same platform, the same
`sdkmanager` fallback, the same licences.

The pull request path keeps the population the earlier decisions gave it: the
fast half, without the coverage measurement
(`2026-10-22-ci-fast-only-pr-full-on-schedule`,
`2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path`). The `run-full-tests`
label is the escalation, and the trunk and the schedule run the full half
without it.

# Rationale

**The cost was not the platform, it was the conversation about the platform.**
Nine of the ten minutes went into `sdkmanager` talking to Google's repository
before it downloaded anything — and it downloaded a package that was already on
the machine. The check removes the whole exchange, including the part that
cannot be cached away.

**A cache would have been the wrong tool for the right reason.** The first
draft of this change cached the platform directory, the way the Gradle home and
the Robolectric jars are cached. With the platform on the image, the restore
would have downloaded a hundred-odd megabytes on every run of every job to
replace a copy the runner already had — and it would have replaced it with a
copy from an earlier image, which is a worse platform than the one that was
there. The cache also wanted a key that has to be bumped by hand with every
`compileSdk`, and a write policy to keep pull requests from poisoning it.
Deleting the check would have been simpler; deleting the *reinstall* is
simpler still, and it is the whole cost.

**The fallback stays.** A future runner image that does not ship the minor
platform gets the `sdkmanager` path, exactly as before — no regression, only
the redundant work disappears.

# Consequences

**The step's name is now slightly narrow.** It installs the platform only when
the runner does not have it; the name still says what the step is for.

**The image is now load-bearing.** The build depends on the runner image
shipping `platforms;android-37.1`, where before it depended on `sdkmanager`
being able to fetch it. The dependency is not new — the build has always
compiled against that platform — but it is now explicit: an image that drops it
pays the install again, which is slow but correct.

**The pull request gets its fast signal back.** The `run-full-tests` label
selected the full population, and the affected-test job skips when the
population is full — so the pull request had no fast job at all and every
answer arrived with the whole suite. Without the label the population is
`fast`, the affected-test job runs beside the suite, and the first answer
arrives in minutes instead of after the suite.

# Links

- `.github/actions/setup-android-sdk/action.yml` — the check, and the fallback
- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md` — the
  population split
- `docs/decisions/2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md` —
  coverage off the pull-request path
- `.github/actions/setup-jvm-gradle/action.yml` — the caches that were
  considered here and left alone
