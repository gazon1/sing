#!/usr/bin/env python3
"""check-maestro-results.py — measure a Maestro run against a committed floor.

The Maestro run knows how many flows it failed: it says so in its exit status,
and the CI log says so in a line nobody reads. What the repository did not
have was the measurement itself — a number in a file, a grouping of the
failures by cause, and a gate that fails when the debt grows.

This script is that gate. It reads the JUnit XML the runner wrote
(`build/maestro-results/<flow>.xml`) and holds the run to these things:

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
* **Every failing flow is named.** The summary lists the flows that failed, and
  the JSON payload carries them under `failing_flows`. A number nobody can
  take apart is a number nobody can hold the repository to: the floor says
  "30 failed", and the list says which thirty.

The floor says how much the repository owes, and it cannot say what it owes.
A run can fix one cause, introduce another, keep the same number of failing
flows and pass the floor — the debt changed kind and the number did not
notice. So the floor is read together with the cause registry
(`config/docs/maestro-debt.yaml`), which names every cause the repository has
accepted, the GitHub issue that owns it and who owns that issue. The gate adds
four rules the floor alone cannot carry:

* **A cause that is not in the registry is a finding.** A new cause is the
  debt changing kind, and a count of failing flows cannot see that. The
  registry is the list of the debt's shapes, and a shape that is not on the
  list is a debt nobody has agreed to carry.
* **A cause marked `fixed` that comes back is a finding.** That is a
  regression: the one case a floor that only ratchets down would have hidden,
  because the run can be no worse than the floor while undoing a fix.
* **A cause that is in the registry and did not happen is reported.** The debt
  shrank, and a shrinking debt is somebody's argument to make: the summary
  prints the causes that did not happen, with the number of flows they took
  down, so lowering the floor is a visible next step rather than a forgotten
  one.
* **The run happened where the floor was calibrated.** The registry's
  `environment` block records where the floor was measured (kind and API); the
  runner writes where the run happened to `environment.json` next to the
  reports. A run that does not say where it happened cannot be compared with a
  floor that was calibrated somewhere, and a phone's number compared with an
  emulator's floor is how a gate starts lying. A missing or mismatched
  environment is a finding, never a silent pass.

Neither the floor nor the registry is ever rewritten by this script. Lowering
the floor and accepting a new cause are deliberate edits to files, and they
show up in the diff as one — a floor that moves by itself is a floor nobody
decided.

Scope: `full` is every flow in the catalogue (the nightly's four shards
together); `smoke` is the flows that declare the `smoke` tag — the tag, not
the directory, because the tag is what the runner filters on.

Usage:
    python3 scripts/ci/check-maestro-results.py [--scope full|smoke]
        [--results-dir DIR] [--catalogue FILE] [--floor FILE] [--debt FILE]
        [--environment FILE] [--json] [--quiet]

Exit codes:
    0 — every flow of the scope reported, the failures are within the floor,
        and every failure is a cause the registry knows
    1 — a flow did not report, the run failed more flows than the floor
        allows, a cause is new or has come back, or the run happened somewhere
        other than where the floor was calibrated
    2 — the results directory is missing, or the floor, catalogue or registry
        could not be read
"""
from __future__ import annotations

import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

import click

# The registry is YAML, and the repository already reads YAML with PyYAML —
# `scripts/src/pyproject.toml` pins it and `scripts/check-ui-wait-budgets.py`
# reads the Maestro flows with it — so the registry is read with the same
# reader rather than with a hand-written parser that would have to be correct
# about a format the repository already has a reader for.
import yaml

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
DEFAULT_DEBT = ROOT / "config" / "docs" / "maestro-debt.yaml"

#: The name the runner gives the file that says where the run happened.
ENVIRONMENT_FILENAME = "environment.json"

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

#: The registry status that means "this cause was fixed; coming back is a
#: regression". Every other status is debt the repository has accepted.
STATUS_FIXED = "fixed"


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


