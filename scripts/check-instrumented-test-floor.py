#!/usr/bin/env python3
"""check-instrumented-test-floor.py — the instrumented suite's floor, and the ratchet on it.

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
classes 6
tests   21
skipped 0
```

- **classes** and **tests** are floors: a run with fewer classes or fewer tests
  than the recorded floor means coverage was lost, and the gate says by how
  much.
- **skipped** is a ceiling, and it is 0. A skipped instrumented test is a test
  that did not run on a device — the tier exists because a device run is the
  only way to know the app works on a device, and a test that skips itself
  reports nothing about that.

## The ratchet, and why a one-sided floor was not enough

A floor that only fails on a drop has one failure mode of its own: it can
lag. The floor was recorded at five classes and nineteen tests while the suite
had grown to six classes and twenty-one tests — a delivery test was added, and
the floor stayed where it was. Nothing failed. The gate compared 21 against 19
and found no drop, so the file that describes the tier went on describing a tier
that no longer exists. A floor that understates reality is not a weak floor; it
is a floor that has stopped asking its question, and it stays that way for as
long as nobody looks at the diff.

So the floor ratchets in **both** directions:

- a run that delivers **fewer** than the floor records is a finding — coverage
  was lost;
- a run that delivers **more** than the floor records is a finding — the floor
  is behind the suite and describes a tier that no longer exists.

The second is not the JVM floor's rule, deliberately. There, the floor is the
*smallest* count any legitimate run produces and the selection varies with the
tag filter, so a fuller run is expected and a rise is not a defect (ADR
`2026-10-04-test-execution-integrity`, REQ-1). The instrumented tier has one
configuration: one emulator run over the whole suite. A full run of it delivers
the same count every time, so a count that is *below* what a full run delivers
can only mean the record is stale — which is the state the tier was in when the
delivery test was added.

## Raising the floor is deliberate

A floor that regenerates itself stops being a floor, so the gate never rewrites
the file on its own. The write is an explicit act:

    python3 scripts/check-instrumented-test-floor.py --update-floor

which rewrites the generated block from the run on disk and behaves like
`scripts/check-test-runs.py --update-baseline`, because it answers the same
question the same way:

- a **drop** is refused by default — a drop means the tier lost coverage, and
  regenerating the floor would convert a caught defect into an accepted one.
  `--allow-drop` is the way to say you meant it;
- a **rise** is written, but it warns: confirm the report is a full run and not
  a filtered one, because a floor recorded from a narrower run is a floor no
  other run can satisfy;
- a run with **skipped** tests is refused outright — it is not a legitimate
  source for a floor;
- the prose around the generated block is never touched, so the reasoning stays
  next to the number it explains.

## Where it runs

Not in the static gate list: this gate reads the report of the emulator run, so
it runs where the report is — after `connectedDebugAndroidTest`, in
`.github/workflows/android-device-tests.yml`. A gate that needs a build artifact
cannot run in the job that has no build.

Usage: python3 scripts/check-instrumented-test-floor.py [--report-dir DIR]
                                                      [--floor FILE]
                                                      [--json] [--quiet]
                                                      [--update-floor]
                                                      [--allow-drop]

Exit codes:
  0 — the run met the floor, or the floor was rewritten
  1 — findings, or a refused rewrite
  2 — the floor file could not be read or written
"""
from __future__ import annotations

import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

import click

# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
FLOOR_FILE = ROOT / "config" / "docs" / "instrumented-test-floor.txt"
REPORT_DIR = ROOT / "androidApp" / "build" / "outputs" / "androidTest-results" / "connected"

FLOOR_PATTERN = re.compile(r"^(?P<name>[a-z][a-z0-9-]*)\s+(?P<value>\d+)$")

#: The bounds that are floors. `skipped` is deliberately not among them: it is a
#: ceiling, and a ceiling that grew with the run would be a ceiling that stopped
#: being a ceiling.
FLOORS = ("classes", "tests")

