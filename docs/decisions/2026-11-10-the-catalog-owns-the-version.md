---
title: "The version catalog owns the versions; the auto-merge waits for a green CI gate"
status: accepted
date: 2026-11-10
tags: [build, gradle, dependabot, ci, kotlin]
---

# Context

A dependabot bump of Kotlin took main red, and it did not take main red by
accident — three separate defects lined up, and each of them alone would have
been survivable.

1. **The Kotlin version lived in two places.** `gradle/libs.versions.toml` had
   `kotlin = "2.4.10"`, and `gradle.properties` had `version.kotlin=2.4.10`.
   Dependabot edits the catalog and cannot edit the property, so the bump moved
   one of them. The gate `scripts/build-version-catalog-gate.py` compared the
   two and failed with `version.kotlin=2.4.10 != libs.versions.toml
   kotlin="2.4.21"` — a red check on a pull request whose *intent* was correct.

2. **Nothing said which Kotlin the detekt build can take.** detekt
   `2.0.0-alpha.6` was compiled with Kotlin 2.4.10 and refuses to run against
   any other version (`detekt was compiled with Kotlin 2.4.10 but is currently
   running with 2.4.21`). This is a hard constraint, recorded in
   `2026-10-08-kotlin-2-4-upgrade.md` as the reason Kotlin 2.4.20 was not
   chosen — but it lived in that ADR's prose, where a dependabot bump would
   never read it.

3. **The auto-merge merged a pull request whose CI was red.** This repository
   has no branch protection (`2026-10-07-branch-protection-is-unavailable.md`),
   so `.mergeable_state` is not a statement about the checks: it is `clean`
   when nothing is required and `unstable` while a check fails. The workflow
   accepted both — it printed "branch protection satisfied" — and merged. The
   pull request went red, main went red with it, and a `GITHUB_TOKEN` push does
   not trigger the `push` workflow, so nothing ran on main to report it.

The symptom was "main is red"; the disease was that a red pull request could be
merged at all, and that the Kotlin version had two addresses.

# Idea

Three changes, each small, each closing one of the defects:

- **One address for the version.** The catalog is the single source of truth.
  `gradle.properties` holds no version, and the build reads the catalog through
  `VersionCatalogsExtension` — the API accessor generation does not touch, so
  the shadowing that forced the duplication in the first place is bypassed
  rather than worked around.
- **The constraint where the bump happens.** The gate learns a detekt↔Kotlin
  compatibility table. A Kotlin version the table does not cover is rejected
  with a message that names both versions and says what to do, instead of
  failing inside the detekt task with an ABI error.
- **The merge waits for the real check.** The auto-merge workflow requires the
  `CI gate` check-run on the pull request's head commit to have concluded
  `success`. That is the thing that is green or not; `.mergeable_state` is not,
  in a repository without branch protection.

# Decision

**The catalog owns the versions.** `version.kotlin`,
`version.kotlinSerialization` and `version.kotlinxCollectionsImmutable` are
deleted from `gradle.properties`. The root `build.gradle.kts` reads them from
the catalog:

```kotlin
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val kotlinVersion: String = versionCatalog.findVersion("kotlin").get().requiredVersion
```

`requiredVersion` and not `version`: in a Kotlin DSL script `version` resolves
to `PluginDependencySpec.version(...)`, and the call does not compile. The same
two lines exist in `build-logic/convention` — that plugin is not wired into
`settings.gradle.kts` yet, but it read the same properties and would have broken
the day it is.

**The gate owns the constraint.** `scripts/build-version-catalog-gate.py` gains
`DETEKT_KOTLIN_COMPATIBILITY` — a table of the Kotlin version each detekt build
was compiled with. Two rules follow:

- A Kotlin version that is not the one the catalog's detekt was compiled with is
  a violation, and the message says which detekt, which Kotlin, and what to do
  instead.
- A detekt version that is not in the table is a violation too. Bumping detekt
  is a decision that has to name the Kotlin it was built with; a version range
  cannot stand in for that.