def cause_key(message: str) -> str:
    """The registry key of a failure message.

    The registry is keyed by the message with only the run-specific numbers
    replaced by `<n>`. The element id and the expected text stay: the id is
    what says *which* cause it is, and the coarse group key
    (`normalise_failure`) cannot tell two causes apart when they share a
    shape. Two runs that fail on the same element produce the same key, and
    the key is the same string the registry holds.

    Only the first line of the message goes into the key. A failure that
    carries a stack trace would otherwise put the trace in the key, and the
    trace moves with the code: the same cause would be a *new* cause in every
    run, and the registry could never learn it.
    """
    stripped = message.strip()
    first = stripped.splitlines()[0] if stripped else ""
    text = " ".join(first.split())
    return _NUMBER.sub("<n>", text)


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


@dataclass(frozen=True)
class Cause:
    """One cause the repository has accepted, as the registry records it."""

    key: str
    issue: int | None
    owner: str
    status: str
    flows: int
    note: str = ""

    @property
    def fixed(self) -> bool:
        """A cause that was fixed; seeing it again is a regression."""
        return self.status == STATUS_FIXED


@dataclass(frozen=True)
class Registry:
    """The debt the repository has accepted, one cause at a time."""

    path: pathlib.Path
    environment: dict[str, object]
    causes: dict[str, Cause]


@dataclass(frozen=True)
class RunEnvironment:
    """Where a run happened, as the runner recorded it."""

    kind: str
    api: int | None
    device: str
    serial: str
    apk: str
    raw: dict[str, object]


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
    #: cause key -> the flows that failed on it
    causes: dict[str, list[str]] = field(default_factory=dict)
    #: the causes that happened and the registry does not know
    unknown_causes: list[str] = field(default_factory=list)
    #: the causes that happened and the registry marks as fixed
    regressed_causes: list[str] = field(default_factory=list)
    #: the registry's causes that did not happen
    absent_causes: list[Cause] = field(default_factory=list)
    #: where the run happened, if it said
    environment: RunEnvironment | None = None
    #: whether that is where the floor was calibrated
    environment_matches: bool | None = None

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


def read_registry(path: pathlib.Path) -> Registry:
    """The causes the repository has accepted, and where the floor was calibrated.

    The file is data, like the floor: it is read, never written. A cause that
    is not in it fails the gate, so the registry can only grow by somebody
    deciding that a new kind of debt is acceptable — and it can lose a cause
    by that same decision, which is how a fixed cause is recorded.
    """
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"{path}: the registry is not a mapping")
    environment = data.get("environment") or {}
    if not isinstance(environment, dict):
        raise ValueError(f"{path}: the environment block is not a mapping")

    causes: dict[str, Cause] = {}
    for entry in data.get("causes") or []:
        if not isinstance(entry, dict):
            raise ValueError(f"{path}: a cause is not a mapping")
        key = str(entry.get("key") or "").strip()
        if not key:
            raise ValueError(f"{path}: a cause has no key")
        issue = entry.get("issue")
        flows = entry.get("flows")
        causes[key] = Cause(
            key=key,
            issue=int(issue) if isinstance(issue, int) else None,
            owner=str(entry.get("owner") or "unclassified"),
            status=str(entry.get("status") or "accepted"),
            flows=int(flows) if isinstance(flows, int) else 0,
            note=str(entry.get("note") or ""),
        )
    return Registry(path=path, environment=dict(environment), causes=causes)


