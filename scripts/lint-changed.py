#!/usr/bin/env python3
"""lint-changed.py — detekt over the changed files only.

Why
---
The whole-module detekt run costs three to four minutes, and a mechanical
finding — a too-complex method, a long method, a missing blank line between
`when` branches — is a finding about the file the author just wrote. Paying
minutes per question about one file is what made a session run detekt four
times to fix four mechanical findings that were visible in seconds.

This runs the same rules over the changed files only: the same config, the same
baseline, the same rule set as the module's own `detekt` task, with a smaller
input. A finding here is the finding CI reports.

What "changed" means
--------------------
* the commits on this branch since the merge base with the base branch
  (`origin/main` by default; `--base` or `$LINT_BASE` to change it), and
* the working tree: staged, unstaged and untracked files.

The working tree is not optional. The pre-commit hook runs on changes that are
staged but not committed, and a lint that only looked at history would report
nothing exactly when it is needed most.

Usage
-----
    python3 scripts/lint-changed.py                  # lint the changed files
    python3 scripts/lint-changed.py --base main      # measure against another base
    python3 scripts/lint-changed.py --list           # print the file list, lint nothing
    python3 scripts/lint-changed.py --print-command  # print the Gradle command, run nothing

Exit code: 0 when there is nothing to report, 1 when there is a finding.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

# The modules that run detekt. Each one gets a `detektChanged` task; a module
# with no changed file has nothing to analyse, and its task is skipped.
TASKS: list[str] = [
    ':shared:detektChanged',
    ':desktopApp:detektChanged',
    ':androidApp:detektChanged',
    ':mcp-server:detektChanged',
    ':detekt-rules:detektChanged',
    ':core-base:detektChanged',
    ':adr-core:detektChanged',
    ':adr-cli:detektChanged',
]

# The detekt console report: "<severity>: <RuleId> - [<message>] at <file>:<line>:<column>".
# The message is truncated by detekt itself ("..."), so the report file is the
# place to read the whole one.
FINDING = re.compile(
    r'^(?P<severity>[ew]):\s+(?P<rule>[\w.]+)\s+-\s+\[(?P<message>.*)\]\s+at\s+'
    r'(?P<file>.+?):(?P<line>\d+):(?P<column>\d+)\s*$'
)

# The colour codes detekt's console output carries; they are noise in a report
# a human reads, and they break the pattern above.
ANSI = re.compile(r'\x1b\[[0-9;]*m')


def git_paths(*args: str) -> tuple[int, list[str]]:
    """Run git for a list of paths and return (exit code, the paths).

    The `-z` format is the one that does not quote: git writes a path with a
    space, a quote or a backslash in quotes, and a quoted path does not name a
    file — the lint would silently not look at the file that was just written.
    """
    process = subprocess.run(
        ['git', *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return process.returncode, [part for part in process.stdout.split('\0') if part]


def changed_paths(base: str) -> list[str]:
    """The paths this change touches: committed and uncommitted alike.

    A change that is not committed yet is still a change, and a lint that only
    looked at the commits would report nothing about the work in front of the
    developer — which is exactly the state the pre-commit hook runs in.
    """
    paths: list[str] = []

    # The commits on this branch, measured against the merge base with the base
    # branch. `A...B` is the three-dot form: the diff starts at the merge base.
    code, committed = git_paths('diff', '--name-only', '-z', f'{base}...HEAD')
    if code == 0:
        paths.extend(committed)
    else:
        print(
            f"lint-changed: cannot compare against '{base}' — "
            f"only the working tree is linted (fetch it, or pass --base)",
            file=sys.stderr,
        )

    # The working tree: staged and unstaged changes to tracked files.
    code, tracked = git_paths('diff', '--name-only', '-z', 'HEAD')
    if code == 0:
        paths.extend(tracked)

    # The working tree: files that are not tracked yet. `git status` would
    # report a new directory as one entry, which would hide the Kotlin files
    # inside it; `ls-files` reports them one by one.
    code, untracked = git_paths('ls-files', '--others', '--exclude-standard', '-z')
    if code == 0:
        paths.extend(untracked)

    seen: set[str] = set()
    ordered: list[str] = []
    for path in paths:
        if path not in seen:
            seen.add(path)
            ordered.append(path)
    return ordered


def kotlin_files(paths: list[str]) -> list[pathlib.Path]:
    """The Kotlin files among the changed paths, that are there to be analysed.

    The file types are the ones detekt includes by default (`*.kt`, `*.kts`).
    A file that does not exist any more — deleted, or renamed away — is not a
    finding, it is a path that no longer holds anything.
    """
    files: list[pathlib.Path] = []
    for path in paths:
        if not (path.endswith('.kt') or path.endswith('.kts')):
            continue
        absolute = ROOT / path
        if absolute.is_file():
            files.append(absolute)
    return files


def gradle_command(files: list[pathlib.Path]) -> list[str]:
    """The Gradle invocation that lints exactly these files."""
    launcher = os.environ.get('LINT_CHANGED_GRADLE')
    if not launcher:
        launcher = './gw' if (ROOT / 'gw').is_file() else './gradlew'

    paths = [str(path.relative_to(ROOT)) for path in files]
    # The list travels as a Gradle property. It is a comma-separated list
    # rather than the path of a file, because the property is part of the
    # configuration-cache key: the same list means the same configuration, and
    # a different list means a different one. A file name would be stable
    # while its content is not, and a cached configuration would then lint the
    # files of an earlier run.
    #
    # A path with a comma in it would break the separator, so such a list goes
    # through a file instead — correctness first, and the case does not arise
    # in this repository.
    if any(',' in path for path in paths):
        list_file = ROOT / 'build' / 'diagnostics' / 'lint-changed-files.txt'
        list_file.parent.mkdir(parents=True, exist_ok=True)
        list_file.write_text('\n'.join(paths) + '\n', encoding='utf-8')
        property = f'-PdetektChangedFilesFile={list_file}'
    else:
        property = '-PdetektChangedFiles=' + ','.join(paths)

    return [launcher, *TASKS, property, '--console=plain']


def findings_of(output: str) -> list[dict[str, str]]:
    """The findings in a Gradle log, deduplicated and in the order they appear.

    detekt prints the findings twice — once grouped by rule set, once grouped by
    file — so the same finding arrives twice and has to be collapsed.
    """
    found: list[dict[str, str]] = []
    seen: set[tuple[str, str, str, str]] = set()
    for raw_line in output.splitlines():
        line = ANSI.sub('', raw_line).strip()
        match = FINDING.match(line)
        if not match:
            continue
        key = (
            match.group('rule'),
            match.group('file'),
            match.group('line'),
            match.group('column'),
        )
        if key in seen:
            continue
        seen.add(key)
        found.append(match.groupdict())
    return found


def relative(path: str) -> str:
    """A path from the report, relative to the repository root."""
    try:
        return str(pathlib.Path(path).relative_to(ROOT))
    except ValueError:
        return path


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument(
        '--base',
        default=os.environ.get('LINT_BASE', 'origin/main'),
        help='What the change is measured against (default: origin/main).',
    )
    parser.add_argument(
        '--list',
        action='store_true',
        help='Print the changed Kotlin files and lint nothing.',
    )
    parser.add_argument(
        '--print-command',
        action='store_true',
        help='Print the Gradle command and run nothing.',
    )
    args = parser.parse_args(argv)

    files = kotlin_files(changed_paths(args.base))
    relative_paths = [str(path.relative_to(ROOT)) for path in files]

    if args.list:
        for path in relative_paths:
            print(path)
        return 0

    if not files:
        print('lint-changed: no changed Kotlin files — nothing to lint.')
        return 0

    print(f"lint-changed: {len(files)} changed Kotlin file(s) against '{args.base}':")
    for path in relative_paths:
        print(f'  {path}')

    command = gradle_command(files)

    if args.print_command:
        print(' '.join(command))
        return 0

    print('lint-changed: running the scoped detekt…')
    process = subprocess.run(
        command,
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )

    output = process.stdout + process.stderr
    findings = findings_of(output)

    if not findings:
        print('lint-changed: no findings.')
        return 0 if process.returncode == 0 else 1

    print(f'\nlint-changed: {len(findings)} finding(s):')
    for finding in findings:
        print(
            f"  {relative(finding['file'])}:{finding['line']}:{finding['column']}  "
            f"{finding['rule']}  {finding['message']}"
        )
    print('\nThe report with the whole messages: <module>/build/reports/detekt/changed.md')
    return 1


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
