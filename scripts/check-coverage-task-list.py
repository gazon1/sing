#!/usr/bin/env python3
"""check-coverage-task-list.py — the coverage report measures every module that is in it.

What went wrong
---------------
The list of test tasks that feed the aggregated coverage report is written by
hand, in the root build script:

    tasks.register("koverReport") {
        dependsOn(":shared:jvmTest", ":desktopApp:test", ":mcp-server:test", ...)
    }

A hand-written list is fine until a module is added. The new module compiles,
ships, has tests — and its tests are not in the list, so nothing runs them
during the coverage job, and the report says nothing about them. Nothing
fails: the floor is measured on a smaller denominator than the reader
believes, and the number in the summary is quietly wrong.

The failure is invisible from the report as well: a module whose classes are
in the denominator but whose tests never ran shows up as 0% covered, which
looks like a coverage problem rather than a measurement problem.

The rule
--------
Every project the coverage job BUILDS is either

1. in the `koverReport` list — its tests run in the coverage job and its
   numbers are in the report; or
2. excluded from the coverage scope by `skipProjects(...)` in
   `settings.gradle.kts` — its classes are not in the denominator at all, and
   the report does not pretend otherwise.

A project that is neither is a project the report says nothing about while
the reader assumes it does.

What "the coverage job builds it" means
----------------------------------------
It is not the same question as "does the project have JVM tests?", and the
difference is the whole gate:

  - the `koverReport` list names the projects whose test tasks run;
  - the project dependencies of those projects are built too — a module the
    app pulls in is compiled and instrumented in the coverage job whether or
    not the list names it, so its classes are in the denominator;
  - the application module (`:androidApp`) is in it as well: it IS the app,
    and its classes are the app's classes whatever task ran.

A project nobody builds in the coverage job — a tool that nothing depends on
— contributes no classes to the report. It is not in the denominator, and
asking about its tests would be asking about a configuration this gate does
not describe. That is the difference between a module the report says
nothing about and a module the report scores at 0%.

And the module's OWN test task is not always visible: `:androidApp` declares
no JVM `Test` task at all, so a scan of the build scripts for `Test` tasks
never sees it — and the module whose classes are in the denominator goes
unmeasured while every gate stays green. A module HAS tests when it has a
test source set with files in it, and that is what this gate asks.

What this does not do
---------------------
It does not run Gradle, and it does not check the FLOOR — that is
`:koverVerify`'s job, and CI runs it on every push. What it proves is the
other half: that the measurement list covers the denominator, so the report
cannot measure a smaller set of modules than the reader assumes.

The control that would raise a floor to 99 and require `:koverVerify` to
fail is NOT part of this gate: the instrumented run costs about 37 minutes,
more than the harness deadline, and
`docs/decisions/2026-10-23-a-population-is-a-name.md` records that gap
rather than papering over it.

The scan is textual, like the floor-declaration gate: the repository has no
Kotlin-DSL parser, and the projects that matter are found by the two things
their build script says out loud — that they depend on other projects, and
that they configure `Test` tasks — plus the test source sets on disk.

Usage:
    python3 scripts/check-coverage-task-list.py            # report
    python3 scripts/check-coverage-task-list.py --quiet

Exit codes:
    0 — every project the coverage job builds is measured by the report or
        excluded from its scope
    1 — a finding
"""

from __future__ import annotations

import pathlib
import re
import sys

import click

# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run  # noqa: PLC0415 — scripts are not a package

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The task that decides what the aggregated report is made of.
REPORT_TASK = "koverReport"

#: A build script that configures JVM test tasks says so out loud.
_TEST_DECLARATION = re.compile(r"tasks\.withType<Test>\(\)|tasks\.named<Test>\(|useJUnitPlatform")

#: `skipProjects(":a", ":b")` in the settings script.
_SKIP_PROJECTS = re.compile(r"skipProjects\(([^)]*)\)")

#: The source sets whose files are tests. `:androidApp` configures no JVM
#: `Test` task and has a suite all the same — the source set is what says
#: so, and the build-script scan below would never see it.
_TEST_SOURCE_SETS = (
    "test",
    "jvmTest",
    "commonTest",
    "androidHostTest",
    "androidTest",
)

#: The Android application plugin — the module that IS the app.
_APPLICATION_PLUGIN = re.compile(
    r"libs\.plugins\.androidApplication\b|com\.android\.application\b"
)

#: A project dependency: `project(":shared")`.
_PROJECT_DEPENDENCY = re.compile(r'project\(\s*"(:[^"]+)"\s*\)')

#: The files a test source set is made of.
_TEST_SOURCES = frozenset({".kt", ".java"})


def _project_paths(text: str) -> list[str]:
    """The `:project` paths inside a `skipProjects(...)` call."""
    return re.findall(r'"(:[^"]+)"', text)


def _skip_projects() -> set[str]:
    """The projects excluded from the coverage scope."""
    settings = ROOT / "settings.gradle.kts"
    if not settings.is_file():
        return set()
    text = settings.read_text(encoding="utf-8")
    skipped: set[str] = set()
    for match in _SKIP_PROJECTS.finditer(text):
        skipped.update(_project_paths(match.group(1)))
    return skipped


