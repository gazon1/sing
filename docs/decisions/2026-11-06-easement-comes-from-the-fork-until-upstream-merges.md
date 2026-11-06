---
title: "Easement comes from the fork until upstream merges the fix"
date: 2026-11-06
status: superseded
superseded-by: 2026-11-07-easement-comes-from-a-fork-release
tags: ["tooling", "architecture"]
---

> **⚠️ SUPERSEDED** — see
> [2026-11-07-easement-comes-from-a-fork-release](2026-11-07-easement-comes-from-a-fork-release.md).
> The fork publishes a release of its own now, so the tool is taken from that
> release instead of a branch built from source, and the CI gate runs the
> fork's action too.

# Easement comes from the fork until upstream merges the fix

## Context

`easement` is the architecture-boundary gate: it reads the SCIP indexes of
this repository and reports a dependency that crosses a component boundary the
`easement.yml` rules do not allow.

Its resolver had a defect. When the same fully qualified name is declared in
two components, the import of that name was attributed to one copy chosen
globally, so a dependency from one component to the other looked like a
dependency between two unrelated components. In this repository that produced
two false violations — a `SyncState` name and a test helper — that had nothing
to do with the boundary rules.

The fix is upstream pull request
[mokevnin/easement#114](https://github.com/mokevnin/easement/pull/114), open
against `mokevnin/easement` from the fork `gazon1/easement`, branch
`fix/duplicate-declaration-across-components`. It is not merged and not
released, so there is no upstream artefact that carries it.

`mise.toml` pinned the tool to the upstream release:

```toml
"github:mokevnin/easement" = "latest"
```

The `github:` backend of `mise` resolves a version through GitHub **releases**
and their assets. The fork publishes no releases — it has the upstream tags
but no release objects — so pointing the same backend at the fork fails:

```
mise install "github:gazon1/easement@latest"
  No versions found for github:gazon1/easement
mise install "github:gazon1/easement@v0.5.0"
  GitHub release gazon1/easement@v0.5.0 not found (404 from mise-versions.jdx.dev)
```

## Idea

- Install the fork's branch with the `cargo:` backend, which builds from a git
  URL and needs no release.
- Pin the branch explicitly, so the install is reproducible and does not
  silently follow the fork's `main`.

## Decision

`mise.toml` pins the fork's branch:

```toml
"cargo:https://github.com/gazon1/easement" = "branch:fix/duplicate-declaration-across-components"
```

The `branch:` prefix is the `mise` version syntax for a git ref; a bare
branch name is rejected (`Invalid cargo git version: …`), and `rev:` pins a
commit instead. `?branch=…` as a query on the URL is accepted by `mise` but
is not passed to `cargo`, and the build it produces is the fork's `main` — the
defect is still there.

## Rationale

**The gate must not report a violation the code does not have.** A false
violation in the architecture gate is worse than no gate: it teaches the team
to ignore the gate. The fixed binary reports `0 violation(s)` on this tree and
lists the two duplicated names as evidence gaps, which is what they are.

**The fork's `main` is upstream's `main`.** The fix lives on a branch of the
fork, so a `latest` pin to the fork would install the unfixed code. The
branch pin names exactly what is installed.

**`cargo:` needs no release.** It clones the repository and builds it, which
is what a fork without releases allows. The build is a release build of one
crate and takes about a minute.

**The pin is a branch, not a commit, on purpose.** The branch moves when the
fix changes; a commit pin would freeze the artefact and require a manual bump
for every follow-up. `mise upgrade` re-reads the branch.

## Consequences

- The tool installed by `mise` is built from source. The CI runners
  (`ubuntu-24.04`) carry Rust 1.99.1, which is what the build needs, but the
  install is a compile rather than a download.
- The gate in CI still runs the upstream action (`mokevnin/easement@main` in
  `.github/workflows/easement.yml`). Only the tool the local gate and the
  pre-push hook use comes from the fork. The CI action follows upstream until
  the pull request merges.
- When upstream merges the fix and cuts a release, this pin goes back to
  `"github:mokevnin/easement" = "latest"`. The branch in the fork can be
  deleted then.
- A `mise` cache of the fork's git checkout is kept in `~/.cargo/git`; the
  build is redone whenever the branch's head changes.

## Links

- `mise.toml`
- [mokevnin/easement#114](https://github.com/mokevnin/easement/pull/114) —
  the upstream pull request carrying the fix
- `gazon1/easement` — the fork, branch
  `fix/duplicate-declaration-across-components`
