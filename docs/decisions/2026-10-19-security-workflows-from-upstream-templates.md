---
title: "Security scanning is taken from the maintained upstream workflows, and runs on its own schedule"
date: 2026-10-19
status: accepted
tags: ["ci", "tooling", "security"]
---

## Context

The repository had six workflows of its own — `ci.yml`, `e2e.yml`,
`android-device-tests.yml`, `flake.yml`, `release.yml`, `pr-cache-cleanup.yml` —
and no security scanning at all. Measured on 2026-10-19: the code-scanning
alerts endpoint answers `no analysis found`, and the dependency-graph SBOM
endpoint answers `404`.

GitHub maintains a set of ready-made workflows at
`actions/starter-workflows`. Four of them were relevant to this repository, and
they are not interchangeable:

| Upstream template | Fit for this repository |
|---|---|
| `code-scanning/codeql.yml` | **Adopted** — the repository's sources are Kotlin and Java. |
| `code-scanning/scorecard.yml` | **Adopted** — reads the repository and its workflows; builds nothing. |
| `code-scanning/dependency-review.yml` | **Not adopted** — the dependency graph is not enabled for this repository; the action would fail on every pull request. |
| `code-scanning/detekt.yml` | **Not adopted** — it downloads the detekt CLI `v1.15.0` and runs it over the tree, duplicating `:shared:detekt` (which runs the project's own custom rule set) with a five-year-old release. |

The `ci/gradle.yml` and `ci/android.yml` templates are likewise not adopted:
`ci.yml` already builds and tests the project, with more instrumentation than
the template has.

Two facts about this repository shaped the two that were adopted:

- **The default `GITHUB_TOKEN` permission is `read`** (`default_workflow_permissions`).
  A workflow that declares `permissions:` explicitly overrides that default, so
  `security-events: write` is available to these two workflows and to no others.
- **The nightly crons are taken.** `ci.yml` runs at 01:00 UTC, `e2e.yml` at
  02:00, `android-device-tests.yml` at 03:00 and `flake.yml` at 04:00.

## Idea

Take the maintained upstream workflow for each capability the repository lacks,
resolve the template placeholders, and adapt the trigger set to the repository's
own schedule — rather than hand-rolling an equivalent.

## Decision

**Add `codeql.yml` and `scorecard.yml`, both adapted from the upstream
templates, both outside the pull-request trigger set.**

- `.github/workflows/codeql.yml` — CodeQL for `java-kotlin`, `build-mode: manual`
  over `:shared:compileKotlinJvm :core-base:compileKotlinJvm`, on `push` to
  `main`, on `schedule` (`0 5 * * 1`), and on `workflow_dispatch`.
- `.github/workflows/scorecard.yml` — OpenSSF Scorecard, on `push` to `main`, on
  `schedule` (`0 6 * * 1`), and on `workflow_dispatch`.

**Neither workflow runs on `pull_request`.** The repository's pull-request
pipeline is a single blocking check (`CI gate`) plus the jobs it aggregates;
adding two more jobs to it would put a security scan and a supply-chain scan in
front of every contribution, and neither of them is a verdict on the change
being proposed. Both scans read the repository as a whole — the supply chain,
the branch protection, the pinned dependencies — so a per-pull-request trigger
would re-answer the same question on every push. The scans answer it on a
schedule, and a finding is opened against `main`, which is where it belongs.

**`build-mode: manual`, with a build command the repository already proves.**
`none` was tried first and failed on this repository: the Java/Kotlin extractor
found the code and processed none of it — *"CodeQL detected code written in
Java/Kotlin but could not process any of it using the 'none' build mode"*. The
sources live in `src/commonMain/kotlin` and `src/jvmMain/kotlin`, not in the
`src/main/java` layout the no-build mode looks for. `autobuild` would run
`./gradlew build` — the whole project, tests and detekt included — which turns
a security scan into a second copy of the test pipeline. `manual` builds
`:shared:compileKotlinJvm :core-base:compileKotlinJvm`: the two modules that
hold the app's Kotlin code, with a task name `ci.yml` already runs. The
extractor sees the compiled classes and maps them back to sources.

**The cron slots are after the nightly chain** — 05:00 for CodeQL, 06:00 for
Scorecard — so a scan never competes with a build for a runner.

## Rationale

- **A maintained workflow is worth more than a hand-written one.** Both files
  are the upstream templates with the placeholders resolved
  (`$default-branch`, `$protected-branches`, `$cron-weekly`,
  `$codeql-languages-matrix`). The analysis, the SARIF upload and the permission
  set come from the people who maintain them; what is written here is the
  schedule and the trigger set, which is the part that is specific to this
  repository.
- **The two that were not adopted were not adopted for a measured reason, not a
  preference.** Dependency review needs a dependency graph, and this repository
  does not have one — the SBOM endpoint answers `404`, so the action would fail
  on every pull request and teach everyone to ignore it. The detekt template
  runs a detekt release from 2021 over a project whose detekt configuration is
  the one its own gate enforces; a second verdict from an older version of the
  same tool is noise.
- **A scan that gates every pull request is a scan people disable.** The
  repository's rule is that the pull-request pipeline has one blocking check.
  These two are not checks on the change; they are checks on the repository.
- **The upstream template is quoted, not paraphrased.** The header of each file
  names the template it came from, so the next person to compare it against
  upstream can see exactly what was changed and why.

## Consequences

- **Code scanning is switched on for this repository** — the first analysis runs
  on the next push to `main`, and the alerts appear in the repository's Security
  tab. Findings are code-scanning alerts, not build failures.
- **The Scorecard badge becomes available** (`publish_results: true` publishes
  to the OpenSSF REST API, which is what a badge reads).
- **Two more workflows read the repository weekly.** Neither builds the project,
  and neither writes to it.
- **The dependency-review capability is still missing**, and it will stay
  missing until the dependency graph is enabled for the repository. The upstream
  template is the one to add at that point, not a substitute written here.
- **The upstream templates are not tracked automatically.** They are copied at a
  point in time, and the headers say which version. A later upstream release is
  a deliberate diff, not an automatic one.

## Links

- `.github/workflows/codeql.yml` — the CodeQL workflow
- `.github/workflows/scorecard.yml` — the Scorecard workflow
- `https://github.com/actions/starter-workflows` — the upstream templates
- `.github/workflows/ci.yml` — the pull-request pipeline the two deliberately
  stay out of