def _included_projects() -> set[str]:
    """The projects the default build contains.

    Only the UNCONDITIONAL includes count. `:pro` is included behind
    `-PwithPro=true`, and the free configuration — the one the gates and the
    coverage job run — does not contain it. A project that is not in the build
    is not in the coverage denominator either, so asking about its tests would
    be asking about a configuration this gate does not describe.
    """
    settings = ROOT / "settings.gradle.kts"
    if not settings.is_file():
        return set()
    text = settings.read_text(encoding="utf-8")
    return set(re.findall(r'^include\("(:[^"]+)"\)', text, re.MULTILINE))


def _report_task() -> tuple[set[str], int]:
    """The projects the `koverReport` task depends on, and the line of the list."""
    build = ROOT / "build.gradle.kts"
    if not build.is_file():
        return set(), 0
    text = build.read_text(encoding="utf-8")
    start = text.find(f'tasks.register("{REPORT_TASK}")')
    if start < 0:
        return set(), 0
    block = text[start:]
    end = block.find("\n}")
    body = block[:end] if end > 0 else block
    projects = set(re.findall(r'"(:[^":]+):[^"]+"', body))
    depends = body.find("dependsOn")
    line = 0
    if depends >= 0:
        line = text.count("\n", 0, start) + 1 + body.count("\n", 0, depends)
    return projects, line


def _module_dir(project: str) -> pathlib.Path:
    """The directory of a Gradle project path: `:a:b` → `a/b`."""
    return ROOT.joinpath(*project.lstrip(":").split(":"))


def _build_script(project: str) -> pathlib.Path | None:
    """The build script of a project, when it has one."""
    path = _module_dir(project) / "build.gradle.kts"
    return path if path.is_file() else None


def _has_tests(project: str) -> bool:
    """Does the project have a test suite of its own?

    Two things say so, and the first one is the one that matters: a test
    source set with files in it. The second is a build script that
    configures a JVM `Test` task — a task can exist without sources, and a
    source set can exist without the task being declared out loud.
    """
    module = _module_dir(project)
    if not module.is_dir():
        return False
    for source_set in _TEST_SOURCE_SETS:
        directory = module / "src" / source_set
        if not directory.is_dir():
            continue
        for path in directory.rglob("*"):
            if path.suffix in _TEST_SOURCES:
                return True
    script = _build_script(project)
    if script is not None and _TEST_DECLARATION.search(script.read_text(encoding="utf-8")):
        return True
    return False


def _dependencies(project: str) -> set[str]:
    """The projects the given project depends on."""
    script = _build_script(project)
    if script is None:
        return set()
    return set(_PROJECT_DEPENDENCY.findall(script.read_text(encoding="utf-8")))


def _is_application_module(project: str) -> bool:
    """Does the project apply the Android application plugin — i.e. is it the app?"""
    script = _build_script(project)
    if script is None:
        return False
    return bool(_APPLICATION_PLUGIN.search(script.read_text(encoding="utf-8")))


def _denominator(scope: set[str], measured: set[str]) -> set[str]:
    """The projects whose classes the coverage report can hold.

    A project is in it when the coverage job BUILDS it and the coverage
    scope does not skip it: the `koverReport` list names it, the app pulls
    it in as a dependency, or it is the application module itself. A project
    the coverage job never builds contributes no classes to the report, so it
    is not in the denominator — and a module the report says nothing about
    is a different thing from a module the report scores at 0%.
    """
    entry_points = set(measured)
    entry_points.update(project for project in scope if _is_application_module(project))
    visited: set[str] = set()
    pending = list(entry_points)
    while pending:
        current = pending.pop()
        if current in visited:
            continue
        visited.add(current)
        pending.extend(_dependencies(current))
    return {project for project in visited if project in scope}


def check() -> list[str]:
    """The findings; empty when the tree is correct."""
    errors: list[str] = []

    measured, report_line = _report_task()
    skipped = _skip_projects()
    included = _included_projects()
    scope = {project for project in included if project not in skipped}
    denominator = _denominator(scope, measured)

    for project in sorted(denominator):
        if project in measured:
            continue
        if not _has_tests(project):
            continue
        errors.append(
            f"build.gradle.kts:{report_line}: the project {project} is in the coverage "
            f"denominator — the coverage job builds it — and it has tests, but no task "
            f"of it is in the `{REPORT_TASK}` list. Its tests do not run in the coverage "
            f"job, so the report says nothing about it while the reader assumes it does. "
            f"Add `\"{project}:<testTask>\"` to the `{REPORT_TASK}` list in "
            f"build.gradle.kts, or exclude the project from the coverage scope with "
            f"`skipProjects(\"{project}\")` in settings.gradle.kts — and mean it: the "
            f"second says the project's classes are not part of the app's coverage at all."
        )

    for project in sorted(measured):
        if project not in included:
            errors.append(
                f"build.gradle.kts:{report_line}: the `{REPORT_TASK}` list names {project}, "
                f"and no settings.gradle.kts includes it. A project that does not exist is a "
                f"task that does not run, and a module that silently drops out of the "
                f"measurement looks exactly like a module with no tests."
            )

    return errors


@click.command()
@click.option("--quiet", is_flag=True)
@click.pass_context
def cli(ctx: click.Context, quiet: bool) -> None:
    errors = check()
    for error in errors:
        click.echo(f"ERROR: {error}")
    if errors:
        click.echo("")
        click.echo(
            "check-coverage-task-list: every project the coverage job builds is either "
            "measured by the coverage report or excluded from its scope."
        )
        raise ctx.exit(VALIDATION_FAILURE)
    if not quiet:
        click.echo(
            "check-coverage-task-list: OK — every project the coverage job builds is in "
            f"the `{REPORT_TASK}` list or excluded from the coverage scope."
        )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
