#!/usr/bin/env python3
"""check-maestro-results.py — measure a Maestro run against a committed floor.

The Maestro run knows how many flows it failed: it says so in its exit status,
and the CI log says so in a line nobody reads. What the repository did not
have was the measurement itself — a number in a file, a grouping of the
failures by cause, and a gate that fails when the debt grows.

This script is that gate. It reads the JUnit XML the runner wrote
(`build/maestro-results/<flow>.xml`) and holds the run to four things:

* **Every flow of the scope has a report.** A flow that did not report is a
  finding, not a skip. A flow that never ran is a flow that silently stopped
  being covered, and "no report" is the one outcome a run must not have.
* **The reports belong to the scope.** A report for a flow the scope did not
  run is a leftover, and a leftover makes the measurement a measurement of the
  wrong run.
* **The number of failing flows is within the floor.** The floor is a number
  in `config/docs/maestro-failure-floor.txt` — data, not a constant — and it
  ratchets in one direction only: a run that fails more flows than the floor
  allows fails this gate; a run that fails fewer passes, so the debt shrinks
  without anyone having to touch the file.
* **The failures are grouped by cause.** The table says what the debt is made
  of, not only how much of it there is.

The floor is never rewritten by this script. Lowering it is a deliberate edit
to the file, and it shows up in the diff as one — a floor that moves by itself
is a floor nobody decided.

Scope: `full` is every flow in the catalogue (the nightly's four shards
together); `smoke` is the flows that declare the `smoke` tag — the tag, not
the directory, because the tag is what the runner filters on.

Usage:
    python3 scripts/ci/check-maestro-results.py [--scope full|smoke]
        [--results-dir DIR] [--catalogue FILE] [--floor FILE] [--json] [--quiet]

Exit codes:
    0 — every flow of the scope reported, and the failures are within the floor
    1 — a flow did not report, or the run failed more flows than the floor allows
    2 — the results directory is missing, or the floor could not be read
"""
from __future__ import annotations

import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

import click

# The gate is run from a workflow step and from a checkout that may not have
# the tooling installed, so the repository's own packages are put on the path
# rather than assumed to be there.
_SRC = pathlib.Path(__file__).resolve().parent.parent.parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from scanners.maestro_flow import flow_tags_text  # noqa: E402
from sgate.commands.base import run  # noqa: E402

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
DEFAULT_RESULTS = ROOT / "build" / "maestro-results"
DEFAULT_CATALOGUE = ROOT / "config" / "docs" / "maestro-flows.txt"
DEFAULT_FLOOR = ROOT / "config" / "docs" / "maestro-failure-floor.txt"

SCOPE_FULL = "full"
SCOPE_SMOKE = "smoke"
SCOPES = (SCOPE_FULL, SCOPE_SMOKE)

#: The tag the runner filters the smoke suite on. The directory
#: `Maestro/flows/smoke/` is not the selection — 22 of the 29 smoke flows
#: live elsewhere — so a scope built from the directory would measure a
#: different suite than the one that ran.
SMOKE_TAG = "smoke"

#: How many distinct messages a group prints before it says "and N more".
GROUP_DETAIL_LIMIT = 8


# ── the failure message, and the cause behind it ─────────────────────────────

#: `id: <value>` — the element the flow expected to find.
_ID_VALUE = re.compile(r"(?<=id: )[A-Za-z0-9_.\-]+")
#: `"<text>"` — the text the flow expected to read.
_QUOTED = re.compile(r"\"[^\"]*\"")
#: `Text matching regex: <pattern>` — a regex the flow expected to match.
_REGEX_TAIL = re.compile(r"(Text matching regex: ).*")
#: A run of three or more digits — a timestamp, a duration, an id with a
#: number in it. Two digits are left alone: "01" in a title is content.
_NUMBER = re.compile(r"\d{3,}")


def normalise_failure(message: str) -> str:
    """The cause behind a failure message, with the run-specific parts removed.

    A failure message carries three kinds of noise: the id of the element
    that was expected, the text it was expected to have, and the numbers a
    run stamps into both. Left in, every flow that fails on a different
    element is a group of its own, and the table says how much debt there is
    without saying what it is made of.

    The normalisation is deliberately coarse — the id becomes `<id>` — so the
    groups are causes rather than instances. The concrete messages are printed
    under each group, so the ids that make it up are still visible.
    """
    text = " ".join(message.split())
    text = _ID_VALUE.sub("<id>", text)
    text = _QUOTED.sub("<text>", text)
    text = _REGEX_TAIL.sub(r"\1<text>", text)
    text = _NUMBER.sub("<n>", text)
    return text


# ── the data the gate reads ──────────────────────────────────────────────────


@dataclass(frozen=True)
class Report:
    """One JUnit XML file the runner wrote for one flow."""

    name: str
    flow: str | None
    messages: tuple[str, ...]

    @property
    def failed(self) -> bool:
        return bool(self.messages)


@dataclass(frozen=True)
class Finding:
    """One thing the measurement refuses to accept."""

    kind: str
    detail: str


