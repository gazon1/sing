#!/usr/bin/env python3
"""changed-modules.py — which checks a change makes worth running.

Why
---
The local verification loop costs about thirteen minutes, and one step of it —
`:shared:jvmTest` — costs seven of them. The measurement is published
(`docs/testing/gate-costs.md`), so the question it was taken to answer is the
obvious one: *what does this change actually require?*

Answering it with "everything" is safe and useless. Answering it with a guess is
worse. So the answer is computed from the module graph the build files declare:

* a change in a module can break the modules that compile against it, and those
  are read out of the build files, not guessed;
* a change in the build itself can break everything, and that is a separate case;
* a change under `scripts/`, `docs/` or `config/` is a change to the *gates*, so
  the gate suite is what has to run.

Usage:
    python3 scripts/changed-modules.py                 # the tasks to run, one per line
    python3 scripts/changed-modules.py --explain       # the same, with the reasons
    python3 scripts/changed-modules.py --base origin/main
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

#: Which module owns which path. The prefixes are the modules' directories.
PATH_MODULES: dict[str, str] = {
    'core-base/': 'core-base',
    'adr-core/': 'adr-core',
    'adr-cli/': 'adr-cli',
    'shared/': 'shared',
    'desktopApp/': 'desktopApp',
    'androidApp/': 'androidApp',
    'mcp-server/': 'mcp-server',
    'detekt-rules/': 'detekt-rules',
}

#: What a change in a module can break.
#:
#: The edges are the ones the build files declare: `:core-base` is consumed by
#: `:adr-core`, `:adr-core` by `:shared`, and `:shared` by the four modules that
#: compile against it. `:adr-cli` depends on `:adr-core` and on nothing else —
#: it was deliberately cut off from `:shared`, so a change in `:shared` does not
#: reach it.
DOWNSTREAM: dict[str, list[str]] = {
    'core-base': ['core-base', 'adr-core', 'adr-cli', 'shared', 'androidApp', 'desktopApp', 'mcp-server'],
    'adr-core': ['adr-core', 'adr-cli', 'shared', 'androidApp', 'desktopApp', 'mcp-server'],
    'shared': ['shared', 'androidApp', 'desktopApp', 'mcp-server'],
    'desktopApp': ['desktopApp'],
    'androidApp': ['androidApp'],
    'mcp-server': ['mcp-server'],
    'adr-cli': ['adr-cli'],
    'core-base-only': ['core-base'],
    'detekt-rules': ['detekt-rules'],
}

#: What has to run for a module that changed.
#:
#: The tests, because the module's own behaviour may have changed; the detekt
#: run, because the rules are the same rules everywhere.
TASKS: dict[str, list[str]] = {
    'shared': [':shared:jvmTest', ':shared:testAndroidHostTest', ':shared:detekt'],
    'desktopApp': [':desktopApp:test', ':desktopApp:detekt'],
    'androidApp': [':androidApp:assembleDebug', ':androidApp:detekt'],
    'mcp-server': [':mcp-server:test', ':mcp-server:detekt'],
    'adr-core': [':adr-core:jvmTest', ':adr-core:detekt'],
    'adr-cli': [':adr-cli:test', ':adr-cli:detekt'],
    'core-base': [':core-base:detekt'],
    'detekt-rules': [
        ':detekt-rules:test',
        ':detekt-rules:koverVerify',
        ':detekt-rules:detekt',
    ],
}

#: The detekt runs of the modules that use the custom rules. A change in the
#: rules changes what those modules report, so their detekt runs are what has to
#: be repeated — not their tests, which do not read the rules.
DETEKT_CONSUMERS: list[str] = [
    ':shared:detekt',
    ':desktopApp:detekt',
    ':androidApp:detekt',
    ':core-base:detekt',
    ':adr-core:detekt',
    ':adr-cli:detekt',
]

#: Paths that change the build itself. Everything downstream of them is affected.
BUILD_AREAS = (
    'gradle/',
    'gradlew',
    'gradlew.bat',
    'gradle.properties',
    'build.gradle.kts',
    'settings.gradle.kts',
)

#: The areas where a change is a change to the *gates* rather than to the app.
GATE_AREAS = (
    'scripts/',
    '.github/',
    'docs/',
    'config/',
    '.specify/',
    'openspec/',
)


def changed_paths(base: str) -> list[str]:
    """The paths this change touches: committed and uncommitted alike.

    A change that is not committed yet is still a change, and a lane that only
    looked at the commits would run nothing for the work in front of the
    developer.
    """
    paths: list[str] = []
    committed = subprocess.run(
        ['git', 'diff', '--name-only', f'{base}...HEAD'],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if committed.returncode == 0:
        paths.extend(line.strip() for line in committed.stdout.splitlines() if line.strip())
    working = subprocess.run(
        ['git', 'status', '--porcelain'],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if working.returncode == 0:
        for line in working.stdout.splitlines():
            name = line[3:].strip()
            if ' -> ' in name:
                name = name.split(' -> ', 1)[1].strip()
            if name:
                paths.append(name)
    seen: set[str] = set()
    ordered: list[str] = []
    for path in paths:
        if path not in seen:
            seen.add(path)
            ordered.append(path)
    return ordered


def plan(paths: list[str]) -> tuple[list[str], list[str]]:
    """The tasks to run, and the reasons for them."""
    tasks: list[str] = []
    reasons: list[str] = []
    modules: set[str] = set()
    gates = False
    everything = False
    script_tests = False

    for path in paths:
        if path.startswith(BUILD_AREAS):
            everything = True
            reasons.append(f'{path} — the build itself changed')
            continue
        if path.startswith(GATE_AREAS):
            gates = True
            reasons.append(f'{path} — the gates changed')
            if path.startswith('scripts/tests/'):
                # The gate scripts' own tests: a change under scripts/tests is a
                # change to the checks that check the checks.
                script_tests = True
            continue
        owner = next(
            (module for prefix, module in PATH_MODULES.items() if path.startswith(prefix)),
            None,
        )
        if owner is None:
            reasons.append(f'{path} — no module owns it')
            continue
        modules.add(owner)
        reasons.append(f'{path} — {owner} changed')

    if everything:
        modules = set(TASKS)
        for module in sorted(modules):
            tasks.extend(TASKS[module])
        reasons.append('the build changed, so every module is checked')
        gates = True
    else:
        affected: set[str] = set()
        for module in modules:
            affected.update(DOWNSTREAM.get(module, [module]))
        for module in sorted(affected):
            tasks.extend(TASKS.get(module, []))
        if 'detekt-rules' in modules:
            # The rules changed: what the other modules *report* changes, so their
            # detekt runs are repeated. Their tests do not read the rules.
            for task in DETEKT_CONSUMERS:
                if task not in tasks:
                    tasks.append(task)
            reasons.append('detekt-rules changed — the detekt runs of its consumers repeated')

    if gates:
        tasks.append('scripts/ci/static-gates.sh')
    if script_tests:
        tasks.append('scripts/tests')

    ordered: list[str] = []
    for task in tasks:
        if task not in ordered:
            ordered.append(task)
    return ordered, reasons


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--base', default='origin/main',
                        help='What the change is measured against.')
    parser.add_argument('--explain', action='store_true',
                        help='Print why each task is in the plan.')
    args = parser.parse_args(argv)

    paths = changed_paths(args.base)
    tasks, reasons = plan(paths)

    if args.explain:
        for reason in reasons:
            print(f'  {reason}', file=sys.stderr)
        print('', file=sys.stderr)

    if not tasks:
        print('nothing to run: no module in this change owns a check.', file=sys.stderr)
        return 0

    for task in tasks:
        print(task)
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