#: `--update-floor` rewrites only what is between these two lines, so the
#: hand-written reasoning in the file survives a regeneration. Same convention
#: as `scripts/check-test-runs.py` and `Maestro/TAGS.md`: a generated region
#: inside a hand-maintained file is the only structure that can hold both.
GENERATED_BEGIN = "# GENERATED:BEGIN — rewritten by --update-floor; do not hand-edit"
GENERATED_END = "# GENERATED:END"


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
    """The findings: a run below the floor, a floor behind the run, or a skip."""
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

    for name in FLOORS:
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
        elif measured[name] > floor[name]:
            findings.append(
                {
                    "kind": f"{name} above the floor",
                    "detail": (
                        f"the run reported {measured[name]} {name} and the recorded floor "
                        f"is {floor[name]} — the floor is behind the suite, so it "
                        f"describes a tier that no longer exists. A floor that lags "
                        f"behind what the tier delivers stops being a floor. Raise it "
                        f"deliberately: python3 scripts/check-instrumented-test-floor.py "
                        f"--update-floor"
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


def update_errors(
    recorded: dict[str, int],
    measured: dict,
    report_dir: pathlib.Path,
    allow_drop: bool,
) -> list[str]:
    """Why the floor may not be rewritten from this run. Empty means it may.

    Kept apart from the writing so the refusal reasons can be tested without a
    file on disk, and so the refusal is a decision rather than a side effect of
    a write that half-happened.
    """
    if not report_files(report_dir):
        return [
            f"{report_dir} holds no JUnit XML, so there is nothing to measure and "
            "nothing to record: a floor written from a run that did not happen is a "
            "floor no run can satisfy"
        ]
    if measured["skipped"] > 0:
        return [
            f"the run skipped {measured['skipped']} test(s) "
            f"({', '.join(measured['skipped_tests'])}), and a run that skipped tests "
            "is not a legitimate source for a floor"
        ]
    drops = [name for name in FLOORS if measured[name] < recorded[name]]
    if drops and not allow_drop:
        return [
            f"the run delivered {measured[name]} {name} and the floor records "
            f"{recorded[name]} — a drop means the tier lost coverage, and "
            "regenerating the floor would convert a caught defect into an accepted "
            "one. Re-run the whole suite, or pass --allow-drop if you really did "
            "delete tests."
            for name in drops
        ]
    return []


def _trim_blank_edges(lines: list[str]) -> list[str]:
    start = 0
    while start < len(lines) and not lines[start].strip():
        start += 1
    end = len(lines)
    while end > start and not lines[end - 1].strip():
        end -= 1
    return lines[start:end]


def write_floor(path: pathlib.Path, floor: dict[str, int]) -> None:
    """Rewrite the generated block, leaving the prose around it untouched.

    The `skipped` ceiling is carried over rather than measured: it is a policy
    value, not an observation, and a writer that measured it would raise the
    ceiling every time a run skipped something — which is the one thing the
    ceiling exists to prevent.
    """
    text = path.read_text(encoding="utf-8")
    block = [
        GENERATED_BEGIN,
        f"classes {floor['classes']}",
        f"tests {floor['tests']}",
        f"skipped {floor['skipped']}",
        GENERATED_END,
    ]
    lines = text.split("\n")
    try:
        start = next(i for i, line in enumerate(lines) if line.startswith(GENERATED_BEGIN[:16]))
        end = next(i for i, line in enumerate(lines) if line.startswith(GENERATED_END))
    except StopIteration:
        # A file without the markers: everything in it is prose, and the block is
        # appended below it rather than overwriting a line the author wrote.
        content = _trim_blank_edges(lines) + block
    else:
        content = lines[:start] + block + lines[end + 1 :]
    path.write_text("\n".join(content).rstrip("\n") + "\n", encoding="utf-8")


@click.command()
@click.option("--report-dir", default=str(REPORT_DIR), help="Where the instrumented report is")
@click.option("--floor", default=str(FLOOR_FILE), help="The recorded floor file")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--quiet", is_flag=True, help="Print nothing when the gate is clean")
@click.option(
    "--update-floor",
    "update_floor",
    is_flag=True,
    help="rewrite the recorded floor from this run. Never happens on its own: a "
    "floor that regenerates itself stops being a floor.",
)
@click.option(
    "--allow-drop",
    is_flag=True,
    help="permit --update-floor to record a LOWER count than the recorded floor. "
    "Off by default: a drop means the tier lost coverage, which is a defect to "
    "investigate, not a floor to regenerate.",
)
@click.pass_context
def cli(
    ctx: click.Context,
    report_dir: str,
    floor: str,
    emit_json: bool,
    quiet: bool,
    update_floor: bool,
    allow_drop: bool,
) -> None:
    floor_path = pathlib.Path(floor)
    report_path = pathlib.Path(report_dir)
    try:
        recorded = read_floor(floor_path)
        measured = measure(report_path)
    except click.ClickException as exc:
        click.echo(f"instrumented test floor: {exc.format_message()}")
        raise ctx.exit(IO_ERROR)

    if update_floor:
        errors = update_errors(recorded, measured, report_path, allow_drop)
        if errors:
            if emit_json:
                click.echo(
                    json.dumps(
                        {
                            "findings": [
                                {"kind": "floor not rewritten", "detail": error}
                                for error in errors
                            ],
                            "measured": measured,
                            "floor": recorded,
                        },
                        indent=2,
                    )
                )
            else:
                click.echo("instrumented test floor: refusing to rewrite the floor")
                for error in errors:
                    click.echo(f"  - {error}")
            raise ctx.exit(VALIDATION_FAILURE)

        updated = dict(recorded)
        updated["classes"] = measured["classes"]
        updated["tests"] = measured["tests"]
        try:
            write_floor(floor_path, updated)
        except OSError as exc:
            click.echo(f"instrumented test floor: cannot write {floor_path}: {exc}")
            raise ctx.exit(IO_ERROR)

        rises = [name for name in FLOORS if updated[name] > recorded[name]]
        if emit_json:
            click.echo(
                json.dumps(
                    {"findings": [], "measured": measured, "floor": updated}, indent=2
                )
            )
        elif not quiet:
            click.echo(
                f"instrumented test floor: rewritten — classes {recorded['classes']} -> "
                f"{updated['classes']}, tests {recorded['tests']} -> {updated['tests']}"
            )
            if rises:
                click.echo(
                    "WARNING: the floor ROSE. Confirm the report is a full run and not "
                    "a filtered one:\n"
                    "a floor recorded from a narrower run makes every other run look "
                    "like a regression."
                )
        raise ctx.exit(CLEAN)

    findings = scan(report_path, recorded)

    if emit_json:
        click.echo(
            json.dumps(
                {"findings": findings, "measured": measured, "floor": recorded}, indent=2
            )
        )
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