@dataclass
class Measurement:
    """Everything the gate knows about one run."""

    scope: str
    floor: int
    expected: list[str] = field(default_factory=list)
    reported: list[str] = field(default_factory=list)
    failures: list[tuple[str, str]] = field(default_factory=list)
    findings: list[Finding] = field(default_factory=list)
    #: group key -> the (flow, message) pairs that landed in it
    groups: dict[str, list[tuple[str, str]]] = field(default_factory=dict)

    @property
    def failed_count(self) -> int:
        return len(self.failures)


def read_catalogue(path: pathlib.Path) -> list[str]:
    """The flow paths the catalogue claims, in file order, comments removed."""
    entries: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        entries.append(stripped)
    return entries


def read_floor(path: pathlib.Path) -> dict[str, int]:
    """The allowed number of failing flows, per scope.

    The file is `<scope> <number>` per line. It is read, never written: the
    floor is a decision somebody made, and a script that lowers it would make
    the decision nobody's.
    """
    floors: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) != 2:
            raise ValueError(
                f"{path}: '{stripped}' is not '<scope> <number>'"
            )
        scope, number = parts
        try:
            floors[scope] = int(number)
        except ValueError as exc:
            raise ValueError(
                f"{path}: '{number}' is not a number of flows"
            ) from exc
    return floors


def scope_flows(
    scope: str, catalogue: list[str], root: pathlib.Path
) -> tuple[list[str], list[Finding]]:
    """The flows of the catalogue that the scope runs.

    `full` is the whole catalogue. `smoke` is the flows that declare the
    `smoke` tag — the same selection the runner makes, read by the same
    reader, because a scope built by a different rule measures a different
    suite than the one that ran.
    """
    if scope == SCOPE_FULL:
        return list(catalogue), []

    findings: list[Finding] = []
    selected: list[str] = []
    for flow in catalogue:
        path = root / flow
        if not path.is_file():
            findings.append(
                Finding(
                    "flow file missing",
                    f"{flow} — the catalogue names a flow that is not in the tree",
                )
            )
            continue
        if SMOKE_TAG in flow_tags_text(path.read_text(encoding="utf-8")):
            selected.append(flow)
    return selected, findings


