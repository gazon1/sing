#!/usr/bin/env python3
"""test-impact-map.py — which tests a change actually requires.

Why
---
The local verification loop costs about thirteen minutes, and the whole-tree test
run is most of it. Running "everything" on every change is safe and useless: it
teaches the developer to skip the suite, and the suite is the only thing that
says the change did not break anything.

`changed-modules.py` answers a coarser question — *which module's checks have to
run* — and it answers it from the module graph the build files declare. This
script answers the finer one: **which test classes can this change break?**

The answer is computed, not guessed:

* a change in a scope can break the tests of that scope — the feature's own
  tests are the ones that know its rules;
* a change in a named class can break every test that *mentions* that class, and
  a test that mentions a class is the test that can break because of it;
* a change can break every gate that READS THE WHOLE TREE it is in — a gate
  that walks `commonMain` reads every file in it, so it can break because of
  any change in that root, whatever the change is about;
* a changed test is a test that has to run — it may now say something different
  about the code it checks;
* a change in the gates is not a change to the app, and the gate suite is what
  has to run.

Everything the map says is a **candidate**, not a verdict. The map is narrow
where it can be and wide where it must be: it never claims that a test outside
the map is safe, it only says which ones are worth running first.

The map names a POPULATION, not a tag list. The populations are declared in
`config/test-populations.properties`, and the command the map prints asks for
one of them by name — so the command cannot select a population the repository
has not declared, and the same tool cannot answer two different questions in
two places. A class whose tag is outside the chosen population is dropped from
the command and named in the report: a `--tests` filter that names a class the
population excludes runs nothing and reports green.

Three failures are loud, because a silent one is indistinguishable from a
change that breaks nothing:

* a base reference that does not resolve is an error, not an empty selection;
* a selection that is empty is reported on stdout, where the caller reads it;
* a population that is not declared is an error.

The command names the TASK that runs the class, and the task belongs to the
SOURCE SET: `shared` carries `commonTest`, `jvmTest` and `androidHostTest`,
and each of them has its own task. A class of `androidHostTest` does not
exist under `:shared:jvmTest` — the filter that names it selects nothing
there, and a run that ran nothing reports green.

One task is not narrowed by the POPULATION, and the map says so rather than
letting the reader assume otherwise: `:shared:testAndroidHostTest` is exempt
from the tag filter (`shared/build.gradle.kts`), so the population does not
narrow that task — a class the population excludes would still run if the map
named it. What narrows it is the map's own `--tests` filter, which Gradle
applies to the task: the command runs exactly the class it names. The
population selects the candidate set; the filter runs it.

Usage:
    python3 scripts/test-impact-map.py                  # the test classes, one per line
    python3 scripts/test-impact-map.py --explain        # the same, with the reasons
    python3 scripts/test-impact-map.py --command        # the gradle commands to run them
    python3 scripts/test-impact-map.py --run            # run those commands
    python3 scripts/test-impact-map.py --base origin/main
    python3 scripts/test-impact-map.py --population fast
    python3 scripts/test-impact-map.py --gradle ./gw    # the private wrapper, not ./gradlew
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

#: The gate scripts live next to this one, and the scanner package under
#: `scripts/src` is what knows how a test class is tagged. The bootstrap is the
#: one `scripts/scanning.py` documents: a checkout that has not installed the
#: repository tools still has to be able to ask the question.
_SCRIPTS_DIR = str(pathlib.Path(__file__).resolve().parent)
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

import scanning  # noqa: E402 — the path entry above is the reason for the late import

#: The directory the arch suite lives in. A test there is a statement about the
#: TREE, not about one class: `CommonMainJvmApiTest` walks every file of
#: `commonMain`, `TestTagCoverageTest` walks every test root, and the Konsist and
#: `SourceScan` gates read their whole source set the same way.
#:
#: The map's other rules are name-shaped: a scope owns its tests, and a mention
#: is a coupling. A tree-wide gate has neither — its scope is empty (it belongs
#: to no feature), and it mentions a class only in the sense that it reads every
#: class. So neither rule can select it, and a change that breaks it selects
#: nothing: the map says "these first", the developer runs them, and the gate
#: that would have said "this is broken" is not among them.
#:
#: That is not hypothetical. A change that replaced `java.util.UUID` with the
#: multiplatform `nextId()` in `DataStoreSessionStore` broke
#: `CommonMainJvmApiTest` — the gate that forbids JVM-only APIs in `commonMain`
#: — and the map did not select it.
#:
#: The DIRECTORY is the marker, not a list of calls. The first version of this
#: rule named four calls (`walkTopDown()` and friends), and it missed
#: `DroppedResultIsReportedTest` (`SourceScan.productionFiles()`),
#: `SingleOfKotlinDefaultsTest` (`Konsist.scopeFromExternalDirectory`) and the
#: `TextField*` scans: about ten fast gates still absent from the map of a
#: `commonMain` change, and each of them reads the tree as literally as the one
#: the rule was written for. A list of calls is a list the next helper escapes.
ARCH_SUITE = '/arch/'

#: Calls that read a whole tree, for a test that is NOT in the arch suite — a
#: fixture-corpus test, for example. Matched against the FILE and not against
#: the class body: the walk usually sits in a top-level or companion helper, so
#: the class that calls it does not contain the call.
#:
#: These are the CALLS, not the names of the classes under test: a gate that
#: names `java.io.File` in a list of forbidden imports mentions `File` without
#: reading every file that uses it.
TREE_WIDE_MARKERS: tuple[str, ...] = (
    'walkTopDown()',
    'Files.walk',
    'commonMainRoot()',
    'jvmTestSourcesRoot()',
    'SourceScan.',
    'Konsist.scopeFromExternalDirectory',
    'TextFieldShapeScan.',
    'SelectableTextScan.',
)

#: Trees whose files a gate reads, and which the Kotlin rules below never see:
#: `MaestroFlowTagsTest` walks every flow and fails on one without `tags:`, and
#: the Room gates read the committed schema exports. A change to a file in one
#: of these can break such a gate, so it selects the tree-wide gates too.
READ_TREES: tuple[str, ...] = (
    'Maestro/',
    'shared/schemas/',
    'androidApp/src/main/res/',
)

#: The Gradle property that switches the coverage agent off. The property
#: DEFAULTS to on (`settings.gradle.kts`), so a run that does not name it is
#: instrumented.
COVERAGE_OFF = '-Pcoverage.enabled=false'

#: The source sets that hold tests, and the module each belongs to.
TEST_SOURCE_DIRS: dict[str, str] = {
    'shared/src/commonTest': 'shared',
    'shared/src/jvmTest': 'shared',
    'shared/src/androidHostTest': 'shared',
    'desktopApp/src/jvmTest': 'desktopApp',
    'mcp-server/src/test': 'mcp-server',
}

#: The source sets that hold production code.
PRODUCTION_SOURCE_DIRS: tuple[str, ...] = (
    'shared/src/commonMain',
    'shared/src/jvmMain',
    'shared/src/androidMain',
    'androidApp/src/main',
    'desktopApp/src/jvmMain',
    'mcp-server/src/main',
)

#: The areas where a change is a change to the *gates*, not to the app.
GATE_AREAS: tuple[str, ...] = (
    'scripts/',
    '.github/',
    'docs/',
    'config/',
    '.specify/',
    'openspec/',
)

#: The gradle task that runs the test classes of each SOURCE SET.
#:
#: The task belongs to the source set and not to the module: `shared` carries
#: `commonTest`, `jvmTest` and `androidHostTest`, and each of them has its own
#: task. A class of `androidHostTest` does not exist under `:shared:jvmTest` —
#: the filter that names it selects nothing there, and Gradle fails a test task
#: that ran no test. The class was named, the run was green, and the class
#: never ran.
#:
#: `:shared:testAndroidHostTest` is not narrowed by the POPULATION: it is
#: exempt from the tag filter (`shared/build.gradle.kts`), so a class the
#: population excludes would still run if the map named it. What narrows it is
#: the `--tests` filter the map puts on the command — the run is exactly the
#: class the map names. The population selects the candidate set; the filter
#: runs it.
SOURCE_SET_TASKS: dict[str, str] = {
    'shared/src/commonTest': ':shared:jvmTest',
    'shared/src/jvmTest': ':shared:jvmTest',
    'shared/src/androidHostTest': ':shared:testAndroidHostTest',
    'desktopApp/src/jvmTest': ':desktopApp:test',
    'mcp-server/src/test': ':mcp-server:test',
}

#: The file that names the populations and the tags each one selects. The same
#: file the build reads, so the map and the build cannot disagree about what
#: `fast` means.
POPULATIONS_FILE = 'config/test-populations.properties'

#: The Gradle entry point the emitted commands name.
#:
#: Overridable, because the local recipe must reach this repository's private
#: wrapper (`./gw`) and not the shared `~/.gradle` that the wrapper exists to
#: avoid — "Parallel git worktrees share `~/.gradle` by default, and sharing it
#: has two costs that are easy to mistake for something else".
GRADLE = './gradlew'

#: What the map prints on stdout when the change selects no test class.
#:
#: The caller reads stdout, so this line is what tells it that the selection
#: was EMPTY — as opposed to a comparison that failed, which is an error and a
#: non-zero exit code. A selection that failed and a selection that found
#: nothing used to look the same, and the difference is the whole answer.
EMPTY_SELECTION = 'test-impact-map: the change selects no test class'

#: The prefix of the line that names a class the population excludes.
DROPPED = 'dropped:'

#: A top-level class declaration in Kotlin. The indentation is part of the
#: pattern on purpose: a nested class is not a test class a `--tests` filter
#: can name.
CLASS_DECLARATION = re.compile(
    r'^(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+)*'
    r'class\s+([A-Za-z_][A-Za-z0-9_]*)',
    re.MULTILINE,
)

#: A test member — the thing that makes a class a test class.
TEST_MEMBER = re.compile(r'^\s*@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b', re.MULTILINE)

#: The package a file declares.
PACKAGE_DECLARATION = re.compile(r'^\s*package\s+([\w.]+)', re.MULTILINE)


class TestClass:
    """One test class: its name, its file, its module, its task, its scope, its body.

    The file's whole source is carried too, because the class-level `@Tag`
    sits ABOVE the declaration — a body that starts at the declaration cannot
    see it, and the population intersection asks exactly that question.
    """

    def __init__(
        self,
        qualified_name: str,
        path: pathlib.Path,
        module: str,
        task: str,
        scope: str,
        body: str,
        source: str = '',
    ) -> None:
        self.qualified_name = qualified_name
        self.path = path
        self.module = module
        self.task = task
        self.scope = scope
        self.body = body
        self.source = source

    def __repr__(self) -> str:  # pragma: no cover — debugging aid
        return f'TestClass({self.qualified_name})'


class UnresolvableBaseRef(Exception):
    """The base reference does not resolve.

    A comparison that could not be made is not a comparison that found
    nothing. The tool used to swallow the failure and report an empty
    selection with exit code 0 — the same answer it gives for a change that
    genuinely touches no test.
    """


class UnknownPopulation(Exception):
    """The population is not declared in `config/test-populations.properties`."""


def populations() -> dict[str, list[str]]:
    """The declared populations: a name -> the tags it selects.

    The file is read the way `config/test-populations.properties` documents
    itself: `=` separates a population from its tags, `:` separates a
    population from one of its properties. A line with no `=` is a property
    and is not a population of its own.
    """
    declared: dict[str, list[str]] = {}
    path = ROOT / POPULATIONS_FILE
    if not path.is_file():
        return declared
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.split('#', 1)[0].strip()
        if not line or '=' not in line:
            continue
        name, _, tags = line.partition('=')
        name = name.strip()
        if not name:
            continue
        declared[name] = [tag.strip() for tag in tags.split(',') if tag.strip()]
    return declared


def default_population() -> str:
    """The population the tool uses when none is named: the first declared one.

    `config/test-populations.properties` declares `fast` first, and `fast` is
    what a pull request runs.
    """
    declared = populations()
    if not declared:
        raise UnknownPopulation(
            f'{POPULATIONS_FILE} declares no population, so the map has none to '
            'select. A tool that names a population the repository has not '
            'declared cannot be checked against it.'
        )
    return next(iter(declared))


def population_tags(population: str) -> list[str]:
    """The tags of a named population; an undeclared one is an error."""
    declared = populations()
    if population not in declared:
        known = ', '.join(sorted(declared)) or 'none'
        raise UnknownPopulation(
            f"the population '{population}' is not declared in {POPULATIONS_FILE} "
            f'(declared: {known}). A command that names a population nobody '
            'declared selects nothing and reports green.'
        )
    return declared[population]


def _is_tree_wide(test: TestClass) -> bool:
    """Does this test read a source tree, rather than one class?

    A gate in the arch suite decides what the tree is allowed to contain, so any
    change to a tree it reads can break it. Outside that suite, the CALL that
    reads a root is the marker — matched against the file, because the walk
    usually sits in a helper above the class.
    """
    if ARCH_SUITE in test.path.as_posix():
        return True
    return any(marker in test.source for marker in TREE_WIDE_MARKERS)


def _scope_from_tail(tail: str) -> str:
    """The scope of a path below a source root — `feature.tasks`, `core.database`.

    The scope is the part of the path after `feature/` or `core/`: the feature
    is the unit a developer thinks in, and the tests of a feature are the ones
    that know its rules.
    """
    parts = [part for part in tail.split('/') if part]
    for marker in ('feature', 'core'):
        if marker in parts:
            index = parts.index(marker)
            if marker == 'core' and index + 1 < len(parts):
                return f'core.{parts[index + 1]}'
            if index + 1 < len(parts):
                return f'{marker}.{parts[index + 1]}'
            return marker
    return ''


def changed_paths(base: str) -> list[str]:
    """The paths this change touches: committed and uncommitted alike.

    A change that is not committed yet is still a change, and a map that only
    looked at the commits would name nothing for the work in front of the
    developer.

    A base reference that does not resolve is an ERROR. The comparison could
    not be made, and a comparison that could not be made is not a comparison
    that found nothing: the tool used to swallow the failed `git diff` and
    report an empty selection with exit code 0 — the same answer it gives for
    a change that genuinely touches no test.
    """
    resolved = subprocess.run(
        ['git', 'rev-parse', '--verify', '--quiet', f'{base}^{{commit}}'],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if resolved.returncode != 0:
        raise UnresolvableBaseRef(
            f"the base reference '{base}' does not resolve in this checkout. A "
            'comparison that could not be made is not a comparison that found '
            'nothing, so the map refuses to report an empty selection for it.'
        )

    paths: list[str] = []
    committed = subprocess.run(
        ['git', 'diff', '--name-only', f'{base}...HEAD'],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if committed.returncode != 0:
        detail = committed.stderr.strip() or f'git diff exited with {committed.returncode}'
        raise UnresolvableBaseRef(
            f"the comparison against the base reference '{base}' failed: {detail}. "
            'A comparison that could not be made is not a comparison that found '
            'nothing.'
        )
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


def _class_blocks(text: str) -> list[tuple[str, str]]:
    """The top-level classes of a file: their name and their body.

    A nested class is not a test class a `--tests` filter can name, so only the
    top-level ones are collected — the pattern matches the declaration at the
    left margin.
    """
    matches = list(CLASS_DECLARATION.finditer(text))
    blocks: list[tuple[str, str]] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        blocks.append((match.group(1), text[match.start():end]))
    return blocks


def test_classes() -> list[TestClass]:
    """Every test class of the repository, with its module, its task and its scope."""
    found: list[TestClass] = []
    for source_dir, module in TEST_SOURCE_DIRS.items():
        root = ROOT / source_dir
        if not root.is_dir():
            continue
        for path in sorted(root.rglob('*.kt')):
            text = path.read_text(encoding='utf-8', errors='replace')
            package_match = PACKAGE_DECLARATION.search(text)
            package = package_match.group(1) if package_match else ''
            scope = _scope_from_tail(path.relative_to(ROOT / source_dir).as_posix())
            for name, body in _class_blocks(text):
                if 'Test' not in name and not TEST_MEMBER.search(body):
                    continue
                qualified = f'{package}.{name}' if package else name
                found.append(
                    TestClass(
                        qualified,
                        path,
                        module,
                        SOURCE_SET_TASKS[source_dir],
                        scope,
                        body,
                        text,
                    )
                )
    return found


def _declared_names(path: str) -> list[str]:
    """The names a production file declares — the ones another file can mention."""
    file_path = ROOT / path
    if not file_path.is_file() or file_path.suffix != '.kt':
        return []
    text = file_path.read_text(encoding='utf-8', errors='replace')
    return [name for name, _ in _class_blocks(text)]


def _is_production(path: str) -> bool:
    return any(path.startswith(directory) for directory in PRODUCTION_SOURCE_DIRS)


def _test_source_of(path: str) -> str | None:
    for source_dir in TEST_SOURCE_DIRS:
        if path.startswith(source_dir):
            return source_dir
    return None


def _relative(path: pathlib.Path) -> str:
    """The path of a file relative to the repository root.

    A file outside the root has no relative path, and asking for one would
    raise — so the absolute path is used instead. The map compares paths, and a
    path it cannot place is a path that matches nothing.
    """
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def map_tests(paths: list[str], tests: list[TestClass]) -> tuple[list[str], list[str]]:
    """The test classes the change can break, and why each one is in the map."""
    selected: dict[str, str] = {}
    reasons: list[str] = []
    tree_wide = [test for test in tests if _is_tree_wide(test)]

    for path in paths:
        if path.startswith(GATE_AREAS):
            reasons.append(f'{path} — a change to the gates, not to the app')
            continue

        if _test_source_of(path) is not None:
            # A changed test is a test that has to run: it may now say something
            # different about the code it checks.
            for test in tests:
                if _relative(test.path) == path:
                    selected.setdefault(test.qualified_name, f'{path} — the test itself changed')
            # And it can break a gate that reads the whole test tree — a gate
            # that asserts every test class carries a @Tag is broken by a test
            # class that does not, and the class that broke it is the one that
            # changed.
            for test in tree_wide:
                selected.setdefault(
                    test.qualified_name,
                    f'{path} — the gate reads the whole tree the change is in',
                )
            continue

        if not path.endswith('.kt'):
            if path.startswith(READ_TREES):
                # A gate reads this tree and the Kotlin rules below never see
                # the file: `MaestroFlowTagsTest` walks every flow, and a flow
                # with no `tags:` breaks it. The class that broke it is the
                # changed file, so the gate has to run.
                for test in tree_wide:
                    selected.setdefault(
                        test.qualified_name,
                        f'{path} — the gate reads the whole tree the change is in',
                    )
                reasons.append(f'{path} — a tree a gate reads, not a Kotlin source')
            else:
                reasons.append(f'{path} — no test class claims it')
            continue

        if not _is_production(path):
            reasons.append(f'{path} — no test class claims it')
            continue

        scope = _scope_from_tail(path.split('/src/', 1)[-1])
        names = _declared_names(path)
        for test in tests:
            if scope and test.scope == scope:
                selected.setdefault(test.qualified_name, f'{path} — the {scope} scope')
                continue
            if _is_tree_wide(test):
                selected.setdefault(
                    test.qualified_name,
                    f'{path} — the gate reads the whole tree the change is in',
                )
                continue
            if not names:
                continue
            if any(name in test.body for name in names):
                selected.setdefault(test.qualified_name, f'{path} — the test mentions it')

    for name in sorted(selected):
        reasons.append(f'{name} — {selected[name]}')
    return sorted(selected), reasons


def select_for_population(
    tests: list[TestClass],
    tags: list[str],
) -> tuple[list[TestClass], list[str]]:
    """The classes the population selects, and the ones it excludes.

    The build selects a class by its MAIN tag — `includeTags("fast")` is an
    exact match, and a class that carries only a sub-tag is selected by no run
    at all. The map asks the same question the same way, so a class the
    population excludes is dropped from the command and NAMED in the report:
    a `--tests` filter that names a class the population excludes runs
    nothing, and a run that ran nothing reports green.
    """
    selected = set(tags)
    kept: list[TestClass] = []
    dropped: list[str] = []
    for test in tests:
        simple = test.qualified_name.rsplit('.', 1)[-1]
        tag = scanning.read_tag(test.source, simple)
        if tag in selected:
            kept.append(test)
            continue
        dropped.append(
            f'{DROPPED} {test.qualified_name} — its tag is \'{tag}\', which the '
            f'population does not select'
        )
    return kept, dropped


def gradle_commands(
    tests: list[TestClass],
    population: str,
    gradle: str = GRADLE,
) -> list[list[str]]:
    """The gradle commands that run the mapped classes — one per task.

    One command per task, because `--tests` is a filter of the whole
    invocation: a filter that names a class of another task leaves that
    task's run with nothing to run, and Gradle fails a test task that ran no
    test. The grouping is by TASK and not by module: one module carries
    several source sets, and each of them has its own task — a class of
    `androidHostTest` does not exist under `:shared:jvmTest`.

    The command names the POPULATION, not a tag list. A tag list is a second
    spelling of a thing the repository already declares in one place, and the
    two spellings drift: the map used to answer a different question than the
    one asked, selecting the fast and slow half together.
    """
    by_task: dict[str, list[str]] = {}
    for test in tests:
        by_task.setdefault(test.task, []).append(test.qualified_name)

    commands: list[list[str]] = []
    for task in sorted(by_task):
        command = [gradle, task, f'-Ptest.population={population}']
        for name in sorted(by_task[task]):
            command += ['--tests', name]
        commands.append(command)
    return commands


def report_empty_selection() -> None:
    """Report an empty selection on stdout, where the caller reads it.

    An empty selection is an ANSWER — "this change claims no test class" — and
    the caller has to be able to tell it apart from a comparison that failed.
    The failure is an error and a non-zero exit code; the empty selection is
    this line and exit code 0.
    """
    print(EMPTY_SELECTION)
    print('nothing to run: the change claims no test class.', file=sys.stderr)


def main(argv: list[str], paths: list[str] | None = None) -> int:
    """The tool's entry point.

    `paths` overrides the changed-paths lookup. It exists so that the empty
    selection can be exercised without a change in the tree: the answer the
    caller reads has to be the same answer whatever the tree holds.
    """
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--base', default='origin/main',
                        help='What the change is measured against.')
    parser.add_argument('--explain', action='store_true',
                        help='Print why each test class is in the map.')
    parser.add_argument('--command', action='store_true',
                        help='Print the gradle commands that run the map.')
    parser.add_argument('--run', action='store_true',
                        help='Run those commands.')
    parser.add_argument('--population', default=None,
                        help=('The population the commands select, by name. '
                              f'Defaults to the first population declared in '
                              f'{POPULATIONS_FILE}.'))
    parser.add_argument('--gradle', default=GRADLE,
                        help=('The Gradle entry point the commands name. The '
                              'local recipe passes the private wrapper (./gw), '
                              'not the shared ~/.gradle.'))
    args = parser.parse_args(argv)

    try:
        population = args.population or default_population()
        tags = population_tags(population)
    except UnknownPopulation as error:
        print(f'test-impact-map: {error}', file=sys.stderr)
        return 2

    try:
        changed = changed_paths(args.base) if paths is None else paths
    except UnresolvableBaseRef as error:
        print(f'test-impact-map: {error}', file=sys.stderr)
        return 2

    selected: list[str] = []
    reasons: list[str] = []
    chosen: list[TestClass] = []
    if changed:
        tests = test_classes()
        selected, reasons = map_tests(changed, tests)
        chosen = [test for test in tests if test.qualified_name in selected]
    kept, dropped = select_for_population(chosen, tags)

    for line in dropped:
        print(line, file=sys.stderr)

    if args.explain:
        for reason in reasons:
            print(f'  {reason}', file=sys.stderr)
        print('', file=sys.stderr)

    commands = gradle_commands(kept, population, args.gradle)
    if args.run:
        if not commands:
            report_empty_selection()
            return 0
        for command in commands:
            # `-Pcoverage.enabled=false`: this is the FAST LANE, and the Kover
            # agent is the difference between a signal and a wait — measured on
            # this tree, the instrumented suite is 1241 s and the uninstrumented
            # one 771 s. A run of a SUBSET of the suite measures a coverage
            # number that describes the subset, which is not a measurement
            # anybody can act on; the coverage job is where the measurement
            # belongs.
            #
            # The flag is added HERE and not to the emitted command: `--command`
            # prints the selection for a caller to run, and the caller decides
            # what to run it with (the CI `quick` job adds the same flag). This
            # path RUNS the command, so it decides for itself.
            print(' '.join([*command, COVERAGE_OFF]), file=sys.stderr)
            result = subprocess.run(
                [*command, COVERAGE_OFF], cwd=ROOT, check=False,
            )
            if result.returncode != 0:
                return result.returncode
        return 0

    if args.command:
        if not commands:
            report_empty_selection()
            return 0
        for command in commands:
            print(' '.join(command))
        return 0

    for name in selected:
        print(name)
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