def read_environment(path: pathlib.Path) -> tuple[RunEnvironment | None, list[Finding]]:
    """Where the run happened, and the findings if it did not say.

    The file is the runner's, not the gate's: it describes the measurement, so
    a file that is missing or unreadable is a finding about the measurement
    rather than an error in the gate's own inputs. A run that does not say
    where it happened cannot be compared with a floor that was calibrated
    somewhere.
    """
    if not path.is_file():
        return None, [
            Finding(
                "environment missing",
                f"{path} is missing — the run did not say where it happened, and a "
                f"measurement that does not say where it ran cannot be compared with "
                f"a floor that was calibrated somewhere",
            )
        ]
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return None, [
            Finding(
                "environment unreadable",
                f"{path} could not be read ({exc}) — the run did not say where it "
                f"happened, and a measurement that does not say where it ran cannot "
                f"be compared with a floor that was calibrated somewhere",
            )
        ]
    if not isinstance(raw, dict):
        return None, [
            Finding(
                "environment unreadable",
                f"{path} does not describe an environment — the run did not say where "
                f"it happened, and a measurement that does not say where it ran "
                f"cannot be compared with a floor that was calibrated somewhere",
            )
        ]
    api = raw.get("api")
    return (
        RunEnvironment(
            kind=str(raw.get("kind") or ""),
            api=int(api) if isinstance(api, int) else None,
            device=str(raw.get("device") or ""),
            serial=str(raw.get("serial") or ""),
            apk=str(raw.get("apk") or ""),
            raw=dict(raw),
        ),
        [],
    )


def compare_environment(
    registry: Registry, run: RunEnvironment | None
) -> list[Finding]:
    """Whether the run happened where the floor was calibrated.

    Only `kind` and `api` are compared: the device name is a property of the
    machine the run happened on, and two emulators of the same kind and API
    measure the same thing. The kind and the API are the properties that make
    the numbers comparable at all — a phone's failures and an emulator's
    failures are two different quantities, and calling them the same one is
    how a gate starts lying.
    """
    calibrated = registry.environment or {}
    kind = calibrated.get("kind")
    api = calibrated.get("api")
    if kind is None or api is None:
        return [
            Finding(
                "environment unknown",
                f"{registry.path} does not say where the floor was calibrated, so "
                f"this run cannot be compared with it — a floor without an "
                f"environment is a number that means nothing on its own",
            )
        ]
    if run is None:
        # `read_environment` already said where the run failed to say.
        return []

    findings: list[Finding] = []
    if run.kind != kind:
        findings.append(
            Finding(
                "environment mismatch",
                f"the run happened on a {run.kind or 'device of an unnamed kind'}, "
                f"but the floor was calibrated on a {kind} — a number measured on "
                f"one and a number measured on the other are two different "
                f"quantities, and comparing them is how a gate starts lying",
            )
        )
    if run.api != api:
        findings.append(
            Finding(
                "environment mismatch",
                f"the run happened on API {run.api if run.api is not None else '?'}, "
                f"but the floor was calibrated on API {api} — a number measured on "
                f"one and a number measured on the other are two different "
                f"quantities, and comparing them is how a gate starts lying",
            )
        )
    return findings


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
    registry: Registry,
) -> Measurement:
    """Measure one run against the floor and the cause registry."""
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
            measurement.causes.setdefault(cause_key(message), []).append(flow)

    if measurement.failed_count > floor:
        measurement.findings.append(
            Finding(
                "over the floor",
                f"the run failed {measurement.failed_count} flow(s) and the floor for "
                f"scope '{scope}' allows {floor}; the debt grew",
            )
        )

    measurement.unknown_causes, measurement.regressed_causes = judge_causes(
        measurement, registry
    )
    measurement.absent_causes = [
        cause
        for key, cause in sorted(registry.causes.items())
        if key not in measurement.causes and not cause.fixed
    ]
    return measurement


