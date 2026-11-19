---
title: "The easement gate comes from upstream again"
date: 2026-11-19
status: open
tags: [tooling, architecture]
supersedes: 2026-11-07-easement-comes-from-a-fork-release
---

# The easement gate comes from upstream again

## Context

The fork `gazon1/easement` existed for one reason: upstream pull request
[mokevnin/easement#114](https://github.com/mokevnin/easement/pull/114) — the fix
for a duplicated declaration across components — was not in an upstream release.
The decision `2026-11-07-easement-comes-from-a-fork-release.md` took the tool
from the fork's own release `0.5.1`, in `mise.toml` and in the CI job, so that
one binary would judge the tree in both places.

Upstream merged #114 and cut releases that carry it: 0.6.4 and later. The
fork's raison d'être is gone.

Two things had meanwhile drifted. The CI job still called
`gazon1/easement@main` with a hand-written `version: 0.5.1`, while `mise.toml`
pinned `"github:mokevnin/easement" = "latest"` — a name, not a version. The
repository's own `scripts/toolchain-version.py` rejects `"latest"` as "not a
version", and it is the one reader of that pin. The tool version was written
in two places, and one of them was not a pin.

## Idea

Take the tool from upstream again, and write the *binary's* version down in
exactly one place — `mise.toml` — the way the ktlint installer already does.

## Decision

- `mise.toml` pins the upstream release:

  ```toml
  "github:mokevnin/easement" = "0.9.0"
  ```

  A fixed version, not `"latest"`: this file is the one place the repository
  writes a tool version down, and `scripts/toolchain-version.py` reads it from
  here.

- The CI job calls the upstream action and asks the repository for the version:

  ```yaml
  - id: easement-version
    name: Read the pinned easement version out of mise.toml
    run: echo "version=$(python3 scripts/toolchain-version.py 'github:mokevnin/easement')" >> "$GITHUB_OUTPUT"
  - uses: mokevnin/easement@v0.9.0
    with:
      version: ${{ steps.easement-version.outputs.version }}
      diff: origin/main
  ```

- The fork's release stays where it is. Nothing is deleted from
  `gazon1/easement`.

## Two pins, two different things

The workflow carries two references, and they are not two versions of the same
thing. The upstream action's contract at `v0.9.0` makes the difference explicit:

| Reference | What it pins | Upstream's own words |
|---|---|---|
| `uses: mokevnin/easement@v0.9.0` | the **Action implementation** — the composite action and the `install.sh` it runs | a git tag on the action repository |
| `with: version:` | the **binary** the action installs and then runs | "easement version to install, such as 0.2.0. The latest release by default." |

The action hands `with.version` to `install.sh -v "$VERSION"`, and `install.sh`
is generic across releases: it turns the version into
`releases/download/v<version>/easement-<arch>-<os>.tar.xz`, fetches that
release's `sha256.sum`, verifies the archive against it, and installs the
binary. The action tag therefore does **not** constrain the binary version, and
the two pins are independent knobs.

So the statement is narrower than "one source of truth":

- **one source of truth for the binary version** — `mise.toml`, read by
  `scripts/toolchain-version.py`. A laptop and CI install the same bytes;
- **a separate pin for the Action implementation** — the `uses:` tag, which
  chooses which version of the install script runs.

They currently carry the same number, `0.9.0`, but that is a coincidence of
convenience, not a coupling: bumping the binary in `mise.toml` alone changes
what CI runs and leaves the action alone, and bumping the action tag alone
changes the installer without changing the judge. The two are allowed to
differ, and a mismatch is a deliberate choice rather than an accident.

### What was verified

- `refs/tags/v0.9.0` exists in `mokevnin/easement`, and the release `v0.9.0`
  publishes `easement-x86_64-unknown-linux-gnu.tar.xz` and `sha256.sum`.
- `action.yml` at that tag declares the `version` input, and its install step
  passes it to `install.sh -v "$VERSION"`.
- The binary mise installs is the binary CI installs: the mise-installed
  `easement` and the binary inside the upstream `v0.9.0` archive have the same
  sha256 (`a615a0df6d6820ee8d3d76742be0fe7cae85c63ba7bc9aa8811b5479c30fe55d`),
  and the archive itself matches the checksum in the release's `sha256.sum`.
- Changing only `mise.toml` propagates to CI with no other edit: the
  `easement-version` step reads the pin at run time and passes it through
  `with.version`.

## Rationale

**The fork's reason to exist has expired.** PR #114 shipped upstream in 0.6.4.
Keeping a fork means keeping a release that must be cut by hand, for a fix that
is now in the upstream release. The fork's own rule went further than upstream's
— it dropped the other candidate copies instead of keeping an over-approximated
edge to them — but that difference only shows up on a tree that really does
declare one name in two components, which is a defect to fix in the code
(`2026-11-19-every-declaration-gets-one-home.md`), not a reason to carry a
fork.

**A version written twice drifts.** The ktlint installer already reads its
version out of `mise.toml` through `scripts/toolchain-version.py`. The easement
job wrote `0.5.1` by hand next to a `"latest"` in `mise.toml`; those two had
already stopped meaning the same thing. Reading the pin removes the second
place to update.

**A pin is a version.** `"latest"` follows the next release silently, and
`toolchain-version.py` — the repository's own rule — does not accept it as a
pin. `0.9.0` names the artefact that was checked.

**The gate must not move under the tree.** The upstream tool reports the
`shared → desktopApp` cycle that the fork's did not, because upstream keeps an
edge to every candidate of an ambiguous name while the fork drops them. That
difference only shows up where a name is declared twice — and where it is
fixed in the code, both binaries report `0 violation(s)`.

## Consequences

- `gazon1/easement` is no longer called by `mise.toml` or by CI. Its release
  `0.5.1` stays published.
- The CI job installs the binary version `mise.toml` names. Bumping the tool
  is a one-line change in `mise.toml`, and the CI job follows it — the action
  tag stays where it is, because it pins the installer and not the judge.
- Two pins remain in the workflow, and they mean different things. A reader who
  sees `v0.9.0` twice should not conclude the version is written twice: one is
  the action, the other the binary.
- A developer on an upstream `easement` older than 0.6.4 still sees the tree
  as clean.
- The decision `2026-11-07-easement-comes-from-a-fork-release.md` is
  superseded. What it got right stands: the tool is taken from a release
  rather than a source build, and the pin lives in one file.

## Links

- `mise.toml`
- `.github/workflows/easement.yml`
- `scripts/toolchain-version.py` — the one reader of the pin
- `2026-11-07-easement-comes-from-a-fork-release.md` — the decision this one
  supersedes
- `2026-11-19-every-declaration-gets-one-home.md` — the code fix the tool was
  tripping over
- [mokevnin/easement#114](https://github.com/mokevnin/easement/pull/114) —
  the upstream pull request that shipped in 0.6.4
