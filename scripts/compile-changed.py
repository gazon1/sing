#!/usr/bin/env python3
"""compile-changed.py — compile the modules a change touches, and only those.

Why
---
The pre-commit hook compiled two modules, always: `:shared` and `:desktopApp`.
The pair was written down so the hook had something to run, and it stayed
because a pair is easier to read than a rule. The price was paid by everybody
whose change touched neither module — a change to a gate script, a document or
a configuration file waited for a Kotlin compile that could not say anything
about it, and a change that DID break somebody else's module was blocked by
that module's breakage.

The ADR of 2026-10-23 states the price plainly: "the compile is of whole
modules, not of the diff … a breakage that is NOT yours blocks your commits in
that worktree, and the only way past it is `--no-verify`". This script is the
half of that sentence that can be fixed: the compile follows the change.

What the change touches
-----------------------
The module graph in ``scripts/changed-modules.py`` already answers "which
module owns this path?" — the same graph the local verification plan and the
pre-push detekt verdict read. So the compile set is computed from that graph:
one source of truth, three callers.

Three rules, and why each one:

* **A change to the build itself compiles everything.** ``settings.gradle.kts``,
  ``gradle/``, ``build-logic/`` and the root build file decide how every module
  is built, so a change to them can break every module.
* **A change that touches no module compiles nothing.** A change under
  ``scripts/``, ``docs/`` or ``config/`` is a change to the gates. There is no
  Kotlin in it, and a compile that cannot say anything about the change is a
  wait, not a check.
* **``:androidApp`` is compiled when the change touches it, and the Android SDK
  is CI's.** The Android target needs the SDK. When the change touches
  ``:androidApp`` and the SDK is not reachable from the machine, the script
  says that it did not compile it — a check that did not run must not look
  like a check that passed.

The scope is the modules the change TOUCHES, and not the modules that compile
against them. That is a deliberate narrowing, and its price is stated in the
ADR of 2026-10-23: the REACH of a change — what it can break — is judged by
the pre-push verdict, which judges the affected modules, and by CI, which
compiles every module on every push. The commit hook answers one question:
does the code I just wrote compile?

Usage
-----
    python3 scripts/compile-changed.py                # compile what the change touches
    python3 scripts/compile-changed.py --list         # print the plan, compile nothing
    python3 scripts/compile-changed.py --json         # the plan as JSON
    python3 scripts/compile-changed.py --base main    # measure against another base

Exit codes:
    0 — everything the change touches compiles (or nothing needed compiling)
    1 — the compile failed
    2 — the plan could not be built (usage error, or the module graph is gone)
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import pathlib
import subprocess
import sys

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

CLEAN = 0
COMPILE_FAILED = 1
PLAN_FAILED = 2

#: The modules whose compile needs the Android SDK.
ANDROID_MODULES: frozenset[str] = frozenset({'androidApp'})


def load_module_graph():
    """The module graph, loaded from scripts/changed-modules.py.

    One source of truth: the local verification plan, the pre-push verdict and
    this compile all read the same graph, so a module the build has and the
    graph does not name cannot be forgotten — the plan says so instead of
    compiling nothing and calling it a pass.
    """
    path = ROOT / 'scripts' / 'changed-modules.py'
    spec = importlib.util.spec_from_file_location('changed_modules', path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f'could not load the module graph from {path}')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def android_sdk_present() -> bool:
    """Whether an Android SDK is reachable from here.

    The SDK is the Android toolchain's home. Where it is not, the Android
    target cannot be compiled locally — and the script says so rather than
    skipping quietly.
    """
    for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        value = os.environ.get(name)
        if value and pathlib.Path(value).is_dir():
            return True
    properties = ROOT / 'local.properties'
    if properties.is_file():
        for line in properties.read_text(encoding='utf-8').splitlines():
            if line.strip().startswith('sdk.dir='):
                return pathlib.Path(line.split('=', 1)[1].strip()).is_dir()
    return False


def plan(graph, paths: list[str]) -> tuple[list[str], list[str], list[str], list[str]]:
    """The modules to compile, the tasks, the reasons, and the errors.

    Returns (modules, tasks, reasons, errors). The reasons are what the script
    prints: a compile that runs without saying what it covers is a compile
    nobody can trust later.

    The errors are what makes a silent skip impossible. A module the graph
    does not name a compile task for would otherwise vanish from the plan
    without a word — and the commit would pass, saying nothing about a module
    nobody checked. A graph that has fallen behind the build is an error, not
    an empty plan.
    """
    reasons: list[str] = []
    errors: list[str] = []
    owners: set[str] = set()
    wide = False

    for path in paths:
        if path.startswith(graph.BUILD_AREAS):
            wide = True
            reasons.append(f'{path} — the build itself changed')
            continue
        if path.startswith(graph.GATE_AREAS):
            reasons.append(f'{path} — the gates changed, not the app')
            continue
        owner = next(
            (module for prefix, module in graph.PATH_MODULES.items() if path.startswith(prefix)),
            None,
        )
        if owner is None:
            reasons.append(f'{path} — no module owns it')
            continue
        owners.add(owner)
        reasons.append(f'{path} — {owner} changed')

    if wide:
        modules = set(graph.COMPILE_TASKS)
        reasons.append('the build changed, so every module is compiled')
        for owner in sorted(modules):
            if not graph.COMPILE_TASKS[owner]:
                reason = graph.NOT_COMPILED_LOCALLY.get(
                    owner,
                    'the graph says it is not compiled locally',
                )
                reasons.append(f'{owner} is not compiled locally — {reason}')
    else:
        # The modules the change TOUCHES — and only those. The modules that
        # compile against them are the change's REACH, and the reach is judged
        # by the pre-push verdict and by CI, which compiles every module on
        # every push. The commit hook answers one question: does the code I
        # just wrote compile?
        modules = set()
        for owner in sorted(owners):
            if owner not in graph.COMPILE_TASKS:
                errors.append(
                    f'the module graph does not say what to compile for {owner!r} — the '
                    f'compile of that module would be skipped without a word. The graph '
                    f'has fallen behind the build: add the module to COMPILE_TASKS.'
                )
                continue
            if not graph.COMPILE_TASKS[owner]:
                reason = graph.NOT_COMPILED_LOCALLY.get(
                    owner,
                    'the graph says it is not compiled locally',
                )
                reasons.append(f'{owner} is not compiled locally — {reason}')
                continue
            modules.add(owner)

    tasks: list[str] = []
    for module in sorted(modules):
        tasks.extend(graph.COMPILE_TASKS.get(module, []))
    ordered: list[str] = []
    for task in tasks:
        if task not in ordered:
            ordered.append(task)
    return sorted(modules), ordered, reasons, errors


def without_sdk(
    graph,
    modules: list[str],
    tasks: list[str],
    sdk_present: bool,
) -> tuple[list[str], list[str], list[str]]:
    """Drop the modules this machine cannot compile, and say which ones.

    The Android target needs the Android SDK, and the SDK is CI's. The module
    leaves the plan and the reason is written down: a check that did not run
    must not be dressed up as a check that passed.

    Returns (modules, tasks, skipped) — the skipped are the reasons, so the
    caller can print them and the verdict can say what it did not cover.
    """
    skipped: list[str] = []
    if 'androidApp' not in modules or sdk_present:
        return modules, tasks, skipped

    skipped.append(
        ':androidApp was not compiled — no Android SDK here (ANDROID_HOME, '
        'ANDROID_SDK_ROOT, local.properties); CI compiles it'
    )
    android_tasks = set(graph.COMPILE_TASKS.get('androidApp', []))
    return (
        [module for module in modules if module != 'androidApp'],
        [task for task in tasks if task not in android_tasks],
        skipped,
    )


def gradle_launcher() -> str:
    """The build launcher: the worktree-private wrapper when there is one."""
    launcher = os.environ.get('COMPILE_CHANGED_GRADLE')
    if launcher:
        return launcher
    return './gw' if (ROOT / 'gw').is_file() else './gradlew'


def run_compile(tasks: list[str]) -> subprocess.CompletedProcess:
    """The compile itself.

    Through ./gw, so the build uses this worktree's private GRADLE_USER_HOME
    instead of the shared ~/.gradle — a bare ./gradlew raises a daemon in the
    shared home, where any worktree's `--gradlew --stop` can kill it.

    The slot pool is switched OFF (SINGULARITY_GRADLE_SLOTS_DIR=0): a commit
    must not sit in a queue for up to 30 minutes behind somebody else's build.
    That is a deliberate trade — a commit waits for a CPU slot but never for
    the pool — and it is stated here so it reads as a decision rather than an
    oversight. See docs/decisions/2026-10-22-gradle-memory-budget.md.
    """
    environment = dict(os.environ)
    environment['SINGULARITY_GRADLE_SLOTS_DIR'] = '0'
    return subprocess.run(
        [gradle_launcher(), *tasks, '--no-daemon', '--quiet', '--console=plain'],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
        env=environment,
    )


def base_exists(base: str) -> bool:
    """Whether the change can be measured against the base at all.

    The check lives in the module graph, because the graph's own command has
    to make the same one: a base that does not resolve, or that has no merge
    base with `HEAD`, silently drops the committed part of the change from
    every plan.
    """
    try:
        graph = load_module_graph()
    except (OSError, RuntimeError):
        return False
    return bool(graph.base_usable(base))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument(
        '--base',
        default=os.environ.get('COMPILE_CHANGED_BASE', 'origin/main'),
        help='What the change is measured against (default: origin/main).',
    )
    parser.add_argument(
        '--list',
        action='store_true',
        help='Print the plan and compile nothing.',
    )
    parser.add_argument(
        '--json',
        action='store_true',
        help='Print the plan as JSON.',
    )
    args = parser.parse_args(argv)

    try:
        graph = load_module_graph()
    except (OSError, RuntimeError) as exc:
        print(f'compile-changed: {exc}', file=sys.stderr)
        return PLAN_FAILED

    if not base_exists(args.base):
        print(
            f'compile-changed: the base {args.base!r} cannot be measured against — it '
            f'does not resolve, or it has no merge base with HEAD. The committed part '
            f'of the change cannot be counted. Fetch it, or pass another base.',
            file=sys.stderr,
        )
        return PLAN_FAILED

    paths = graph.changed_paths(args.base)
    modules, tasks, reasons, errors = plan(graph, paths)
    touched = list(modules)
    modules, tasks, skipped = without_sdk(graph, modules, tasks, android_sdk_present())
    reasons.extend(skipped)

    if args.json:
        print(json.dumps({
            'modules': modules,
            'tasks': tasks,
            'reasons': reasons,
            'skipped': skipped,
            'errors': errors,
        }, ensure_ascii=False))
        return PLAN_FAILED if errors else CLEAN

    if errors:
        for error in errors:
            print(f'compile-changed: {error}', file=sys.stderr)
        print('compile-changed: FAILED — the module graph is behind the build.',
              file=sys.stderr)
        return PLAN_FAILED

    if not tasks:
        if touched:
            # The change DID touch a module — there was just nothing to
            # compile here. Saying "the change touches no module" would be a
            # false statement about the change.
            print(
                f'compile-changed: nothing to compile — the change touches '
                f'{", ".join(touched)}, and there is nothing to compile here.',
            )
        else:
            print('compile-changed: nothing to compile — the change touches no module.')
        for reason in reasons:
            print(f'  {reason}')
        return CLEAN

    print(f'compile-changed: {len(modules)} module(s) the change touches:')
    for module in modules:
        print(f'  {module}')
    for reason in reasons:
        print(f'  {reason}')
    print(f'compile-changed: running {len(tasks)} compile task(s)…')

    if args.list:
        for task in tasks:
            print(f'  {task}')
        return CLEAN

    process = run_compile(tasks)
    if process.returncode != 0:
        output = (process.stdout + process.stderr).strip()
        if output:
            print(output)
        print('compile-changed: FAILED', file=sys.stderr)
        return COMPILE_FAILED

    if skipped:
        print('compile-changed: OK — but something was NOT compiled:')
        for item in skipped:
            print(f'  {item}')
        return CLEAN
    print('compile-changed: OK')
    return CLEAN


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