The old `gradle.properties` ↔ catalog comparison becomes a duplication check: the
property must not exist at all. Two places holding the same value are still two
places, and the values are exactly what drift. The rule is the `version.` prefix
and not a list of the three keys that were there: a list catches only the
versions the rule was written against, and the next one to be added would slip
through. `versionCode` is not a version the catalog owns, and the prefix leaves it
alone.

**The auto-merge waits for the check-run.** `.github/workflows/dependabot-auto-merge.yml`
reads the `CI gate` check-run on the head commit instead of `.mergeable_state`.
`mergeable_state` still has a job — it says whether the pull request is
mergeable at all (`dirty`, `behind`, `unknown`) — but it no longer stands in
for the checks. The read needs the `checks` permission, which is a scope of its
own: naming any permission in a workflow's `permissions:` block turns every
unnamed one to `none`, so the workflow asks for `checks: read` explicitly.
Without it the API answers 403 and the step aborts — the merge would never
happen, which is the opposite of what the check is for.

# Rationale

**Why the catalog and not the properties.** The catalog is what dependabot
edits, and it is what the rest of the build already reads. Making it the only
place removes a class of defect rather than a single instance: there is no
second address to forget, so no future bump can half-apply. The duplication
existed for a real reason — `libs.versions.kotlin` does not resolve, because
the `kotlin-serialization` version key generates a nested accessor that shadows
it — but `VersionCatalogsExtension` is not an accessor and is not affected. The
workaround became unnecessary the moment the problem was named precisely.

**Why the compatibility table and not a version range.** detekt's check is an
exact match against the compiler it was built with, so a range would be a
guess. A table of known-good pairs is also the honest shape of the knowledge:
the repository knows that detekt `2.0.0-alpha.6` works with Kotlin 2.4.10, and
it does not know what the next detekt will be built with. Making an unknown
detekt a violation is deliberate — it is the point at which a human has to look
at a release note, and it is far cheaper there than in a broken build.

**Why the check-run and not branch protection.** Branch protection is not
available for this repository, and
`2026-10-07-branch-protection-is-unavailable.md` records why. The next best
thing is the workflow refusing to merge on its own, and the check-run is the
only signal that survives the absence of protection: `CI gate` is the single
required check, and its conclusion is per-commit, so it answers exactly the
question — "is this pull request's own head commit green?" — that
`.mergeable_state` was being asked to answer and could not.

**What this does not fix.** The squash merge creates a commit that no CI run
ever saw: CI ran on the pull request's head, not on the merged result. A merge
queue would close that gap, and that is a larger change than this one.

# Consequences

- A Kotlin bump is one edit in `gradle/libs.versions.toml` — and it is refused
  until detekt ships a build that supports it, with a message that says so.
- The versions are read from the catalog in two places (the root build script
  and the unwired convention plugin). A third would be a third copy.
- `gradle.properties` no longer contains a version. The gate fails if one comes
  back — any key under the `version.` prefix, not the three it knows by name.
- A dependabot pull request with a failing `CI gate` is no longer merged. It
  waits — the workflow polls for 30 minutes and then fails the job rather than
  merging something red.
- The table has to be extended by hand when detekt moves. That is the cost, and
  it is the price of the check being a fact rather than a guess.
- The pull request that red-lined main is not re-run; main is green by the
  merge of `2026-11-10`'s sibling change, and the Kotlin version stayed at
  2.4.10.

# Links

- Files: `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`,
  `build-logic/convention/src/main/kotlin/CommonDepsConventionPlugin.kt`,
  `scripts/build-version-catalog-gate.py`,
  `.github/workflows/dependabot-auto-merge.yml`
- Tests: `scripts/tests/test_build_version_catalog_gate.py`
- Related: `2026-10-08-kotlin-2-4-upgrade.md`,
  `2026-09-18-version-catalog-cleanup.md`,
  `2026-10-07-branch-protection-is-unavailable.md`
