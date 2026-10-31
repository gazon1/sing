#!/usr/bin/env python3
"""maestro-flow-timings.py — how long each Maestro flow takes.

## The problem this solves

The shard plan balances the suite by FLOW COUNT. A shard of seven flows that
each finish in five seconds is treated as equal to a shard of seven flows that
each hit the 300-second timeout and are retried twice. The nightly's wall
clock is set by its largest shard, and the number that decides which shard is
largest is a count, not a time.

Maestro already writes the time: every flow's JUnit report carries a `time`
attribute. `scripts/src/traceability/junit_xml.py` reads it and the renderer
then drops it, because a timestamp in a traceability report turns every
regeneration into a diff. That is the right call for the report and the wrong
call for the plan — the time has to be kept SOMEWHERE, and this is that
place.

## What this writes

`config/docs/maestro-flow-timings.txt` — one line per flow: the shortest time
the flow has ever been measured at.

A floor, not a snapshot, for the same reason the failure floor is a floor
(`config/docs/maestro-failure-floor.txt`): the shard estimate is a LOWER
BOUND, and the file moves only when a flow got faster. A file a script
regenerates on every run is a file that changes by itself, and the diff that
shows a suite getting faster has to be somebody's decision.

Nothing rewrites the file except `--record`, and `--record` only ever lowers
a number.

## Usage

    python3 scripts/maestro-flow-timings.py --list      # the flows, slowest first
    python3 scripts/maestro-flow-timings.py --record    # fold a run into the floor
    python3 scripts/maestro-flow-timings.py --json      # machine-readable
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
FLOOR_FILE = ROOT / "config" / "docs" / "maestro-flow-timings.txt"
DEFAULT_RESULTS = ROOT / "build" / "maestro-results"

#: `flow <seconds>` — the flow's path relative to the repository root, and the
#: shortest time it has ever been measured at.
_FLOOR_LINE = re.compile(r"^(?P<flow>\S+)\s+(?P<seconds>\d+(?:\.\d+)?)\s*$")


def _floors() -> dict[str, float]:
    """The recorded floor per flow, from the file."""
    recorded: dict[str, float] = {}
    if not FLOOR_FILE.is_file():
        return recorded
    for line in FLOOR_FILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = _FLOOR_LINE.match(stripped)
        if match:
            recorded[match.group("flow")] = float(match.group("seconds"))
    return recorded


def _measured(results_dir: pathlib.Path) -> dict[str, float]:
    """The shortest time each flow was measured at, from a run's reports."""
    measured: dict[str, float] = {}
    if not results_dir.is_dir():
        return measured
    for xml in sorted(results_dir.glob("**/*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except (ET.ParseError, OSError):
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for case in suite.findall("testcase"):
                flow = case.get("file") or case.get("classname") or ""
                if not flow:
                    continue
                try:
                    elapsed = float(case.get("time") or 0.0)
                except ValueError:
                    continue
                flow = _relative(flow)
                previous = measured.get(flow)
                # The floor is the SHORTEST time: the suite's cost estimate is
                # a lower bound, and a run that was slow once does not make
                # every later plan worse.
                if previous is None or elapsed < previous:
                    measured[flow] = elapsed
    return measured


def _relative(flow: str) -> str:
    """The flow's path, relative to the repository root."""
    text = flow.replace("\\", "/")
    # The report carries the path of the machine that ran the flow, and the
    # repository can live anywhere. `Maestro/` is the part that means the same
    # thing on every machine.
    marker = "Maestro/"
    index = text.find(marker)
    if index >= 0:
        return text[index:]
    return text.lstrip("./")


def _write(floors: dict[str, float]) -> None:
    """Write the floor file, keeping the header and the order stable."""
    header = [
        "# The shortest time each Maestro flow has ever been measured at.",
        "#",
        "# A FLOOR, not a snapshot: the shard-cost estimate is a lower bound, and",
        "# a file a script rewrites on every run is a file that changes by",
        "# itself. Only `--record` writes this file, and it only ever lowers a",
        "# number — a flow that got faster keeps the faster number.",
        "#",
        "# Recorded by `scripts/maestro-flow-timings.py --record` from the JUnit",
        "# reports of a Maestro run (`build/maestro-results/`). The seconds are",
        "# what Maestro wrote in the report's `time` attribute.",
        "#",
        "# Format: one `flow <seconds>` per line. `#` starts a comment.",
        "#",
        "# A flow with no line here has no measured cost: the shard-cost",
        "# comparison counts it as the slowest flow that HAS one, so a flow that",
        "# was never measured cannot make a shard look cheap.",
        "",
    ]
    body = [f"{flow} {seconds:g}" for flow, seconds in sorted(floors.items())]
    FLOOR_FILE.parent.mkdir(parents=True, exist_ok=True)
    FLOOR_FILE.write_text("\n".join(header + body) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--record", action="store_true", help="fold a run into the floor file")
    parser.add_argument("--list", action="store_true", help="the flows, slowest first")
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument(
        "--results",
        default=str(DEFAULT_RESULTS),
        help="the directory with a Maestro run's JUnit reports",
    )
    args = parser.parse_args(argv)

    measured = _measured(pathlib.Path(args.results))
    floors = _floors()

    if args.record:
        for flow, elapsed in measured.items():
            previous = floors.get(flow)
            if previous is None or elapsed < previous:
                floors[flow] = elapsed
        _write(floors)
        print(f"recorded {len(measured)} flow(s); the floor holds {len(floors)}")
        return 0

    merged = dict(floors)
    merged.update(measured)

    if args.json:
        print(json.dumps(merged, indent=2, sort_keys=True))
        return 0

    if not merged:
        print(
            "no Maestro timings yet — run a Maestro suite and then "
            "`python3 scripts/maestro-flow-timings.py --record`"
        )
        return 0

    for flow, seconds in sorted(merged.items(), key=lambda item: -item[1]):
        print(f"{seconds:8.1f}s  {flow}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