def judge_causes(
    measurement: Measurement, registry: Registry
) -> tuple[list[str], list[str]]:
    """The causes the run produced that the registry does not accept.

    Two of them are findings and neither of them is a count:

    * a cause the registry has never heard of — the debt changed kind, and the
      number of failing flows cannot see a change of kind;
    * a cause the registry marks as fixed — a regression, which a floor that
      only ratchets down would hide, because the run can be no worse than the
      floor while undoing a fix.
    """
    unknown: list[str] = []
    regressed: list[str] = []
    for key, flows in sorted(measurement.causes.items()):
        cause = registry.causes.get(key)
        if cause is None:
            unknown.append(key)
            measurement.findings.append(
                Finding(
                    "cause not in the registry",
                    f"'{key}' — {len(flows)} flow(s) failed on a cause the "
                    f"registry at {registry.path} does not list; a new cause is "
                    f"the debt changing kind, and a count of failing flows cannot "
                    f"see that",
                )
            )
        elif cause.fixed:
            regressed.append(key)
            measurement.findings.append(
                Finding(
                    "fixed cause came back",
                    f"'{key}' — {len(flows)} flow(s) failed on a cause the "
                    f"registry marks as fixed"
                    + (f" (issue #{cause.issue})" if cause.issue else "")
                    + "; that is a regression, not new debt",
                )
            )
    return unknown, regressed


# ── the report ───────────────────────────────────────────────────────────────


def _cause_line(key: str, flows: int, cause: Cause | None) -> str:
    """One row of the cause table: the cause, its weight, and who owns it."""
    issue = f"#{cause.issue}" if cause is not None and cause.issue else "-"
    owner = cause.owner if cause is not None else "unknown"
    status = cause.status if cause is not None else "not in the registry"
    return f"  {flows:>3}  {key}  {issue}  {owner}  {status}"


def _environment_line(measurement: Measurement, registry: Registry) -> str:
    """One line: where the run happened, and where the floor was calibrated."""
    calibrated = registry.environment or {}
    kind = calibrated.get("kind", "?")
    api = calibrated.get("api", "?")
    run = measurement.environment
    if run is None:
        return (
            f"environment: the run did not say where it happened — the floor was "
            f"calibrated on {kind}, API {api}"
        )
    verdict = "matches" if measurement.environment_matches else "does not match"
    return (
        f"environment: run kind={run.kind or '?'} api={run.api if run.api is not None else '?'}"
        f" device={run.device or '?'} — {verdict} the floor's calibration "
        f"(kind={kind}, api={api})"
    )


def format_measurement(measurement: Measurement, registry: Registry) -> str:
    """The table a human reads: the debt, what it is made of, and who owns it."""
    lines: list[str] = []
    lines.append(
        f"maestro results (scope={measurement.scope}): "
        f"{len(measurement.reported)}/{len(measurement.expected)} flow(s) reported, "
        f"{measurement.failed_count} failed, floor {measurement.floor}"
    )
    # The number is only worth what it can be traced to: every failing flow is
    # named, so "30 failed" is 30 flows a reader can go and look at, not an
    # aggregate nobody can take apart.
    lines.append(f"failing flows ({measurement.failed_count}):")
    if measurement.failures:
        for flow, _ in sorted(measurement.failures):
            lines.append(f"  {flow}")
    else:
        lines.append("  none — every flow of the scope passed")
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

    # The point of the change: the debt is a list of named things with owners,
    # not a number.
    lines.append(f"causes ({len(measurement.causes)}):")
    if measurement.causes:
        for key, flows in sorted(
            measurement.causes.items(), key=lambda item: (-len(item[1]), item[0])
        ):
            lines.append(_cause_line(key, len(flows), registry.causes.get(key)))
    else:
        lines.append("  none — every flow of the scope passed")

    if measurement.absent_causes:
        freed = sum(cause.flows for cause in measurement.absent_causes)
        lines.append(
            f"registry causes that did not happen ({len(measurement.absent_causes)}, "
            f"{freed} flow(s) when the floor was calibrated) — the debt shrank, and "
            f"the floor is now higher than the debt:"
        )
        for cause in measurement.absent_causes:
            lines.append(_cause_line(cause.key, cause.flows, cause))

    lines.append(_environment_line(measurement, registry))
    return "\n".join(lines)


