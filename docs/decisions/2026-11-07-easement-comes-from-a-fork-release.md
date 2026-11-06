---
title: "The easement gate comes from a release of the fork"
date: 2026-11-07
status: accepted
tags: ["tooling", "architecture"]
supersedes: 2026-11-06-easement-comes-from-the-fork-until-upstream-merges
---

# The easement gate comes from a release of the fork

## Context

`easement` is the architecture-boundary gate of this repository: it reads the
SCIP indexes of the tree and reports a dependency that crosses a component
boundary the `easement.yml` rules do not allow.

Its resolver had a defect. When the same fully qualified name is declared in
two components, the import of that name was attributed to one copy chosen
globally, so a dependency from one component to the other looked like a
dependency between two unrelated components. In this repository that produced
two false violations — a `SyncState` name and a test helper — that had nothing
to do with the boundary rules. The fix is upstream pull request
[mokevnin/easement#114](https://github.com/mokevnin/easement/pull/114), open
against `mokevnin/easement` from the fork `gazon1/easement`.

The previous decision took the fix from the fork's branch and built it from
source with the `cargo:` backend of `mise`, because the fork published no
release. That left the two places that run the gate on different binaries:
`mise` installed the fixed build, while the CI job ran the upstream action
`mokevnin/easement@main`, which installed the unfixed upstream release. The
build also cost a compile on every fresh install.

The fork can publish a release of its own. `cargo-dist` builds the archives
and the installers from a tag, and `mise` installs a `github:` release as a
download — the same way it installs the upstream release.

## Idea

Publish a release from the fork and take the tool from that release everywhere:
locally through `mise`, in CI through the fork's action.

## Decision

- The fork publishes its own release: `gazon1/easement` **0.5.1** carries the
  fix of #114. Its `install.sh` installs from the releases of the fork, so the
  fork's action installs the fork's binary.
- `mise.toml` pins that release:

  ```toml
  "github:gazon1/easement" = "0.5.1"
  ```

- The CI gate runs the fork's action, over the diff against the base of the
  repository:

  ```yaml
  - uses: gazon1/easement@main
    with:
      diff: origin/main
  ```

- The Rust toolchain is pinned, so a checkout that has to build `easement` from
  source builds it with the version the `easement` repository pins itself:

  ```toml
  rust = "1.99.1"
  ```

## Rationale

**One binary judges the tree, in CI and on a laptop.** The gate that reports a
violation the code does not have is worse than no gate: it teaches the team to
ignore the gate. The local gate and the CI job must be the same judge, and
both must be the fixed one.

**`diff: origin/main` judges the change, not the tree.** The revision is a
revision of *this* repository: the checkout is full (`fetch-depth: 0`), so
`origin/main` is the base to compare with. It is not a revision of the tool.

**A release is a download.** The `github:` backend of `mise` takes the asset of
the release and unpacks it. The `cargo:` backend cloned the repository and
compiled it — a minute of CPU per fresh install, and a toolchain that had to
be present before the gate could run.

**The pin names a version, not a branch.** `0.5.1` is exactly the artefact that
was checked. A branch pin would follow the fork's `main` silently.

## Consequences

- The fork owns a release of its own. `release-please` cannot run there: the
  workflow needs a `RELEASE_PLEASE_TOKEN` the fork does not have. A release is
  therefore made by hand — bump the version in `Cargo.toml`, tag it, push the
  tag; the `dist` workflow builds the assets and undrafts the release. The
  version of the package must match the tag, or `dist` refuses to plan.
- The fork publishes no Homebrew formula and no crate: the upstream tap and the
  `easement` name on crates.io are not ours to publish to. The release ships
  the archives, the installers and the packages.
- The Rust pin is a fallback, not the daily path: a released binary needs no
  toolchain.
- When upstream merges #114 and cuts a release, both pins go back to
  `"github:mokevnin/easement" = "latest"` and the CI action goes back to
  `mokevnin/easement@main`. The fork's release stays where it is; it is not
  deleted.
- The gate over `--diff origin/main` reports `0 violation(s)` on this tree:
  2795 files, 11320 internal deps. The two names that the defect turned into
  violations are evidence gaps, which is what they are.

## Links

- `mise.toml`
- `.github/workflows/easement.yml`
- [gazon1/easement 0.5.1](https://github.com/gazon1/easement/releases/tag/v0.5.1) —
  the release the gate is taken from
- [mokevnin/easement#114](https://github.com/mokevnin/easement/pull/114) —
  the upstream pull request carrying the fix
