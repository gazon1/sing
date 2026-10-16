---
title: "The release gate reads a committed snapshot, not GitHub"
date: 2026-10-16
status: accepted
deciders: max
tags: ["release", "ci", "gates"]
---

## Context

`.github/workflows/release.yml` has two jobs that never once agreed with each other,
and nothing checked them:

- `meta` proves a tag sits on `main` with a successful `ci.yml` run for that commit.
  It says nothing about whether a release came out of it.
- `publish` writes `MANIFEST.txt` and `SHA256SUMS.txt`, then attaches whatever is in
  `dist/`. Nothing compared the manifest against the attached files, and nothing
  compared the checksum file against the manifest.

`publish` had never run. Its manifest step computed `files` *inside* a heredoc — so it
wrote the shell source of the command into a data file and left `files=` blank
(#673). The checksums were computed before the manifest existed, so the manifest would
have shipped unsigned. Both defects were invisible precisely because no gate had
anything to say about a release that had never happened.

Two tags, `v0.1.0` and `v0.2.0`, are already tagged and will never have a release.
Their runs died in `meta`, and CI could not install itself at those commits (#635), so
the runs that would have accepted them cannot be reproduced.

## Idea

Ask GitHub, live, on every gate run: "does each `v*` tag have a release, and does that
release's manifest match its assets?"

## Decision

No. `scripts/check-release-tags.py` reads a committed snapshot,
`config/docs/releases-snapshot.json`, refreshed by hand via `--refresh`. It states two
rules:

- **R1** — every tag in the snapshot has a release, *unless* it is listed in
  `tags_without_release` with a reason. v0.1.0 and v0.2.0 are listed, with the exact
  `meta` error each one produced.
- **R2** — each release's `MANIFEST.txt` version and file list, and its
  `SHA256SUMS.txt` coverage, match the assets actually attached, and its `prerelease`
  flag matches the tag.

`release.yml` gains a second refusal next to the "tag must be on main" check: if
`gh release view <tag>` already answers, the run stops. A draft counts — a force-moved
tag with a release built from an earlier commit is worse than a failed build, because
the tag and the artifacts disagree and nothing reports it.

## Rationale

A snapshot runs the same way on a developer machine with no token, in `check.sh`, and in
CI — the same arrangement `check-backlog-issue-refs.py` uses, for the same reason. A
gate that needs credentials is a gate nobody runs locally.

R1 without `tags_without_release` would be red by construction: two tags can never
acquire a release, and a gate that can never go green is the same debt as the
always-red advisory gates this repository is retiring (#667). The exceptions are
therefore data, not code, and `--refresh` **preserves them** — GitHub knows that v0.1.0
produced nothing, never *why*, and a refresh that dropped the reasons would convert each
one into a red finding and send the next person hunting for a reason that no longer
exists anywhere.

The gate does not download and re-hash the binaries. That is 380 MB per release, and
the hashes were computed at build time from those same files: recomputing them proves
the upload, not the release. What it checks is the claim each release makes about
itself — the part written by hand.

## Consequences

- A new tag that produces no release is caught by the next gate run.
- Publishing a release means refreshing the snapshot. That is a deliberate, visible step
  between "tag pushed" and "release described".
- `check-release-tags.py` has a positive control (`release-tags` in
  `check-gate-wiring.py`'s `SCRIPT_GATES`): appending a tag that is neither released nor
  excused must make the gate fail.
- The release-tag refusal in `meta` means re-running an existing tag requires deleting
  the release first. That is the intended cost: the alternative is artifacts that
  disagree with the tag they are named after.

## Links

- `.github/workflows/release.yml` — the `meta` refusals
- `scripts/check-release-tags.py`, `config/docs/releases-snapshot.json`
- `scripts/tests/test_check_release_tags.py` — the rules as data
- `docs/decisions/2026-10-16-desktop-package-version-must-satisfy-rpm-and-deb.md`