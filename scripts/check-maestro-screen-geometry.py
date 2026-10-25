#!/usr/bin/env python3
"""
check-maestro-screen-geometry.py — a flow must not name a pixel.

## The defect this gate holds closed

A Maestro flow that swipes to `105, 2000` is making a claim about one display:
that the screen is at least 2000 pixels tall. The CI emulator is 1080×2400, and
the flow is written for it. On a 720×1600 phone the start point is off-screen,
the swipe never happens, and the flow fails on a screen it never claimed to
know about.

Measured on a physical phone (2026-10-28, realme RMX3938, SDK 35,
720×1600 @ 320dpi): the flows that scroll the settings rail by pixel
(`settings/03-cycle-tabs`, `smoke/06-session-anonymous`) never reached the
trailing tabs, and the flows that open the menu sheet and tap its last item
(`smoke/03-05-health-*-drilldown`) tapped the system navigation bar instead of
the item.

A green run of those flows on the emulator was not a statement about the phone.
It was a statement about the emulator, and it looked like a statement about the
app.

## The rule

A flow may not address a point in pixels. Two forms are allowed, and they are
the two that mean the same thing on every display:

- **a percentage** — `start: "50%, 88%"` — the same gesture, scaled;
- **an element** — `swipe: {direction: UP, element: {id: ...}}` — the gesture
  is addressed to the thing that scrolls, so the display is not part of the
  claim at all. This is the stronger of the two, and the repository already
  uses it (`smoke/03-account-tab`, `backup/01-round-trip`).

What is refused is `swipe: {start: 105, 2000}` and `tapOn: {point: "10, 20"}`:
a pair of numbers that only mean something on the screen they were measured on.

Usage: python3 scripts/check-maestro-screen-geometry.py [--json] [maestro-dir]
"""
from __future__ import annotations

import json
import pathlib
import re
import sys
from typing import Dict, List, NamedTuple

import yaml

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
#: The whole Maestro tree: the flows and the helpers they run. A helper is
#: a flow — `settings-account-tab.yaml` swiped the settings rail by pixel,
#: and a gate that only looked at `Maestro/flows` would not have seen it.
DEFAULT_FLOWS_DIR = ROOT / "Maestro"

#: The exceptions registry: the flows that may name a pixel, and why.
EXCEPTIONS_FILE = ROOT / "config" / "docs" / "maestro-pixel-exceptions.txt"

#: A pair of pixels: "105, 2000" — with or without a space.
PIXELS = re.compile(r"^\s*\d+\s*,\s*\d+\s*$")


class Finding(NamedTuple):
    """One command that names a pixel."""

    flow: str
    line: int
    command: str
    reason: str


def _commands(path: pathlib.Path) -> List[dict]:
    """The command list of a Maestro flow file.

    A flow is two YAML documents: a header (appId, name, tags) and the
    commands. A command is either a mapping (`tapOn: {...}`) or a bare string
    (`- hideKeyboard`), and both are commands.
    """
    with path.open(encoding="utf-8") as handle:
        documents = [doc for doc in yaml.safe_load_all(handle) if doc is not None]
    for document in documents:
        if isinstance(document, list):
            return [command for command in document if isinstance(command, (dict, str))]
    return []


def _line_of(path: pathlib.Path, needle: str) -> int:
    """The 1-based line of the first line that holds `needle`."""
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            if needle in line:
                return number
    return 0


def _is_pixels(value) -> bool:
    """Whether a coordinate is a pair of pixels rather than a pair of shares."""
    if not isinstance(value, str):
        return False
    return bool(PIXELS.match(value))


def check_flow(path: pathlib.Path, root: pathlib.Path) -> List[Finding]:
    """The findings of one flow file.

    Args:
        path: the flow file.
        root: the flows directory, for the name in the finding.

    Returns:
        One finding per command that names a pixel.
    """
    findings: List[Finding] = []
    for command in _commands(path):
        if not isinstance(command, dict):
            continue
        swipe = command.get("swipe")
        if isinstance(swipe, dict):
            for key in ("start", "end"):
                value = swipe.get(key)
                if _is_pixels(value):
                    findings.append(
                        Finding(
                            flow=str(path.relative_to(root)),
                            line=_line_of(path, str(value)),
                            command="swipe",
                            reason=(
                                f"`{key}: {value}` is a pair of pixels: the swipe only "
                                f"happens on a display that is at least that tall. Use a "
                                f"share of the screen or the element that scrolls"
                            ),
                        ),
                    )
        tap = command.get("tapOn")
        if isinstance(tap, dict):
            point = tap.get("point")
            if _is_pixels(point):
                findings.append(
                    Finding(
                        flow=str(path.relative_to(root)),
                        line=_line_of(path, str(point)),
                        command="tapOn",
                        reason=(
                            f"`point: {point}` is a pair of pixels: the tap only lands on a "
                            f"display that is laid out exactly like this one. Address the "
                            f"element instead"
                        ),
                    ),
                )
    return findings


