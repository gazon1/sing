#!/usr/bin/env python3
"""check-test-task-inputs.py — a test task that reads a tree must declare it as an input.

## Why this exists

Measured 2026-10-04. `MaestroFlowTagsTest` lives in `:shared:jvmTest`, resolves the
worktree root by walking up from `commonMain.root`, and reads `Maestro/flows/**`. That
tree is not a compile input of `:shared`, so the task's up-to-date check did not see it.

The failure mode is the worst kind, because it looks like a pass:

    $ # baseline: gate green, task cached
    $ ./gradlew :shared:jvmTest --tests '…MaestroFlowTagsTest'   # UP-TO-DATE, SUCCESS
    $ # inject a valid Maestro command carrying an unknown `id:` into a flow
    $ ./gradlew :shared:jvmTest --tests '…MaestroFlowTagsTest'
    > Task :shared:jvmTest UP-TO-DATE
    BUILD SUCCESSFUL in 15s

A blocking gate, reporting green, about a file it had not re-read. The same shape had
already been fixed twice in this repository by hand — `desktopApp` and `mcp-server` on
`:shared:jvmTest`, and `config/detekt/detekt.yml` on `:detekt-rules` — which is the
signal that fixing instances is not the answer. Every hand-fix is a fix to the one tree
somebody happened to trip over.

## The rule

A test task in this repository receives its scan roots as `systemProperty("*.root", …)`.
Whenever such a root resolves **outside the module that declares it**, the declaring
build file must also declare that path — or a directory containing it — as a task input.

Outside-the-module is the trigger, not the whole story: a root inside the module is
already a compile input, so it needs no declaration. A root outside is a dependency only
Gradle cannot see, which is exactly the kind that goes missing.

## What it deliberately does not do

It does not try to discover roots by reading Kotlin sources. A test that walks up to
`settings.gradle.kts` — `DetektConfigWiringTest` does — declares no property at all, and
a static scan of the sources would have to guess where it stopped. The rule is enforced
at the point where the dependency is made explicit, which is the only place it can be
stated without guessing.

That is also why `detekt-rules` passes: its config input is declared as `inputs.file`,
without a matching root property, and the absence of a root means there is nothing to
cross-check. A root declared but not covered is the failure; a file input with no root
is somebody's deliberate fix, left alone.

Usage:
  python3 scripts/check-test-task-inputs.py
  python3 scripts/check-test-task-inputs.py --verbose
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Any `systemProperty("name", …)` whose value is a path expression. The rule is about the
# *value* escaping the module, not about how the property is named.
#
# An earlier version matched only names ending in `.root`, on the assumption that scan
# roots are conventionally spelled that way. The unit test then passed a fixture named
# `maestroRoot`, the gate ignored it, and the fixture "passed" for the wrong reason. A
# gate whose rule is a naming convention is a gate with a hole shaped like a convention.
SYSTEM_PROPERTY_RE = re.compile(r'systemProperty\(\s*"(?P<name>[A-Za-z][A-Za-z0-9._]*)"')

# How far past the property name to look for the path expression. A declaration is a few
# lines; a generous window costs nothing and avoids re-introducing the nesting bug that
# made the first parser match nothing.
ROOT_LOOKAHEAD = 400

# `inputs.dir(<expr>)` / `inputs.file(<expr>)` / `inputs.files(<expr>, …)`.
INPUT_RE = re.compile(r'inputs\.(?:dir|files?)\((?P<body>.*?)\)\s*(?:\.|\n|$)', re.S)

# Path-ish argument inside an inputs call. The BASE is captured as well as the path:
# `rootProject.file("config/detekt/detekt.yml")` and
# `layout.projectDirectory.dir("config/detekt/detekt.yml")` are the same directory from
# two different modules' points of view, and dropping the base resolved one of them
# against the wrong root.
PATH_ARG_RE = re.compile(
    r'(?P<base>layout\.projectDirectory|rootProject\.projectDirectory|rootProject|project)'
    r'\.(?:dir|file)\(\s*"(?P<path>[^"]+)"\s*\)'
)

# Only top-level module build files. build-logic holds convention plugins, which
# configure other projects' tasks and cannot be resolved to one module.
SKIP_DIRS = {'build-logic', 'node_modules', '.git', 'build', 'gradle'}


def module_build_files(root: pathlib.Path) -> list[pathlib.Path]:
    files = []
    for child in sorted(root.iterdir()):
        if not child.is_dir() or child.name in SKIP_DIRS or child.name.startswith('.'):
            continue
        candidate = child / 'build.gradle.kts'
        if candidate.is_file():
            files.append(candidate)
    return files


def escapes_module(module_dir: pathlib.Path, path: pathlib.Path) -> bool:
    """True when `path` is not inside the module that declared it.

    Compared lexically after resolution. A symlinked checkout can make every path look
    shared; the question being asked is which *directory tree* the path names, and that
    is a property of the text, not of the filesystem's link structure.
    """
    try:
        path.relative_to(module_dir)
        return False
    except ValueError:
        return True


def analyse(root: pathlib.Path) -> tuple[list[str], list[str], int, int]:
    findings: list[str] = []
    notes: list[str] = []
    checked = 0
    path_properties = 0
    for build_file in module_build_files(root):
        text = build_file.read_text(encoding='utf-8', errors='replace')
        module_dir = build_file.parent.resolve()
        rel_module = module_dir.relative_to(root).as_posix()

        declared_inputs: list[pathlib.Path] = []
        for match in INPUT_RE.finditer(text):
            for arg in PATH_ARG_RE.finditer(match.group('body')):
                declared_inputs.append(
                    _resolve(module_dir, root, arg.group('base'), arg.group('path')))

        for match in SYSTEM_PROPERTY_RE.finditer(text):
            name = match.group('name')
            window = text[match.end():match.end() + ROOT_LOOKAHEAD]
            arg = PATH_ARG_RE.search(window)
            if not arg:
                # A system property whose value is not a path (a boolean, a tag) is not
                # this gate's business. Only a path-valued one would be a gap, and a
                # property with no literal path has no path to declare.
                continue
            path_properties += 1
            resolved = _resolve(module_dir, root, arg.group('base'), arg.group('path'))
            if not escapes_module(module_dir, resolved):
                continue  # inside the module: already a compile input
            checked += 1
            covered = any(
                resolved == candidate or _is_ancestor(candidate, resolved)
                for candidate in declared_inputs
            )
            shown = resolved.relative_to(root).as_posix() if resolved.is_relative_to(root) else str(resolved)
            if covered:
                notes.append(f'{rel_module}: {name} -> {shown} (declared)')
            else:
                findings.append(
                    f'{rel_module}: root property {name!r} resolves to {shown}, which is '
                    f'outside {rel_module}, but no inputs.dir/inputs.file covers it. A test '
                    f'task reading that tree will report a stale verdict — the gate looks '
                    f'green while describing a file it never re-read.'
                )
    return findings, notes, checked, path_properties


def _resolve(module_dir: pathlib.Path, root: pathlib.Path, base: str, raw: str) -> pathlib.Path:
    """Resolve a build-script path expression to an absolute, lexically clean path.

    `rootProject.*` is repo-relative; `layout.projectDirectory` and `project` are
    module-relative. The base comes from the matched expression, never from the path
    string — a path may legitimately start with `../` and be module-relative, or be
    repo-relative and start with a bare directory name.
    """
    if base.startswith('rootProject'):
        return pathlib.Path(root, raw.lstrip('/')).resolve()
    if raw.startswith('/'):
        return pathlib.Path(root, raw.lstrip('/')).resolve()
    return pathlib.Path(module_dir, raw).resolve()


def _is_ancestor(candidate: pathlib.Path, target: pathlib.Path) -> bool:
    try:
        target.relative_to(candidate)
        return True
    except ValueError:
        return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--verbose', action='store_true',
                        help='print the roots that are correctly declared too')
    args = parser.parse_args()

    if not module_build_files(ROOT):
        print('check_test_task_inputs.py: no module build files found — '
              'refusing to pass on an empty scan', file=sys.stderr)
        sys.exit(1)

    findings, notes, checked, path_properties = analyse(ROOT)
    if args.verbose:
        for note in notes:
            print(f'  {note}')

    if findings:
        print(f'check_test_task_inputs.py: {len(findings)} finding(s)')
        for finding in findings:
            print(f'ERROR: {finding}')
        print('Fix: add inputs.dir(...) for the tree, on the task that reads it, with '
              'PathSensitivity.RELATIVE and a withPropertyName. Then prove it with a '
              'probe — edit the file, re-run the same command, and confirm the task is '
              'no longer UP-TO-DATE. A fix that is not probed is a comment.')
        sys.exit(1)

    if path_properties == 0:
        # The failure this gate exists to prevent, applied to itself. A parser that stops
        # matching — a refactor, a syntax change, a moved file — yields no path-valued
        # properties at all, the finding list comes out empty, and the gate reports OK.
        #
        # The trigger is zero *path properties found*, not zero *external roots*: a
        # repository that genuinely declares no external roots is a legitimate state and
        # must pass, while a parser that matches nothing is indistinguishable from a clean
        # tree unless it is called out. This is the same distinction as
        # `check-test-runs.py`'s "ran 0 tests" floor.
        print('check_test_task_inputs.py: ERROR — found 0 path-valued system properties, so '
              'this run proves nothing.', file=sys.stderr)
        print('Either the parser stopped matching the declarations, or the repository has '
              'no test task passing a path to its JVM. Both must be resolved by hand before '
              'this gate is trusted; do not read the empty result as a pass.', file=sys.stderr)
        sys.exit(1)

    print(f'check_test_task_inputs.py: OK — {checked} external root(s) checked, '
          f'all declared as task inputs')


if __name__ == '__main__':
    main()
