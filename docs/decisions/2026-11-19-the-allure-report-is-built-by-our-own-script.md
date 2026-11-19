---
title: "The Allure report is built by our own script, not by a third-party action"
date: 2026-11-19
status: open
tags: ["ci", "allure", "github-pages"]
---

## Context

The report was built by `simple-elf/allure-report-action@v1.15`. That action
is a Docker action: the runner rebuilds its image on every run, from a
floating base image (`ubuntu:latest` plus a `yum -y update`), and the image
then downloads the Allure CLI from Maven Central with `wget` and without a
retry.

Two consequences were observed on 2026-11-19:

- Maven Central answered HTTP 429 and the job failed. The action is not
  fixable from this repository: its `Dockerfile` hard-codes the artifact URL
  and has no retry.
- The action ran as `root` and left everything it created under
  `allure-history/` and `gh-pages/` owned by `root`. The helper that applies
  the publication state runs as the runner user, so every publish run needed
  a `sudo chown -R` repair step before the state could be written.

The action also contributed almost nothing beyond `allure generate`: this
repository already owns the publication policy — the history, the manifest,
the retention, the indexes, the state validation — in `scripts/ci/allure_pages.py`
(`docs/decisions/2026-11-11-the-publication-helper-owns-retention.md`). The
action wrote a hard-coded `executor.json` whose report URL pointed at
`https://gazon1.github.io/sing/<population>` — the address of a project site,
while this repository publishes on a custom domain (`http://maxdrobin.ru/sing/`).

## Idea

1. **Keep the action and retry the job.** The flake stays; a rate limit can
   come back any time.
2. **Keep the action and wrap it in a retry.** A retry of the whole Docker
   build re-downloads the base image and rebuilds it; the expensive part of
   the flake is not retried at all.
3. **Own the build.** Install the Allure CLI ourselves — pinned by version and
   by digest, with retries and a mirror fallback — and generate the report
   with a script that this repository tests.

## Decision

The report is built by `scripts/ci/allure_report_build.py`, and the
third-party action is gone.

- **The CLI is pinned.** `allure-commandline-2.44.0.tgz`, sha256
  `7021a90828c00cd6ec992027cce48e8a94bd87fad43d0c2dcac4795cadc178d7`. The
  digest is verified after the download; a mismatch is a failure, not a
  warning.
- **The download has retries and mirrors.** Three attempts per mirror, and
  three mirrors: `repo.maven.apache.org`, `repo1.maven.org`,
  `maven-central.storage-download.googleapis.com`. The mirrors serve identical
  bytes, so the fallback is a fallback of the same artifact.
- **The extraction is safe.** The archive is refused if a member escapes the
  destination directory or is a link.
- **The build owns the report tree, and only that.** The manifest, the
  retention, the population indexes and the publication state stay with
  `allure_pages.py` (`prepare-namespace` / `decide` / `apply` /
  `validate-state`). The build assembles the tree that `apply` then judges.
- **The publish tree starts as a copy of `gh-pages`**, as the action did. The
  other population, the manifest and the `CNAME` survive in it.
- **The report carries the address the site answers with.** `executor.json`
  is written with the Pages address that `allure_report_link.py` resolves —
  the custom domain, not the address of a project site.
- **Java is set up by the workflow**, not by a container: `actions/setup-java@v6`
  with Java 21 (zulu), before the install.

The Docker-specific repair is gone with the action: nothing runs as `root`
anymore, so the `sudo chown -R` step is removed.

## Rationale

- The report is a build artifact, not a policy decision. This repository
  already owns the policy; the action was the only part of the publication
  that it did not own, and that part was the flaky one.
- A pinned digest is a stronger statement than a pinned version, and it is
  checkable: the helper refuses an artifact whose bytes are not the bytes
  that were reviewed.
- Retries and mirrors are what turns a third party's rate limit into a
  non-event; the action had neither.
- The own script is testable. The action was a black box that could only be
  tested by running it, and the tests of this repository run in seconds.
- Writing the real site address into `executor.json` is a correction, not a
  feature: the report used to advertise an address that does not serve it.

## Consequences

- The runner no longer builds a Docker image for the publication, and no
  longer runs `yum -y update` on the runner's behalf. The job needs a JDK on
  the runner, which `setup-java` provides.
- The report build depends on Maven Central and its mirrors. A failure there
  now fails the job — and the job is advisory
  (`docs/decisions/2026-11-19-the-allure-publication-does-not-block-a-merge-or-a-release.md`),
  so it cannot block a merge or a release.
- The `allure` CLI is a new build dependency of the publication. The version
  is pinned in the helper, and the digest next to it.
- The behaviour of the published tree is unchanged: the same directories, the
  same names, the same retention. Only the producer of the report tree
  changed.

## Links

- Issue: [#3007](https://github.com/gazon1/sing/issues/3007)
- Workflow: `.github/workflows/ci.yml` (`publish-allure`)
- Helper: `scripts/ci/allure_report_build.py`
- Tests: `scripts/tests/test_allure_report_build.py`,
  `scripts/tests/test_allure_publication_workflow.py` (`ReportBuildTest`)
- Prior decisions: `docs/decisions/2026-11-10-allure-publication-latest-history-and-retention.md`,
  `docs/decisions/2026-11-11-the-publication-helper-owns-retention.md`,
  `docs/decisions/2026-11-19-the-allure-publication-does-not-block-a-merge-or-a-release.md`
