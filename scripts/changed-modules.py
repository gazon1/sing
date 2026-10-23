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

The module list is read, not written
------------------------------------
The modules used to be a table in this file, and the table and the build
drifted apart: `settings.gradle.kts` declares ten modules, and the table knew
eight. The two it did not know were the two nobody judged —

* `:tools:unwritten-properties`, a nested module. Its directory is
  `tools/unwritten-properties` — two segments deep, which is also why the CI
  annotation glob `*/build/test-results/**/*.xml` skipped its JUnit XML;
* `:pro`, which the build includes only behind `-PwithPro`.

A module the graph does not know is a module nothing runs, and nothing says
so. The list is therefore READ from `settings.gradle.kts` now:

* every `include("…")` is a module, nested ones included. The directory a
  module owns is derived from its path the way Gradle maps it: `:a:b` lives in
  `a/b`, so a change under `a/b/` is a change in `:a:b`;
* an include inside a condition is a module the build declares CONDITIONALLY.
  It is known — the graph does not pretend it is not there — and the decision
  about judging it is written down (see `CONDITIONAL_POLICY`) instead of
  falling out of a lookup.

What is still written here is what a module OWES: the tasks that judge it.
Those are facts about the build scripts rather than about the build's shape,
and the one that matters is whether a module has a detekt task at all —
`:tools:unwritten-properties` applies the plain `kotlin("jvm")` plugin, so its
verdict is its test suite and not a detekt run.

`TASKS` is therefore a mapping with two views, and the difference is
deliberate: `keys()` and `items()` see every judged module, while iterating it
yields the modules that HAVE a detekt verdict — the list
`scripts/pre-push-verdict.py` turns into detekt tasks (`f":{module}:detekt"`).
A module without a detekt task would make that synthesis name a task the build
does not have, and the hook would fail on every wide push.

Usage:
    python3 scripts/changed-modules.py                 # the tasks to run, one per line
    python3 scripts/changed-modules.py --explain       # the same, with the reasons
    python3 scripts/changed-modules.py --base origin/main
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
from functools import lru_cache
from typing import Iterator, NamedTuple

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

#: The build file that declares the modules. It is the definition of what the
#: build contains, so it is the definition of what the graph knows.
SETTINGS: pathlib.Path = ROOT / 'settings.gradle.kts'


class Module(NamedTuple):
    """One module the build declares.

    A named tuple and not a dataclass: `scripts/pre-push-verdict.py` loads this
    file by path WITHOUT registering it in `sys.modules`, and a dataclass
    resolves its own annotations through `sys.modules` — which is not there.
    The graph is loaded that way by the hook, so the graph has to load that way.
    """

    #: The module's name in the graph: `shared`, `tools:unwritten-properties`.
    #: It is the Gradle path without the leading colon, so a task of the module
    #: is `:name:task` — one spelling for the module and its tasks.
    name: str

    #: The Gradle path: `:shared`, `:tools:unwritten-properties`.
    gradle_path: str

    #: The directory the module owns: `shared`, `tools/unwritten-properties`.
    #: Gradle maps a project path to a directory this way unless the build says
    #: otherwise, and the build does not say otherwise here.
    directory: str

    #: True when the build includes the module only behind a condition.
    conditional: bool


# ── reading the build file ───────────────────────────────────────────────────

#: An identifier in the settings file. The graph is interested in two of them:
#: `include` and `if`.
_IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')


def _read_string(text: str, index: int) -> tuple[str, int]:
    """The string literal at *index*, and the index after it.

    Kotlin strings may not span lines, so an unterminated one ends the scan.
    """
    assert text[index] == '"'
    index += 1
    start = index
    while index < len(text):
        char = text[index]
        if char == '\\':
            index += 2
            continue
        if char == '"':
            return text[start:index], index + 1
        if char == '\n':
            return text[start:index], index
        index += 1
    return text[start:index], index


def _skip_space(text: str, index: int) -> int:
    """The first index at or after *index* that is not whitespace."""
    while index < len(text) and text[index].isspace():
        index += 1
    return index


