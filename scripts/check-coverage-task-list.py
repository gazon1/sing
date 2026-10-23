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
Every project that has JVM tests is either

1. in the `koverReport` list — its tests run in the coverage job and its
   numbers are in the report; or
2. excluded from the coverage scope by `skipProjects(...)` in
   `settings.gradle.kts` — its classes are not in the denominator at all, and
   the report does not pretend otherwise.

A project that is neither is a project the report says nothing about while
the reader assumes it does.

What this does not do
---------------------
It does not run Gradle and it does not check the FLOOR — that is
`:koverVerify`'s job, and the control in the gate registry raises a floor to
99 and requires the task to fail. This check is the cheap half: it catches a
module being added to the build and to the denominator without its tests
being added to the measurement.

The scan is textual, like the floor-declaration gate: the repository has no
Kotlin-DSL parser, and the projects that matter are found by the two things
their build script says out loud — that they configure `Test` tasks, and
that they are part of the coverage scope or of the measurement list.

Usage:
    python3 scripts/check-coverage-task-list.py            # report
    python3 scripts/check-coverage-task-list.py --quiet
"""

from __future__ import annotations

import pathlib
import re
import sys

import click
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


def _report_task_projects() -> set[str]:
    """The projects the `koverReport` task depends on."""
    build = ROOT / "build.gradle.kts"
    if not build.is_file():
        return set()
    text = build.read_text(encoding="utf-8")
    start = text.find(f'tasks.register("{REPORT_TASK}")')
    if start < 0:
        return set()
    block = text[start:]
    end = block.find("\n}")
    body = block[:end] if end > 0 else block
    return set(re.findall(r'"(:[^":]+):[^"]+"', body))


def _projects_with_tests() -> dict[str, pathlib.Path]:
    """The projects whose build script configures JVM test tasks."""
    found: dict[str, pathlib.Path] = {}
    for path in sorted(ROOT.glob("*/build.gradle.kts")):
        if path.parent.name in {"build", "build-logic"}:
            continue
        text = path.read_text(encoding="utf-8")
        if _TEST_DECLARATION.search(text):
            found[f":{path.parent.name}"] = path
    return found


def check() -> list[str]:
    """The findings; empty when the tree is correct."""
    errors: list[str] = []

    measured = _report_task_projects()
    skipped = _skip_projects()
    included = _included_projects()

    for project, script in _projects_with_tests().items():
        if project not in included:
            continue
        if project in measured or project in skipped:
            continue
        errors.append(
            f"{script.relative_to(ROOT).as_posix()}: the project {project} has JVM tests, "
            f"and neither the `{REPORT_TASK}` list nor `skipProjects(...)` mentions it. Its "
            f"tests do not run in the coverage job, so the report says nothing about it "
            f"while the reader assumes it does. Add `\":{project}:<testTask>\"` to the "
            f"`{REPORT_TASK}` list in build.gradle.kts, or exclude the project from the "
            f"coverage scope with `skipProjects(\"{project}\")` in settings.gradle.kts — "
            f"and mean it: the second says the project's classes are not part of the "
            f"app's coverage at all."
        )

    for project in sorted(measured):
        if project not in included:
            errors.append(
                f"build.gradle.kts: the `{REPORT_TASK}` list names {project}, and no "
                f"settings.gradle.kts includes it. A project that does not exist is a "
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
            "check-coverage-task-list: every project with tests is either measured by the "
            "coverage report or excluded from its scope."
        )
        raise ctx.exit(VALIDATION_FAILURE)
    if not quiet:
        click.echo(
            "check-coverage-task-list: OK — every project with tests is in the "
            f"`{REPORT_TASK}` list or excluded from the coverage scope."
        )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
