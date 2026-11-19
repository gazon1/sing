---
title: "A gate that measures the network is not a gate"
date: 2026-11-19
status: open
tags: [tooling, ci, quality]
---

# A gate that measures the network is not a gate

## Context

`scripts/check-quality-thresholds.py` verifies that the quality page states
the thresholds the tree actually enforces. To know the pinned detekt's
*default* values it needs `detekt-core-<version>.jar`. It looks for that jar
in a Gradle home first, and downloads it from Maven Central when no Gradle
home has it.

The CI job that runs this gate — "Gate tooling" — has no Gradle home. It
therefore always takes the download path.

On 2026-11-19 that job failed on CI with:

```
FAIL quality page states the enforced thresholds (exit 2)
check-quality-thresholds.py: the default configuration of detekt 2.0.0-alpha.6
was not found in a Gradle home and could not be fetched from
https://repo1.maven.org/maven2
```

Two tests failed because of it: the registry's real-cluster control and the
"every member runs" control. The same commit's "Static gates" job — which
*does* have a Gradle home — passed the same check.

The green run of the same job, on byte-identical scripts, was 66 minutes
earlier. `scripts/check-quality-thresholds.py`, `gradle/libs.versions.toml`
and `.github/` are all byte-identical between the green run `44413320022` and
the red run `44423123696`. Maven Central was reachable at the moment of
triage: the jar returned HTTP 200, 271725 bytes.

So the gate's verdict depended on whether the CI runner's network was up at
that instant, not on the state of the tree.

## Idea

A network call in a gate is legitimate — the gate genuinely needs an
artifact it does not ship. What is illegitimate is letting a *transient*
network failure decide the verdict. The artifact's absence is a fact; a
dropped connection is not.

## Decision

**A failed download is retried before the gate gives up.** The download is
attempted up to `FETCH_ATTEMPTS` (3) times, with `FETCH_RETRY_DELAY_SECONDS`
(2.0) between attempts.

**A 404 ends the attempts at once.** A 404 is the server stating that the
artifact does not exist; repeating the request cannot change that answer,
and retrying a missing artifact only delays the failure.

**Every other failure is worth another try.** A connection reset, a refused
connection, a 503 — these are facts about the network, not about the
artifact.

**The gate still fails when the artifact is genuinely unreachable.** The
retries do not make the gate lenient about a real absence; they only stop it
from reporting the network's mood as the tree's state.

**The `HTTPError` is closed after it is read.** `HTTPError` wraps a
temporary file; leaving it unclosed leaks a descriptor per attempt.

## Rationale

The alternative to retrying was either to accept the flakiness (a gate that
fails a CI run over a blip it did not cause) or to cache the jar in CI (which
changes the CI configuration and puts a binary in the repository's care for a
single job).

Retrying is the smallest change that makes the verdict a property of the
tree. The cost is bounded: at most three attempts and two sleeps of two
seconds, and only on the failure path.

The 404 carve-out matters because a genuinely missing artifact — a wrong
pinned version, a typo in the catalog — must still fail fast and clearly
rather than after three round trips.

## Consequences

- A transient network failure no longer fails the "Gate tooling" job.
- A missing artifact still fails it, and now does so in about two seconds
  instead of after a minute-long timeout.
- The retry cost is paid only on failure.
- The gate still depends on the network on CI. This decision makes the
  dependency tolerable, not absent — a job with a Gradle home still does not
  need the network at all.

## Links

- `scripts/check-quality-thresholds.py` — `_fetch_detekt_core`, `_download`
- `scripts/tests/test_check_quality_thresholds.py` — `DownloadRetryTest`
- `.github/workflows/ci.yml` — the "Gate tooling" job, which has no Gradle home
