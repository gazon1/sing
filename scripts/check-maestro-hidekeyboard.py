#!/usr/bin/env python3
"""check-maestro-hidekeyboard.py — hideKeyboard must follow an input, not precede one.

In this app, `hideKeyboard` triggers autosave-on-dismiss and closes the
editor. Placing it before `inputText` saves an empty draft and navigates away
— the flow passes, the user's words are gone.

So this gate reads every Maestro flow and finds the `hideKeyboard` that has no
`inputText` before it: the nearest preceding non-comment, non-blank line must
mention `inputText`.

Usage: python3 scripts/check-maestro-hidekeyboard.py [--flows DIR] [--json] [--check]
"""
from __future__ import annotations

import json
import pathlib
import sys

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
FLOWS = ROOT / "Maestro" / "flows"

#: How far back a flow may look for the input that the dismissal follows.
LOOKBEHIND = 20


def flow_files(flows: pathlib.Path) -> list[pathlib.Path]:
    """The flow files under `flows`, in file order."""
    if not flows.is_dir():
        return []
    return sorted(path for path in flows.rglob("*.yaml") if path.is_file())


def _has_preceding_input(lines: list[str], index: int) -> bool:
    """Whether the line before `index` mentions an `inputText`."""
    earliest = max(index - LOOKBEHIND, 0)
    for previous in range(index - 1, earliest - 1, -1):
        stripped = lines[previous].strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "inputText" in stripped:
            return True
    return False


def _label(path: pathlib.Path) -> str:
    """The flow's name in a finding — repository-relative when it is one."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return path.name


def scan(flows: pathlib.Path) -> list[dict]:
    """The `hideKeyboard` calls that dismiss without an input, as findings."""
    findings: list[dict] = []
    for path in flow_files(flows):
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if "hideKeyboard" not in line or line.strip().startswith("#"):
                continue
            if _has_preceding_input(lines, index):
                continue
            findings.append(
                {
                    "flow": _label(path),
                    "line": index + 1,
                    "detail": (
                        "hideKeyboard without a preceding inputText — the dismissal "
                        "saves an empty draft and closes the editor"
                    ),
                }
            )
    return findings


@click.command()
@click.option(
    "--flows",
    "flows_dir",
    type=click.Path(path_type=pathlib.Path),
    default=FLOWS,
    help="The directory of Maestro flows to read (default: Maestro/flows).",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--check", is_flag=True, help="Dry-run: print the result without exiting non-zero")
@click.pass_context
def cli(ctx: click.Context, flows_dir: pathlib.Path, emit_json: bool, check: bool) -> None:
    try:
        findings = scan(flows_dir)
    except OSError as exc:
        click.echo(f"maestro hideKeyboard: cannot read the flows: {exc}")
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(json.dumps({"findings": findings}, indent=2))
    elif not findings:
        click.echo(f"maestro hideKeyboard: OK ({len(flow_files(flows_dir))} flows checked)")
    else:
        click.echo(f"maestro hideKeyboard: {len(findings)} finding(s)")
        for finding in findings:
            click.echo(f"  {finding['flow']}:{finding['line']} — {finding['detail']}")
    if check:
        raise ctx.exit(CLEAN)
    raise ctx.exit(VALIDATION_FAILURE if findings else CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
