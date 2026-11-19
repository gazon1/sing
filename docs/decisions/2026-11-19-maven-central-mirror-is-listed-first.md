---
title: "Maven Central is reached through Google's mirror first, because Gradle fetches artifacts only from the repository that served the metadata"
date: 2026-11-19
status: accepted
tags: ["build", "gradle", "ci", "infrastructure"]
---

# Maven Central is reached through Google's mirror first

## Context

On 2026-11-18 the GitHub-hosted runners stopped being able to download JARs from
`repo.maven.apache.org`. Every CI job that resolved the buildscript classpath
died the same way:

```
> Could not resolve all artifacts for configuration 'classpath'.
> Could not find kover-gradle-plugin-0.9.11.jar (org.jetbrains.kotlinx:kover-gradle-plugin:0.9.11).
  Searched in the following locations:
    https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kover-gradle-plugin/0.9.11/kover-gradle-plugin-0.9.11.jar
```

The same nine artifacts failed every time: the Kover plugin and its runtime,
`kotlin-stdlib`, the IntelliJ coverage agent and reporter, `coverage-report`,
`freemarker`, `annotations`. They are exactly the buildscript classpath of the
settings-level `kover.aggregation` plugin, so the failure happened while
*evaluating settings* — before any task ran.

Two facts make the diagnosis what it is:

- **The artifacts exist.** From a developer machine every one of them answers
  `200`, on `repo.maven.apache.org` and on `repo1.maven.org` alike.
- **Only the JAR is missing.** Gradle resolved the POM and then could not get
  the JAR, and the "Searched in the following locations" list names *only*
  `repo.maven.apache.org` — not the plugin portal, which proxies Central and
  would have served the same coordinates.

That last point is the whole problem, and it is not a Gradle bug: **Gradle
fetches a module's artifacts from the same repository that served its
metadata.** A repository listed *after* Central is therefore never consulted
for a JAR Central could not serve. The failure looks like "the artifact does
not exist" because, as far as the repository that answered for the module,
it does not.

A re-run of the failed jobs on 2026-11-19 reproduced it identically, so this
is a standing block and not a transient outage of a few minutes.

The cost was not cosmetic: `ci.yml` was red on `detekt`, `Android (free)`,
`Android (pro)`, `Static gates` and `Gate tooling`, and `release.yml`'s
`android` job could not build an APK at all. That matters beyond CI, because
`release.yml`'s `meta` gate refuses a tag whose commit has no successful
`ci.yml` run — so no release could be cut while the runners could not resolve
Central.

## Decision

- **Google's official Maven Central mirror
  (`https://maven-central.storage-download.googleapis.com/maven2/`) is listed
  BEFORE `mavenCentral()`** in both `pluginManagement.repositories` and
  `dependencyResolutionManagement.repositories` in `settings.gradle.kts`.
- **`mavenCentral()` stays in the list**, as the fallback for whatever the
  mirror does not carry. Central remains the authority; the mirror is the
  front door.
- **The Gradle plugin portal stays last.** Plugin markers that exist only
  there — the foojay toolchain resolver — are absent from the mirror, and
  Gradle falls through the list to the portal for them. (Verified: the
  mirror answers `404` for the foojay marker and `200` for every artifact
  that is actually on Central.)
- **A gate that fetches an artifact itself uses the same order.**
  `scripts/check-quality-thresholds.py` downloads the pinned `detekt-core`
  jar over HTTP, so Gradle's repository order does not apply to it: it reads
  the mirror first and falls back to Central. Without that, the runners'
  block reached this gate too, and it failed with a message about a missing
  artifact — the network's mood wearing the costume of a verdict about the
  tree.

Ordering is the entire mechanism, so it is not a detail: a mirror listed
*after* Central changes nothing, because the failing JARs are never looked up
in it.

## Rationale

The alternative was to leave the build alone and wait for Maven Central to
serve the runners again. That was measured, not assumed: the failed jobs were
re-run on 2026-11-19 and failed identically, so "wait for it to come back" was
not a plan but a hope.

The second alternative — putting the mirror *after* Central, as a plain
fallback — was rejected by the mechanism above: it cannot work. The mirror
had to go first.

Google operates this mirror as a full, first-party copy of Central, and it
answers `200` for every artifact this build needs. Listing it first costs
nothing when Central is reachable (both serve the same bytes) and unblocks
the build when it is not.

The scope was deliberately kept to `settings.gradle.kts`. A CI-only init
script would have left a developer on a machine with the same block building
against a different set of repositories than CI does — two definitions of
where dependencies come from, which is the "two homes for one fact" defect
this repository keeps rejecting.

## Consequences

- **Builds no longer depend on `repo.maven.apache.org` being reachable from
  the runner.** The runners' failure mode was invisible: the error names a
  missing artifact, not a blocked network, which is why it read as "these
  versions do not exist" rather than "the network is down".
- **The mirror is now in the resolution path of every build**, including
  developer machines. If it ever diverges from Central, the fallback order
  means Central answers for whatever the mirror lacks — but a mirror that
  serves a *different* artifact for the same coordinates would be chosen
  first, and nothing in this repository would notice.
- **The failure message is still misleading if the mirror goes down the same
  way.** The mirror is a second host, not a second copy of the same
  infrastructure, so this is a narrower single point of failure than it
  looks — but it is a point of failure.
- **The setting-level Kover plugin is what made this visible.** It is
  applied in `settings.gradle.kts`, so its classpath is resolved before any
  task and before the Gradle cache can be warm — a plugin applied in a
  project build script would have failed later and less loudly.
- **A gate that downloads an artifact is a second place where the network
  decides the verdict.** `scripts/check-quality-thresholds.py` fetches the
  pinned `detekt-core` jar itself, because the job that runs it resolves no
  detekt artifact of its own. It now reads the mirror first, so a runner
  that cannot reach Central still gets an answer about the tree. Any future
  script that reaches the network on its own has to make the same choice:
  Gradle's repository order does not apply to it.

## Links

- `settings.gradle.kts` — `pluginManagement.repositories`,
  `dependencyResolutionManagement.repositories`
- `.github/actions/setup-jvm-gradle/action.yml` — the cache policy that hid
  most dependencies from this failure
- Issue #3007 — the release whose APK could not be built while this was true
