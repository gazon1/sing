#!/usr/bin/env bash
# check-doc-links.sh — every Markdown link in the repository resolves.
#
# What this checks, and what it deliberately does not
# ---------------------------------------------------
# Links, and only links. `scripts/check-doc-dead-refs.py` checks the *other*
# shape of reference: a path inside backticks, and a dated ADR slug cited
# without its `.md`. Its PATH_RE matches backticks only, so a Markdown link
# was never in its scope. This script is coverage the repository did not have,
# not a replacement for that gate.
#
# It is not a replacement because the two resolve differently, and the
# difference is the whole point:
#
#   * lychee resolves a relative link against the file that contains it, which
#     is what an editor does when a reader clicks the link.
#   * check-doc-dead-refs.py resolves against the repository root and falls
#     back to matching on basename alone, so a link written one directory too
#     high ("../AGENTS.md" from docs/decisions/) still lands on a file of that
#     name and is reported as "drift" — a warning, never a failure.
#
# Fifteen such links sat in ADRs and skills on 2026-10-19, invisible to every
# gate in the repository. They were fixed rather than accepted; see the ADR.
#
# Flags
# -----
# --offline      Local files only, no network. A link checker that reaches the
#                internet makes the gate's verdict depend on someone else's
#                uptime, and this repository's gates are built to be
#                reproducible. External URLs are out of scope by construction,
#                not by omission.
# --root-dir     The repository root, for the links written root-relative.
# --no-progress  This runs in CI, where a progress bar is noise.
#
# Inputs come from `git ls-files`, so the corpus is exactly what is tracked:
# no build output, no untracked scratch files, and the same set on every
# machine. `.lycheeignore` holds the links that are *examples* — a path shown
# to demonstrate a convention, not a reference to a file.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if ! command -v lychee >/dev/null 2>&1; then
  echo "lychee is not on PATH; the documentation link check would cover nothing and say nothing" >&2
  # The version comes from the pins file, like every other install of it: a
  # second copy of a pin is a pin that can drift.
  # shellcheck source=../config/toolchain-versions.env
  . "$(dirname "${BASH_SOURCE[0]}")/../config/toolchain-versions.env"
  echo "the gate tooling installs it: bash scripts/ci/setup-local-tools.sh" >&2
  echo "and then put this checkout's venv on PATH — the installer keeps the binary" >&2
  echo "in a store shared by every checkout (a wheel of version ${LYCHEE_BIN_VERSION})" >&2
  exit 1
fi

git ls-files -z -- '*.md' | xargs -0 -r lychee --offline --no-progress --root-dir "$PWD"
