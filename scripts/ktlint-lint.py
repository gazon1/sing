#!/usr/bin/env python3
"""ktlint-lint.py — the Kotlin formatting gate, run by the ktlint CLI.

Why
---
Formatting used to be a detekt plugin (`detekt-formatting`), so the format
rules and the semantic rules were one Gradle invocation and one verdict. That
made the cheap half expensive: `:shared:detekt` costs minutes, and a question
about a single file — "is this formatted?" — was answered in minutes.

ktlint now owns the formatting, and detekt owns the semantics. This script is
the seam: it runs the ktlint CLI over the Kotlin sources of a tree and reports
what it found, so the same check can run in CI, in the pre-push hook and over
the changed files only.

What it runs
------------
The tracked and untracked `*.kt` / `*.kts` files of the tree — the list comes
from git, so build output and other ignored directories are not in it, and a
file that was deleted is not linted.

Usage
-----
    python3 scripts/ktlint-lint.py                 # lint the tree
    python3 scripts/ktlint-lint.py --format        # format the tree
    python3 scripts/ktlint-lint.py --require       # fail when ktlint is missing
    python3 scripts/ktlint-lint.py path/to/File.kt # lint these files

Exit code: 0 when there is nothing to report, 1 when there is a finding.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import subprocess
import sys

#: The answer to "which git variables must a command inside a tree not inherit"
#: belongs to `sgate.gatekit.gitenv`. This script has no third-party dependency,
#: so it cannot import the package (it pulls in `click` and `loguru`) — it loads
#: the owner by path, the way `scripts/pre-push-verdict.py` does. The loader
#: sits beside this script and is therefore importable without a path of its
#: own.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from gitenv_loader import tree_env  # noqa: E402

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

#: Where the binary is looked for, in this order: the environment (a shared
#: store, a CI runner's tool cache), then PATH.
BINARY_ENV = "KTLINT_BIN"


def find_binary() -> str | None:
    """The ktlint binary, or None when it is not installed."""
    explicit = os.environ.get(BINARY_ENV)
    if explicit:
        return explicit if os.access(explicit, os.X_OK) else None
    return shutil.which("ktlint")


def kotlin_files(root: pathlib.Path) -> list[pathlib.Path]:
    """The Kotlin sources of the tree: tracked and untracked, ignored ones left out.

    The list comes from git rather than from a directory walk: a walk finds
    the copies of the sources in `build/` and in the IDE caches, and a lint
    over those is a lint over files nobody edits.

    The git command runs in `tree_env()`: the pre-push hook is invoked by git
    with `GIT_DIR` and friends exported, and they name the tree the hook runs
    in — not necessarily the tree this script was asked about.
    """
    process = subprocess.run(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', '*.kt', '*.kts'],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
        env=tree_env(),
    )
    if process.returncode != 0:
        print(
            'ktlint-lint: git could not list the sources — '
            'is this a git checkout?',
            file=sys.stderr,
        )
        return []

    files: list[pathlib.Path] = []
    for part in process.stdout.split('\0'):
        if not part:
            continue
        path = root / part
        if path.is_file():
            files.append(path)
    return files


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--root', default=str(ROOT), help='The tree to lint (default: this checkout).')
    parser.add_argument('--format', action='store_true', help='Rewrite the files instead of reporting.')
    parser.add_argument(
        '--require',
        action='store_true',
        help='Fail when ktlint is not installed, instead of skipping.',
    )
    parser.add_argument('files', nargs='*', help='The files to lint (default: every Kotlin source).')
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root).resolve()

    binary = find_binary()
    if binary is None:
        message = (
            'ktlint is not installed — the formatting check did not run.\n'
            'Install it: bash scripts/ci/setup-local-tools.sh '
            '(and put the venv on PATH).'
        )
        if args.require:
            print(message, file=sys.stderr)
            return 1
        print(message)
        return 0

    if args.files:
        files = [pathlib.Path(path).resolve() for path in args.files]
        files = [path for path in files if path.is_file()]
    else:
        files = kotlin_files(root)

    if not files:
        print('ktlint-lint: no Kotlin files to check.')
        return 0

    command = [binary, '--reporter', 'plain']
    if args.format:
        command.append('--format')
    command += [str(path) for path in files]

    verb = 'formatting' if args.format else 'checking'
    print(f'ktlint-lint: {verb} {len(files)} file(s) with {binary}…')
    process = subprocess.run(command, cwd=root, check=False)

    if process.returncode == 0:
        print('ktlint-lint: nothing to report.')
        return 0
    print(
        'ktlint-lint: ktlint reported findings.\n'
        'Fix them with: just ktlint-fix (or: python3 scripts/ktlint-lint.py --format)',
        file=sys.stderr,
    )
    return 1


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