def _skip_parens(text: str, index: int) -> int:
    """The index after the parenthesis group that starts at or after *index*.

    The condition of an `if` may hold anything, including a parenthesis of its
    own, so the group is counted rather than assumed to end at the first `)`.
    """
    index = _skip_space(text, index)
    if index >= len(text) or text[index] != '(':
        return index
    depth = 0
    while index < len(text):
        char = text[index]
        if text.startswith('//', index):
            newline = text.find('\n', index)
            index = len(text) if newline < 0 else newline
            continue
        if text.startswith('/*', index):
            end = text.find('*/', index + 2)
            index = len(text) if end < 0 else end + 2
            continue
        if char == '"':
            _value, index = _read_string(text, index)
            continue
        if char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return index


def _include_argument(text: str, index: int) -> tuple[str | None, int]:
    """The path an `include` names, and the index after the call.

    The argument is a string literal in every build file that matters. When it
    is something else — a variable, a concatenation — the path is unknown and
    the include is not recorded: a module the graph cannot name is a module it
    cannot judge, and saying so beats guessing.
    """
    index = _skip_space(text, index)
    if index >= len(text) or text[index] != '(':
        return None, index
    index = _skip_space(text, index + 1)
    if index >= len(text) or text[index] != '"':
        end = text.find(')', index)
        return None, len(text) if end < 0 else end + 1
    value, index = _read_string(text, index)
    index = _skip_space(text, index)
    if index < len(text) and text[index] == ')':
        index += 1
    return value, index


def _tokens(text: str) -> Iterator[tuple[str, str]]:
    """The structural tokens of a settings file.

    Three kinds matter to the module graph: an `include("…")` call, a block
    that opens and a block that closes. Everything else — plugins, versions,
    repositories — is the build's business.

    The scan skips comments and string literals, and it has to: the settings
    file holds a Maven URL, and a `//` inside that string is not a comment. A
    scanner that stripped comments naively would cut the line in half, lose
    the closing brace of the block around it, and then read every module after
    that point as being inside an `if`.
    """
    index = 0
    length = len(text)
    while index < length:
        if text.startswith('//', index):
            newline = text.find('\n', index)
            index = length if newline < 0 else newline
            continue
        if text.startswith('/*', index):
            end = text.find('*/', index + 2)
            index = length if end < 0 else end + 2
            continue
        char = text[index]
        if char == '"':
            _value, index = _read_string(text, index)
            continue
        if char == '{':
            yield ('open', 'plain')
            index += 1
            continue
        if char == '}':
            yield ('close', '')
            index += 1
            continue
        match = _IDENTIFIER.match(text, index)
        if match:
            word = match.group(0)
            index = match.end()
            if word == 'include':
                argument, index = _include_argument(text, index)
                if argument is not None:
                    yield ('include', argument)
                continue
            if word == 'if':
                index = _skip_parens(text, index)
                cursor = _skip_space(text, index)
                if cursor < length and text[cursor] == '{':
                    # A block that opens on a condition is a conditional block,
                    # and everything inside it is conditional.
                    yield ('open', 'conditional')
                    index = cursor + 1
                continue
            continue
        index += 1


def parse_modules(text: str) -> list[Module]:
    """The modules a settings file declares, in the order it declares them.

    A module is an `include("…")` call. An include inside a conditional block
    is a module the build declares only sometimes, and it is reported as
    such: the graph knows it, and the decision about judging it is taken
    explicitly (see `CONDITIONAL_POLICY`), not by the accident of a lookup
    that happens to miss it.
    """
    blocks: list[bool] = []
    modules: list[Module] = []
    for kind, value in _tokens(text):
        if kind == 'open':
            blocks.append(value == 'conditional')
        elif kind == 'close':
            if blocks:
                blocks.pop()
        else:
            segments = [segment for segment in value.strip(':').split(':') if segment]
            if not segments:
                continue
            name = ':'.join(segments)
            modules.append(Module(
                name=name,
                gradle_path=f':{name}',
                directory='/'.join(segments),
                conditional=any(blocks),
            ))
    return modules


