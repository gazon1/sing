---
title: "The gate tooling is installed by mise, and mise.toml holds the pins"
date: 2026-11-09
status: accepted
tags: ["tooling", "gates", "ci"]
supersedes: 2026-10-20-the-toolchain-pins-live-in-one-file
---

# The gate tooling is installed by mise, and mise.toml holds the pins

## Context

The pins of the gate tooling used to live in `config/toolchain-versions.env`:
plain `KEY=VALUE` lines that two install paths read —
`scripts/ci/setup-local-tools.sh` for a developer and
`.github/actions/setup-python-tools` for a runner. The decision that put them
there (ADR `2026-10-20-the-toolchain-pins-live-in-one-file.md`) was about
*where a pin lives*, and it was right about the shape: one file, two readers, a
test that fails when the version is written a second time.

Then the installers changed. `mise` took the tooling over: `mise.toml` pins
every external binary the gates shell out to, and `mise install` puts them on
`PATH` — on a developer machine and on a runner alike, from the same file. The
two install paths that the pins file existed for are gone. What was left was a
file nobody read:

- `setup-local-tools.sh` creates the venv and links the binaries mise
  installed; it never reads the pins.
- the composite action installs through `jdx/mise-action`; the pins file is
  mentioned in its description and used nowhere.
- the pins and mise had already drifted apart: the file pinned the PyPI wheel
  `shellcheck-py` at one version while mise installed the Rust binary at
  another, and the actionlint pin was a release tag where mise holds a plain
  version.

So the pins file was a second copy of a fact that mise.toml already held — the
exact failure the earlier ADR was written to prevent, wearing the clothes of
the fix. The test caught it: `scripts/tests/test_toolchain_pins.py` held the
two install paths to reading the file, and it went red on thirteen cases when
main moved the installation to mise.

## Idea

One file holds the pins — the file the installer reads. Shell code that needs
a version asks for it instead of keeping its own copy.

## Decision

**`mise.toml` is the one place the gate tooling's versions are written down.**
mise installs from it, so the local run and the CI run of a gate use the same
bytes without anyone copying a version anywhere.

**`config/toolchain-versions.env` is deleted.** It was a projection of a file
that did not exist yet; a projection of `mise.toml` would be a second copy
again, one that has to be regenerated and kept in step.

**A shell script that needs a version asks for it.**
`scripts/toolchain-version.py <tool>` prints the version mise pins for a tool,
read out of `mise.toml` with the standard library only (the caller is often a
CI job whose Python carries none of the project's dependencies). It refuses a
tool that is not pinned, and a pin that is not a fixed version — `latest` is
not a version.

- `scripts/ci/install-ktlint.sh` takes the version as an optional argument;
  without one it reads the pin out of `mise.toml`. The autofix job calls it
  without a version, so the formatter in CI is the version mise pins and the
  gate expects.
- `scripts/check-doc-links.sh` names the fix for a missing `lychee` without
  repeating a version.

**`scripts/tests/test_toolchain_pins.py` holds the arrangement in place**, with
`mise.toml` in place of the pins file: every gate tool has a fixed version
there, neither install path repeats a version literal, no version literal
appears anywhere else in the repository, and the resolver answers with the
pinned version. The allowlist of files that may name a version (a skill page,
an ADR, a change proposal, the version catalog) is unchanged in kind.

## Rationale

The earlier decision's reason was that a fact with two copies diverges, and
that the copy has to be the one the installer reads. Both halves still hold.
What changed is which file the installer reads: it used to be a shell script
that read a pins file, and now it is mise, which reads a TOML file. Following
the decision to its end means the pins live where the installer looks for them.

The alternative — keeping the pins file as a *generated* mirror of `mise.toml`
— was considered and rejected. It would have satisfied the letter of the old
contract (a shell-readable file, kept in step by a generator and a check), but
it keeps a second copy of every version alive for the sake of two readers: one
that only wanted to name a version in an error message, and one that wanted to
install ktlint. A copy that exists to be regenerated is a copy that will be
stale in the moment somebody forgets the generator, and the failure it produces
is the one this ADR set out to remove: the formatter runs a version the gate
does not expect.

A shell cannot read TOML, so the version has to travel through something. A
script that answers the question is that something, and it is testable: the
pins test asserts that the answer is the pin.

## Consequences

- A version bump is one edit, in `mise.toml`, and nothing else can disagree
  about it: there is no second copy to forget.
- `scripts/toolchain-version.py` is a new file with one job. It is the only
  place the TOML is read from a shell, and it reads it with the standard
  library, so it runs on a runner with no project dependencies installed.
- The install paths no longer "read the pins file", because there is no pins
  file. The test that held them to it now holds them to *not repeating a
  version* — the same property, asked of the file that actually installs.
- A tool that mise pins as `latest` has no version to ask for. The resolver
  refuses it, and the pins test requires a fixed version for every tool the
  gate suite shells out to.
- The mise-action call names no version of mise. The action interpolates its
  `version` input into the release URL it downloads from, so the string
  "latest" became `vlatest` and the download 404'd — a runner that never
  downloaded mise because the action's cache was warm, until a changed
  `mise.toml` invalidated that cache. With the input omitted the action
  resolves the real latest release itself, from `https://mise.jdx.dev/VERSION`.
  The pins test holds that: every mise-action call site asks for a version or
  for nothing, never for a word.
- `scripts/tests/test_setup_local_tools_sh.py` was rewritten: it described a
  shared store of PyPI wheels that main removed with the move to mise. What it
  covers now is what the script does — a missing tool is a loud failure, and
  the venv links the tools mise installed instead of copying them.

## Links

- ADR `2026-10-20-the-toolchain-pins-live-in-one-file.md` (superseded)
- `mise.toml`
- `scripts/toolchain-version.py`
- `scripts/ci/install-ktlint.sh`
- `scripts/ci/setup-local-tools.sh`
- `.github/actions/setup-python-tools/action.yml`
- `.github/workflows/autofix.yml`
- `scripts/tests/test_toolchain_pins.py`