def read_reports(results_dir: pathlib.Path) -> tuple[list[Report], list[Finding]]:
    """Every JUnit XML in the results directory, and the ones that do not read."""
    reports: list[Report] = []
    findings: list[Finding] = []
    for xml in sorted(results_dir.glob("*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError as exc:
            findings.append(
                Finding("unreadable report", f"{xml.name}: the report is not valid XML ({exc})")
            )
            continue
        flow: str | None = None
        messages: list[str] = []
        for case in root.iter("testcase"):
            if flow is None:
                flow = case.get("file")
            failure = case.find("failure")
            text = (failure.text or "").strip() if failure is not None else ""
            if text:
                messages.append(text)
                continue
            status = (case.get("status") or "").upper()
            if status in {"ERROR", "FAILURE", "FAILED"}:
                messages.append(
                    f"the flow ended with status {status} and carried no failure message"
                )
        reports.append(Report(name=xml.name, flow=flow, messages=tuple(messages)))
    return reports, findings


def assign_flows(
    reports: list[Report], expected: list[str]
) -> tuple[dict[str, Report], list[Finding]]:
    """Match every report to the flow it is about.

    The report says which flow it is: Maestro writes the flow's path into the
    testcase's `file` attribute. The file name is the fallback — it is the
    flow's stem — and a stem two flows share is a finding, because the second
    flow's report would overwrite the first's and the run would look complete
    while covering less than it says.
    """
    findings: list[Finding] = []
    by_stem: dict[str, list[str]] = {}
    for flow in expected:
        by_stem.setdefault(pathlib.PurePosixPath(flow).stem, []).append(flow)
    for stem, flows in sorted(by_stem.items()):
        if len(flows) > 1:
            findings.append(
                Finding(
                    "two flows, one report",
                    f"{stem}.xml — {', '.join(flows)} share a report file name, so one "
                    f"flow's report overwrites the other's",
                )
            )

    expected_set = set(expected)
    assigned: dict[str, Report] = {}
    for report in reports:
        flow = report.flow
        if flow is None:
            candidates = by_stem.get(pathlib.PurePosixPath(report.name).stem, [])
            flow = candidates[0] if len(candidates) == 1 else None
        if flow is None or flow not in expected_set:
            findings.append(
                Finding(
                    "report outside the scope",
                    f"{report.name} — it reports "
                    f"{report.flow or 'a flow the scope did not run'}, which is not "
                    f"part of the scope this run measured",
                )
            )
            continue
        if flow in assigned:
            findings.append(
                Finding(
                    "two reports for one flow",
                    f"{report.name} and {assigned[flow].name} both report {flow}",
                )
            )
            continue
        assigned[flow] = report
    return assigned, findings


def measure(
    scope: str,
    catalogue: list[str],
    reports: list[Report],
    floor: int,
    root: pathlib.Path,
) -> Measurement:
    """Measure one run against the floor."""
    expected, findings = scope_flows(scope, catalogue, root)
    assigned, assign_findings = assign_flows(reports, expected)
    findings += assign_findings

    measurement = Measurement(scope=scope, floor=floor, expected=expected)
    measurement.findings += findings

    for flow in expected:
        report = assigned.get(flow)
        if report is None:
            measurement.findings.append(
                Finding(
                    "flow did not report",
                    f"{flow} — the run produced no report for it, so it did not run; "
                    f"a flow that does not run is a flow that stopped being covered",
                )
            )
            continue
        measurement.reported.append(flow)
        if report.failed:
            message = report.messages[0]
            measurement.failures.append((flow, message))
            key = normalise_failure(message)
            measurement.groups.setdefault(key, []).append((flow, message))

    if measurement.failed_count > floor:
        measurement.findings.append(
            Finding(
                "over the floor",
                f"the run failed {measurement.failed_count} flow(s) and the floor for "
                f"scope '{scope}' allows {floor}; the debt grew",
            )
        )
    return measurement


# ── the report ───────────────────────────────────────────────────────────────


def format_measurement(measurement: Measurement) -> str:
    """The table a human reads: the debt, and what it is made of."""
    lines: list[str] = []
    lines.append(
        f"maestro results (scope={measurement.scope}): "
        f"{len(measurement.reported)}/{len(measurement.expected)} flow(s) reported, "
        f"{measurement.failed_count} failed, floor {measurement.floor}"
    )
    if measurement.groups:
        lines.append(f"failure groups ({len(measurement.groups)}):")
        for key, entries in sorted(
            measurement.groups.items(), key=lambda item: (-len(item[1]), item[0])
        ):
            lines.append(f"  {len(entries):>3}  {key}")
            counts: dict[str, int] = {}
            for _, message in entries:
                counts[message] = counts.get(message, 0) + 1
            ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
            for index, (message, count) in enumerate(ordered):
                if index >= GROUP_DETAIL_LIMIT:
                    lines.append(
                        f"       ... and {len(ordered) - GROUP_DETAIL_LIMIT} more message(s)"
                    )
                    break
                lines.append(f"       {count} x {message}")
    else:
        lines.append("failure groups (0): none — every flow of the scope passed")
    return "\n".join(lines)


@click.command()
@click.pass_context
@click.option(
    "--scope",
    type=click.Choice(SCOPES),
    default=SCOPE_FULL,
    show_default=True,
    help="Which suite the reports belong to: the whole catalogue, or the smoke subset.",
)
@click.option(
    "--results-dir",
    type=click.Path(path_type=pathlib.Path),
    default=DEFAULT_RESULTS,
    show_default=True,
    help="The directory the runner wrote its JUnit XML into.",
)
@click.option(
    "--catalogue",
    type=click.Path(path_type=pathlib.Path),
    default=DEFAULT_CATALOGUE,
    show_default=True,
    help="The flow catalogue the run is measured against.",
)
@click.option(
    "--floor",
    "floor_file",
    type=click.Path(path_type=pathlib.Path),
    default=DEFAULT_FLOOR,
    show_default=True,
    help="The file holding the allowed number of failing flows, per scope.",
)
@click.option("--json", "as_json", is_flag=True, help="Print the measurement as JSON.")
@click.option("--quiet", is_flag=True, help="Print findings only, without the table.")
def cli(
    ctx: click.Context,
    scope: str,
    results_dir: pathlib.Path,
    catalogue: pathlib.Path,
    floor_file: pathlib.Path,
    as_json: bool,
    quiet: bool,
) -> None:
    """Measure a Maestro run against the committed floor."""
    if not results_dir.is_dir():
        click.echo(
            f"the results directory {results_dir} does not exist — the Maestro run "
            f"wrote no reports, so there is nothing to measure",
            err=True,
        )
        raise ctx.exit(IO_ERROR)

    try:
        catalogue_entries = read_catalogue(catalogue)
    except OSError as exc:
        click.echo(f"cannot read the catalogue {catalogue}: {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    try:
        floors = read_floor(floor_file)
    except (OSError, ValueError) as exc:
        click.echo(f"cannot read the floor {floor_file}: {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    if scope not in floors:
        click.echo(
            f"the floor {floor_file} has no entry for scope '{scope}' — the allowed "
            f"number of failing flows is not known, and a measurement without one "
            f"cannot be judged",
            err=True,
        )
        raise ctx.exit(IO_ERROR)

    reports, report_findings = read_reports(results_dir)
    measurement = measure(scope, catalogue_entries, reports, floors[scope], ROOT)
    measurement.findings = report_findings + measurement.findings

    if as_json:
        payload = {
            "scope": measurement.scope,
            "floor": measurement.floor,
            "expected": len(measurement.expected),
            "reported": len(measurement.reported),
            "failed": measurement.failed_count,
            "groups": {key: len(flows) for key, flows in measurement.groups.items()},
            "findings": [
                {"kind": f.kind, "detail": f.detail} for f in measurement.findings
            ],
        }
        click.echo(json.dumps(payload, indent=2, ensure_ascii=False))
    elif not quiet:
        click.echo(format_measurement(measurement))

    for finding in measurement.findings:
        click.echo(f"{finding.kind}: {finding.detail}", err=True)

    raise ctx.exit(CLEAN if not measurement.findings else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