@lru_cache(maxsize=8)
def load_modules(settings: pathlib.Path | None = None) -> dict[str, Module]:
    """The modules the build declares, keyed by their name.

    The build file is the definition. Where it cannot be read — a tree
    without one, like the hook fixture in `scripts/tests/test_pre_push_hook.py`,
    which copies this script into an empty repository — the modules the graph
    already judges are the modules it knows, and that fallback is used
    knowingly rather than dressed up as a build.
    """
    source = SETTINGS if settings is None else settings
    try:
        text = source.read_text(encoding='utf-8')
    except OSError:
        return {
            name: Module(
                name=name,
                gradle_path=f':{name}',
                directory=name.replace(':', '/'),
                conditional=False,
            )
            for name in TASKS.keys()
        }
    return {module.name: module for module in parse_modules(text)}


def path_modules(modules: dict[str, Module]) -> dict[str, str]:
    """Which module owns which path prefix.

    The prefix is the module's directory, the way Gradle maps a project path
    to a directory. The longest prefix wins, so a nested module is not
    swallowed by a module that owns its parent directory.
    """
    prefixes = {f'{module.directory}/': name for name, module in modules.items()}
    return dict(sorted(prefixes.items(), key=lambda item: len(item[0]), reverse=True))


# ── what a module owes ───────────────────────────────────────────────────────


class ModuleChecks(dict):
    """The checks every judged module owes, keyed by the module's name.

    Two views, and the difference is deliberate:

    * `keys()` and `items()` see EVERY judged module, including one whose
      verdict is a test suite rather than a detekt run;
    * iteration yields only the modules that HAVE a detekt verdict, because
      that is the list `scripts/pre-push-verdict.py` turns into detekt tasks
      (`f":{module}:detekt"`). A module without a detekt task in its build
      script would make that synthesis name a task the build does not have,
      and the hook would fail on every wide push.

    The distinction is the reason the graph reads the build: `:tools:unwritten-properties`
    applies the plain `kotlin("jvm")` plugin, so it has no detekt task, and a
    graph that invented one would be wrong in a way that only shows up as a
    failed build.
    """

    def __iter__(self) -> Iterator[str]:
        return iter(self.detekt_modules())

    def detekt_modules(self) -> list[str]:
        """The modules that have a detekt verdict, in name order."""
        return sorted(
            name for name, checks in dict.items(self)
            if any(check.endswith(':detekt') for check in checks)
        )

    def all_modules(self) -> list[str]:
        """Every judged module, in name order."""
        return sorted(dict.keys(self))


#: What has to run for a module that changed.
#:
#: The tests, because the module's own behaviour may have changed; the detekt
#: run, because the rules are the same rules everywhere.
#:
#: `:tools:unwritten-properties` has a test task and NO detekt task: its build
#: script applies the plain `kotlin("jvm")` plugin, and a detekt rule run is
#: not something the build can do for it. Its checks are its tests.
TASKS: ModuleChecks = ModuleChecks({
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
    'tools:unwritten-properties': [':tools:unwritten-properties:test'],
})

#: What a change in a module can break.
#:
#: The edges are the ones the build files declare: `:core-base` is consumed by
#: `:adr-core`, `:adr-core` by `:shared`, and `:shared` by the four modules that
#: compile against it. `:adr-cli` depends on `:adr-core` and on nothing else —
#: it was deliberately cut off from `:shared`, so a change in `:shared` does not
#: reach it.
#:
#: A module that is not named here reaches nothing but itself, and that is the
#: natural reading for a leaf: `:tools:unwritten-properties` is a KSP processor
#: that no module compiles against, so a change in it breaks it and nothing
#: else. The nested path makes no difference to that rule — what a change
#: reaches is a fact about the dependency edges, not about how deep the module
#: sits in the directory tree.
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

#: What the plan does with a module the build includes CONDITIONALLY.
#:
#: `'exclude'` — the plan does not schedule the module's checks, because they
#: do not exist in the configuration the plan runs in: `:pro` only exists in
#: the `-PwithPro` build, and a plan that named `:pro:detekt` in the free
#: build would hand the developer a task that cannot resolve. The module is
#: still KNOWN — a change under `pro/` is recognized as a change in a module
#: and says so — and the CI judges that module in the configuration that has
#: it (see `config/hooks/pre-push.json`).
#:
#: `'include'` — the plan schedules the module's checks and the run needs the
#: configuration that has them.
CONDITIONAL_POLICY = 'exclude'

