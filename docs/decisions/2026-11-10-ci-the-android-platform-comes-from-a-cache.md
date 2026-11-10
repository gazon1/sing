---
title: The Android platform comes from a cache, not from sdkmanager
status: accepted
date: 2026-11-10
tags: [ci, android, cache, performance]
---

# Context

Three CI jobs need the Android platform the app compiles against
(`compileSdk 37`, minor 1 — `platforms;android-37.1`): the affected-test job,
the JVM-suite job and the Android job. The runner image ships an SDK, but not
that minor platform, so every one of them installed it with `sdkmanager` on
every run.

Measured on run `42720173415` (head `157e4a194`, branch `fix/red-base-gates`),
the `JVM tests` job took 1435 s, and the step that installed the platform took
**626 s** of it — 44 % of the job, and the job is the critical path of the
pull request. The log says where the time went: `sdkmanager` sat on "Fetch
remote repository" and "Still waiting for package manifests" for nine minutes
before it started downloading. Three jobs paid it, in parallel, for a package
whose content never changes between runs.

The repository already caches the two things that behave the same way: the
Gradle home (`gradle/actions/setup-gradle`) and the Robolectric jars
(`actions/cache` in `.github/actions/setup-jvm-gradle`). Both use one policy —
the trunk writes the cache, pull requests and tags restore it read-only.

# Idea

The platform is content-addressed by its version, exactly like the Robolectric
jar. It can be cached the same way, under the same policy.

# Decision

**`.github/actions/setup-android-sdk` caches the platform directory.**

- The SDK root is read from the runner's environment (`ANDROID_SDK_ROOT`, then
  `ANDROID_HOME`) and passed to the cache steps as a step output, because a
  `with:` block cannot read a shell variable.
- The cache key names the platform version (`android-platform-37.1`), not the
  commit. A new `compileSdk` invalidates the cache; nothing else does.
- The install step runs only when the platform directory is missing, so a
  cache hit costs one directory test instead of a network round trip.
- Only the trunk saves (`if: github.ref == 'refs/heads/main'`); pull requests
  and tags restore read-only, so a pull request cannot evict or poison the
  copy the trunk depends on. This is the policy the other two caches already
  use.

The pull request path keeps the population the earlier decisions gave it: the
fast half, without the coverage measurement
(`2026-10-22-ci-fast-only-pr-full-on-schedule`,
`2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path`). The `run-full-tests`
label is the escalation, and the trunk and the schedule run the full half
without it.

# Rationale

**The cost was not the download, it was the manifests.** Nine of the ten
minutes went into `sdkmanager` talking to Google's repository before it
downloaded anything. A cache does not make the download faster — it removes
the conversation.

**A version-addressed key is the honest one.** The platform package changes
when the version changes and not otherwise, so a key on the version has exactly
the right invalidation. A key on the commit would store a fresh copy on every
push and evict the one before it, which is the failure the Robolectric cache
already had to be fixed for.

**The write policy is the same everywhere.** One rule — the trunk writes, pull
requests read — is what keeps a pull request from being able to break the
trunk's cache. Adding a second policy for one more cache would be a way to
have two answers to the same question.

# Consequences

**The first run after this lands still pays the 626 s.** The cache is empty
until the trunk writes it, and the trunk writes it only on `refs/heads/main`.
A pull request before that pays the install as before — once.

**The key is a literal and has to be bumped with the build.** `compileSdk 37`
with minor 1 is written in `androidApp/build.gradle.kts` and the cache key is
written in the action. A `compileSdk` bump without a key bump does not break
the build — the install step sees a missing directory and installs it — but it
does mean the cache keeps serving the old platform under the old key, and the
new one is downloaded on every run until the key is bumped. The action says so
where the key is.

**The step's name is now slightly narrow.** It installs the platform only when
the cache did not answer; the name still says what the step is for.

# Links

- `.github/actions/setup-android-sdk/action.yml` — the cache, the key, the
  write policy
- `.github/actions/setup-jvm-gradle/action.yml` — the same policy for the
  Gradle home and the Robolectric jars
- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md` — the
  population split
- `docs/decisions/2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md` —
  coverage off the pull-request path