def build_payload(
    measurement: Measurement, registry: Registry
) -> dict[str, object]:
    """The JSON document: the measurement, the causes, and the cross-checks."""
    causes: list[dict[str, object]] = []
    for key, flows in sorted(
        measurement.causes.items(), key=lambda item: (-len(item[1]), item[0])
    ):
        cause = registry.causes.get(key)
        causes.append(
            {
                "key": key,
                "flows": len(flows),
                "issue": cause.issue if cause is not None else None,
                "owner": cause.owner if cause is not None else None,
                "status": cause.status if cause is not None else None,
                "in_registry": cause is not None,
            }
        )

    calibrated = registry.environment or {}
    run = measurement.environment
    return {
        "scope": measurement.scope,
        "floor": measurement.floor,
        "expected": len(measurement.expected),
        "reported": len(measurement.reported),
        "failed": measurement.failed_count,
        "failing_flows": sorted(flow for flow, _ in measurement.failures),
        "groups": {key: len(flows) for key, flows in measurement.groups.items()},
        "causes": causes,
        "registry": {
            "path": str(registry.path),
            "causes": len(registry.causes),
            "unknown_causes": list(measurement.unknown_causes),
            "regressed_causes": list(measurement.regressed_causes),
            "did_not_happen": [
                {
                    "key": cause.key,
                    "issue": cause.issue,
                    "owner": cause.owner,
                    "status": cause.status,
                    "flows": cause.flows,
                }
                for cause in measurement.absent_causes
            ],
        },
        "environment": {
            "calibrated": {
                "kind": calibrated.get("kind"),
                "api": calibrated.get("api"),
                "device": calibrated.get("device"),
            },
            "run": (
                {
                    "kind": run.kind,
                    "api": run.api,
                    "device": run.device,
                    "serial": run.serial,
                    "apk": run.apk,
                }
                if run is not None
                else None
            ),
            "matches": measurement.environment_matches,
        },
        "findings": [
            {"kind": f.kind, "detail": f.detail} for f in measurement.findings
        ],
    }


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
@click.option(
    "--debt",
    "debt_file",
    type=click.Path(path_type=pathlib.Path),
    default=DEFAULT_DEBT,
    show_default=True,
    help="The registry of the causes the repository has accepted, one cause at a time.",
)
@click.option(
    "--environment",
    "environment_file",
    type=click.Path(path_type=pathlib.Path),
    default=None,
    help=(
        "The file the runner wrote the run's environment into. "
        f"Defaults to <results-dir>/{ENVIRONMENT_FILENAME}."
    ),
)
@click.option("--json", "as_json", is_flag=True, help="Print the measurement as JSON.")
@click.option("--quiet", is_flag=True, help="Print findings only, without the table.")
def cli(
    ctx: click.Context,
    scope: str,
    results_dir: pathlib.Path,
    catalogue: pathlib.Path,
    floor_file: pathlib.Path,
    debt_file: pathlib.Path,
    environment_file: pathlib.Path | None,
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

    try:
        registry = read_registry(debt_file)
    except (OSError, ValueError, yaml.YAMLError) as exc:
        click.echo(f"cannot read the cause registry {debt_file}: {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    reports, report_findings = read_reports(results_dir)
    measurement = measure(scope, catalogue_entries, reports, floors[scope], ROOT, registry)
    measurement.findings = report_findings + measurement.findings

    environment_path = environment_file or (results_dir / ENVIRONMENT_FILENAME)
    run_environment, environment_findings = read_environment(environment_path)
    environment_findings += compare_environment(registry, run_environment)
    measurement.environment = run_environment
    measurement.findings += environment_findings
    # The environment matches when the run said where it happened and that
    # place is the one the floor was calibrated in.
    measurement.environment_matches = (
        run_environment is not None and not environment_findings
    )

    if as_json:
        click.echo(
            json.dumps(
                build_payload(measurement, registry), indent=2, ensure_ascii=False
            )
        )
    elif not quiet:
        click.echo(format_measurement(measurement, registry))

    for finding in measurement.findings:
        click.echo(f"{finding.kind}: {finding.detail}", err=True)

    raise ctx.exit(CLEAN if not measurement.findings else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