#: Paths that change the build itself. Everything downstream of them is affected.
BUILD_AREAS = (
    'gradle/',
    'build-logic/',
    'gradlew',
    'gradlew.bat',
    'gradle.properties',
    'build.gradle.kts',
    'settings.gradle.kts',
)

#: What has to COMPILE for a module that changed.
#:
#: The tasks above answer "what has to run to know the change is good" — the
#: tests and the detekt run. These answer a different question: "what has to
#: compile for the commit to be a commit that compiles". A commit gate wants
#: the second, and it wants it for the same modules the first list names: the
#: module that changed and the modules that compile against it.
#:
#: Only the JVM targets are here. The Android target of `:shared` is compiled
#: by `:androidApp`, and `:androidApp` itself needs the Android SDK — the
#: Android toolchain is CI's, and the local loop says so rather than
#: pretending it checked. `scripts/compile-changed.py` holds that policy.
COMPILE_TASKS: dict[str, list[str]] = {
    'shared': [':shared:compileKotlinJvm', ':shared:compileTestKotlinJvm'],
    'core-base': [':core-base:compileKotlinJvm'],
    'adr-core': [':adr-core:compileKotlinJvm'],
    'adr-cli': [':adr-cli:compileKotlin'],
    'detekt-rules': [':detekt-rules:compileKotlin'],
    'desktopApp': [':desktopApp:compileKotlin'],
    'mcp-server': [':mcp-server:compileKotlin'],
    'androidApp': [':androidApp:compileDebugKotlin'],
}

#: The areas where a change is a change to the *gates* rather than to the app.
GATE_AREAS = (
    'scripts/',
    '.github/',
    'docs/',
    'config/',
    '.specify/',
    'openspec/',
)

#: The modules the build declares, and the paths they own.
#:
#: Read once, at import, so that a reader of this file sees the module list
#: the plan works with — and so that the consistency check below has something
#: to compare the build against.
MODULES: dict[str, Module] = load_modules()
PATH_MODULES: dict[str, str] = path_modules(MODULES)


def consistency_errors(
    modules: dict[str, Module] | None = None,
    checks: ModuleChecks | None = None,
) -> list[str]:
    """Where the graph and the build disagree.

    A module the build declares and the graph does not judge is a module
    nobody runs — the defect this graph was rebuilt to end, and the one that
    left `:tools:unwritten-properties` without a verdict. A module the graph
    judges and the build does not declare is a module that cannot be built at
    all. Both are stated rather than absorbed: a graph that quietly forgets a
    module is a graph that quietly judges nothing.
    """
    declared = MODULES if modules is None else modules
    owed = TASKS if checks is None else checks
    errors: list[str] = []
    for name, module in sorted(declared.items()):
        if module.conditional:
            # A conditional module is judged by the configuration that has it;
            # `CONDITIONAL_POLICY` says which plan that is.
            continue
        if name not in owed:
            errors.append(
                f'the build declares :{name}, and the graph judges no checks for it — '
                f'a module nobody runs',
            )
    for name in owed.all_modules():
        if name not in declared:
            errors.append(
                f'the graph judges :{name}, and the build does not declare it — '
                f'a module that cannot be built',
            )
    return errors


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

    known = load_modules()
    prefixes = path_modules(known)

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
            (module for prefix, module in prefixes.items() if path.startswith(prefix)),
            None,
        )
        if owner is None:
            reasons.append(f'{path} — no module owns it')
            continue
        if known[owner].conditional:
            # The decision is written down, not implied by a lookup that
            # happens to miss the module.
            if CONDITIONAL_POLICY == 'include':
                modules.add(owner)
                reasons.append(
                    f'{path} — {owner} changed, and it is included only in the '
                    f'conditional build; its checks need that configuration',
                )
            else:
                reasons.append(
                    f'{path} — {owner} changed, and it is included only in the '
                    f'conditional build; its checks are not part of this plan',
                )
            continue
        modules.add(owner)
        reasons.append(f'{path} — {owner} changed')

    if everything:
        for module in TASKS.all_modules():
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

    errors = consistency_errors()
    if errors:
        for error in errors:
            print(f'error: {error}', file=sys.stderr)
        print(
            'the module graph and the build disagree — no plan can be trusted',
            file=sys.stderr,
        )
        return 2

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
