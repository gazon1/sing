#!/usr/bin/env python3
"""check-instrumented-test-floor.py — the instrumented suite's floor.

## The defect this catches

The instrumented suite is the one verification surface that no CI job ran at all
before `android-device-tests.yml` existed. It ran on a schedule, on pushes to
`main`, on tags and on demand — and a suite that runs on a schedule is a suite
whose size nobody watches. A test class can be deleted, a whole package can be
renamed out of the way, a device can stop being reachable for one class only,
and the run stays green: the report lists the tests that are left, and the ones
that are gone are only visible in a diff nobody reads.

This is the same defect the JVM tiers already have a floor for
(`scripts/check-test-runs.py`, `config/docs/test-runs-baseline.txt`). The
instrumented tier had none, because a floor for it needs the report — and the
report only exists after the emulator run.

## What this gate holds

`config/docs/instrumented-test-floor.txt` is the recorded floor:

```
classes 5
tests   19
skipped 0
```

- **classes** and **tests** are floors: a run with fewer classes or fewer tests
  than the recorded floor means coverage was lost, and the gate says by how
  much.
- **skipped** is a ceiling, and it is 0. A skipped instrumented test is a test
  that did not run on a device — the tier exists because a device run is the
  only way to know the app works on a device, and a test that skips itself
  reports nothing about that.

## Where it runs

Not in the static gate list: this gate reads the report of the emulator run, so
it runs where the report is — after `connectedDebugAndroidTest`, in
`.github/workflows/android-device-tests.yml`. A gate that needs a build artifact
cannot run in the job that has no build.

Usage: python3 scripts/check-instrumented-test-floor.py [--report-dir DIR]
                                                      [--floor FILE]
                                                      [--json] [--quiet]
"""
from __future__ import annotations

import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
FLOOR_FILE = ROOT / "config" / "docs" / "instrumented-test-floor.txt"
REPORT_DIR = ROOT / "androidApp" / "build" / "outputs" / "androidTest-results" / "connected"

FLOOR_PATTERN = re.compile(r"^(?P<name>[a-z][a-z0-9-]*)\s+(?P<value>\d+)$")


def read_floor(path: pathlib.Path) -> dict[str, int]:
    """The recorded floor, as name -> number."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise click.ClickException(f"cannot read {path}: {exc}") from exc
    floor: dict[str, int] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = FLOOR_PATTERN.match(stripped)
        if not match:
            raise click.ClickException(
                f"{path}:{number}: cannot read a floor from {stripped!r}; "
                "the format is '<name> <number>'"
            )
        name = match.group("name")
        if name in floor:
            raise click.ClickException(f"{path}:{number}: the floor {name!r} is listed twice")
        floor[name] = int(match.group("value"))
    for required in ("classes", "tests", "skipped"):
        if required not in floor:
            raise click.ClickException(f"{path}: the floor does not name {required!r}")
    return floor


def report_files(report_dir: pathlib.Path) -> list[pathlib.Path]:
    """The JUnit XML files of the instrumented run."""
    if not report_dir.is_dir():
        return []
    return sorted(p for p in report_dir.rglob("*.xml") if p.is_file())


def measure(report_dir: pathlib.Path) -> dict:
    """What the run actually did: classes, tests, skipped, and their names."""
    classes: set[str] = set()
    tests = 0
    skipped: list[str] = []
    for path in report_files(report_dir):
        try:
            tree = ET.parse(path)  # noqa: S314 — the report is written by AGP, not by a user
        except ET.ParseError:
            continue
        for case in tree.getroot().iter("testcase"):
            name = case.get("classname") or case.get("class") or "unknown"
            classes.add(name)
            tests += 1
            if case.find("skipped") is not None:
                skipped.append(f"{name}.{case.get('name')}")
    return {
        "classes": len(classes),
        "tests": tests,
        "skipped": len(skipped),
        "skipped_tests": skipped,
        "class_names": sorted(classes),
    }


def scan(report_dir: pathlib.Path, floor: dict[str, int]) -> list[dict]:
    """The findings: a run below the floor, or a test that did not run."""
    findings: list[dict] = []
    if not report_files(report_dir):
        findings.append(
            {
                "kind": "no report",
                "detail": (
                    f"{report_dir} holds no JUnit XML, so the instrumented suite either "
                    "did not run or wrote its report somewhere else. A tier that does "
                    "not run must not report as a tier that ran"
                ),
            }
        )
        return findings

    measured = measure(report_dir)

    for name in ("classes", "tests"):
        if measured[name] < floor[name]:
            findings.append(
                {
                    "kind": f"{name} below the floor",
                    "detail": (
                        f"the run reported {measured[name]} {name} and the recorded floor "
                        f"is {floor[name]} — coverage was lost, and the run is green "
                        "because the report lists what is left"
                    ),
                }
            )

    if measured["skipped"] > floor["skipped"]:
        findings.append(
            {
                "kind": "tests skipped",
                "detail": (
                    f"{measured['skipped']} test(s) skipped and the ceiling is "
                    f"{floor['skipped']}: a skipped instrumented test says nothing about "
                    "the app on a device, which is the only thing this tier knows — "
                    + ", ".join(measured["skipped_tests"])
                ),
            }
        )

    return findings


@click.command()
@click.option("--report-dir", default=str(REPORT_DIR), help="Where the instrumented report is")
@click.option("--floor", default=str(FLOOR_FILE), help="The recorded floor file")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--quiet", is_flag=True, help="Print nothing when the gate is clean")
@click.pass_context
def cli(
    ctx: click.Context,
    report_dir: str,
    floor: str,
    emit_json: bool,
    quiet: bool,
) -> None:
    try:
        recorded = read_floor(pathlib.Path(floor))
        findings = scan(pathlib.Path(report_dir), recorded)
        measured = measure(pathlib.Path(report_dir))
    except click.ClickException as exc:
        click.echo(f"instrumented test floor: {exc.format_message()}")
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(json.dumps({"findings": findings, "measured": measured}, indent=2))
    elif not findings and not quiet:
        click.echo(
            "instrumented test floor: "
            + ", ".join(f"{name}={measured[name]}" for name in ("classes", "tests", "skipped"))
            + " — floor met"
        )
    elif findings:
        click.echo(f"instrumented test floor: {len(findings)} finding(s)")
        for finding in findings:
            click.echo(f"  - {finding['kind']}: {finding['detail']}")
    raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