def read_exceptions(path: pathlib.Path) -> Dict[str, str]:
    """The exceptions registry: the flow that may name a pixel, and why.

    Args:
        path: the registry file.

    Returns:
        The flow path (relative to `Maestro/`) mapped to its reason.
    """
    exceptions: Dict[str, str] = {}
    if not path.is_file():
        return exceptions
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split(None, 1)
        exceptions[parts[0]] = parts[1].strip() if len(parts) > 1 else ""
    return exceptions


def check_flows(flows_dir: pathlib.Path, exceptions: Dict[str, str] | None = None) -> List[Finding]:
    """The findings of every flow under `flows_dir`.

    A flow that names a pixel is a finding unless the exceptions registry
    names it: the registry is where the reason is written down. A registry
    entry without a reason is a finding, and so is an entry for a flow that
    no longer names a pixel — a stale exception is a claim that is no longer
    true.

    Args:
        flows_dir: the Maestro tree.
        exceptions: the exceptions registry.

    Returns:
        One finding per rule that is broken.
    """
    exceptions = exceptions or {}
    findings: List[Finding] = []
    seen: set[str] = set()
    for path in sorted(flows_dir.rglob("*.yaml")):
        relative = str(path.relative_to(flows_dir))
        found = check_flow(path, flows_dir)
        if found:
            seen.add(relative)
        if found and relative not in exceptions:
            findings.extend(found)
        if found and relative in exceptions and not exceptions[relative]:
            findings.append(
                Finding(
                    flow=relative,
                    line=0,
                    command="exception",
                    reason=(
                        "the exceptions registry lists this flow without a reason: "
                        "\"no reason\" is not a reason"
                    ),
                )
            )
    for relative in sorted(set(exceptions) - seen):
        findings.append(
            Finding(
                flow=relative,
                line=0,
                command="exception",
                reason=(
                    "the exceptions registry lists this flow, but it no longer names "
                    "a pixel: a stale exception hides the day the flow is fixed"
                ),
            )
        )
    return findings


def main(argv: List[str] | None = None) -> int:
    """Run the gate.

    Args:
        argv: the command-line arguments.

    Returns:
        The exit code (0 = clean, 1 = findings, 2 = I/O error).
    """
    import click

    @click.command()
    @click.option("--json", "as_json", is_flag=True, help="Print the findings as JSON.")
    @click.argument("flows_dir", type=click.Path(path_type=pathlib.Path), default=DEFAULT_FLOWS_DIR)
    def cli(as_json: bool, flows_dir: pathlib.Path) -> None:
        """Fails when a flow addresses a point in pixels."""
        if not flows_dir.is_dir():
            click.echo(f"not a directory: {flows_dir}", err=True)
            raise SystemExit(IO_ERROR)
        findings = check_flows(flows_dir, read_exceptions(EXCEPTIONS_FILE))
        if as_json:
            click.echo(json.dumps([f._asdict() for f in findings], indent=2))
        if findings:
            click.echo("A flow that names a pixel is a claim about one display:\n")
            for finding in findings:
                click.echo(f"  - {finding.flow}:{finding.line} — {finding.reason}")
            click.echo(
                "\nA flow that is written for one screen size is green on that screen and\n"
                "meaningless on every other one. A swipe may be a share of the screen\n"
                "(`start: \"50%, 88%\"`) or the element that scrolls\n"
                "(`swipe: {direction: UP, element: {id: ...}}`) — both mean the same\n"
                "gesture on every display.\n\n"
                "A flow that cannot say it any other way may name a pixel, and then it\n"
                "belongs in the exceptions registry with the reason written down:\n"
                f"  {EXCEPTIONS_FILE.relative_to(ROOT)}",
                err=True,
            )
            raise SystemExit(VALIDATION_FAILURE)
        click.echo("No flow names a pixel.")

    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
