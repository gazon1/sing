---
status: accepted
date: 2026-10-20
---

# The toolchain pins live in one file, and the preflight runs in CI too

## Context

Two install paths, one set of versions, and a check that only ran on one of
them.

`scripts/ci/setup-local-tools.sh` installs the gate tooling for a developer:
`lychee-bin`, `yamllint`, `shellcheck-py`, and the `actionlint` release. The CI
composite action `.github/actions/setup-python-tools/action.yml` installs the
same four for a runner. Each wrote the versions out in full:

```
lychee-bin==<version>
yamllint==<version>
shellcheck-py==<version>
actionlint v<version>
```

The versions are not written here: they live in `config/toolchain-versions.env`,
and a second copy of a pin is the thing this decision is about.

Two copies of four pins, in two languages of shell, in two files that are read
by different people: the developer reads the script, the reviewer reads the
workflow. Nothing compared them. A bump on one side was a silent divergence —
the developer's `lychee` and the runner's `lychee` could disagree about what a
dead link is, and the difference would show up as "it passes here and fails
there".

The preflight had the same shape of problem from the other side.
`scripts/ci/preflight-gate-tools.sh` answers "is every tool the gate suite
shells out to actually installed?", and names the fix when one is not. It was
referenced **only** by `check.sh`. CI never ran it, so a CI job whose tooling
degraded — a wheel that would not build, an action that stopped installing —
discovered the problem as a gate traceback in the middle of a gate run, with
the actual cause several steps earlier.

## Idea

One file holds the pins, both consumers read it, and a test fails when a pin is
written anywhere else. The preflight runs where the tools are installed.

## Decision

**The pins live in `config/toolchain-versions.env`** — plain `KEY=VALUE` lines,
no substitution, so it is safe to `source` under `set -euo pipefail`.

- `scripts/ci/setup-local-tools.sh` sources it and uses the variables in the
  `uv pip install` line and in the actionlint download URL.
- `.github/actions/setup-python-tools/action.yml` sources it in the same way.
  The composite action's `run:` steps execute with the checked-out repository as
  their working directory, so the file is where the action expects it.
- The values are unchanged. This decision is about *where the pin lives*, not
  about which version.

**`scripts/tests/test_toolchain_pins.py` fails when a pin is written twice.**
The expected versions are derived from the pins file, not hard-coded in the
test, and the test scans the repository for the literals: the pins file is the
one allowed place. The test is not vacuous — a planted duplicate makes it fail.

**The preflight runs in CI's "Static gates" job**, in a step named "Preflight
gate tooling", right after "Set up gate tooling" and before the gate suite. It
fails the job when a tool is missing.

The **tests job** deliberately does not run it: that job installs the gate
tooling with `with-yaml-lint: false` and `with-action-lint: false` on purpose,
so the full preflight would fail there on tools the job deliberately does not
install. The static job is the gate job, and it installs the complete set.

## Rationale

A version pin is a fact about the project, and a fact with two copies is a fact
that will diverge. The file has to be one both consumers can read — shell
`source` is the only format that both a bash script and a composite action's
`run:` block already speak, and it needs no parser, no YAML, and no new
dependency.

The test matters more than the file. A single source that nothing checks is a
single source that people will "just edit in place" the first time it is
inconvenient — which is the moment the two copies come back. The test fails on
the duplicate, so the convenience is not available.

The preflight in CI is the difference between "the tooling is broken" and "the
tooling is broken and here is which tool". The gate suite shells out to
`lychee`, `yamllint`, `actionlint` and `shellcheck`; each of those, missing,
produces a traceback in a gate that was supposed to check something else. The
preflight names the missing tool before the first gate runs.

Not running it in the tests job is deliberate, not an oversight: that job
*chooses* to install a smaller toolset, and a preflight that demands tools the
job does not want would be a gate failure about a decision, not about a
breakage.

## Consequences

- A version bump is one edit, and the second copy cannot be forgotten: the test
  fails if a literal appears anywhere else.
- The pins file is a new thing a contributor can trip over — it is plain shell
  assignments, and the test's failure message names the file.
- The action's `source config/toolchain-versions.env` assumes the composite
  action runs with the checkout as its working directory. That is what the
  action's other steps already do, and the step fails loudly if the file is not
  there.
- The CI "Static gates" job now has one more step. It is a preflight: a few
  `command -v` calls, and it fails before the gates rather than during them.
- The preflight is not run in the tests job, and the reason is written down
  here rather than left to be rediscovered.

## Links

- `openspec/changes/the-next-layer-of-contracts/`
- `config/toolchain-versions.env`
- `scripts/ci/setup-local-tools.sh`
- `scripts/ci/preflight-gate-tools.sh`
- `.github/actions/setup-python-tools/action.yml`
- `.github/workflows/ci.yml`
- `scripts/tests/test_toolchain_pins.py`
