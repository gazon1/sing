#!/usr/bin/env python3
"""check-maestro-hidekeyboard.py — hideKeyboard must follow an input, not precede one.

## The defect this gate holds closed

In this app, `hideKeyboard` triggers autosave-on-dismiss and closes the
editor. Placed *before* `inputText`, it saves an empty draft and navigates
away, and the flow fails on the app's behaviour instead of on its own mistake.

## The rule

Every `hideKeyboard` follows an `inputText`: the keyboard is hidden because
something was typed into it. A `hideKeyboard` with no text input before it is
a finding.

## What this script is, and is not

This is the narrower probe of the pair. The registered gate
`check-maestro-keyboard-commands.py` holds the stricter rule — there is
exactly one `hideKeyboard` per text input, and a second one is a BACK
keypress in disguise. This script answers the narrower question: is there
anything to hide at all.

## How the rule is read

The commands of a flow are read in order, and the keyboard's state is tracked:

- `inputText` — text was typed into a field: the keyboard may be up, and there
  is something to hide;
- `hideKeyboard` — the keyboard is hidden again, and the state resets.

The state is per flow, so a helper flow that types text would make the state
of the calling flow wrong. No helper in `Maestro/helpers` types today, and the
gate says so in its failure message rather than pretending to know.

Usage: python3 scripts/check-maestro-hidekeyboard.py [--json] [--check] [flows-dir]
"""
from __future__ import annotations

import json
import pathlib
import sys
from typing import List, NamedTuple

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
DEFAULT_FLOWS_DIR = ROOT / "Maestro" / "flows"

#: The commands that put text into a field, and therefore may leave the
#: keyboard up.
TEXT_INPUT_COMMANDS = frozenset({"inputText"})


class Finding(NamedTuple):
    """One `hideKeyboard` that had nothing to hide."""

    flow: str
    line: int
    reason: str


def _commands(path: pathlib.Path) -> List[dict]:
    """The command list of a Maestro flow file.

    A flow is two YAML documents: a header (appId, name, tags) and the
    commands. A file that is not a flow at all is an empty command list.

    A command is either a mapping (`tapOn: {...}`) or a bare string
    (`- hideKeyboard`), and both are commands.
    """
    with path.open(encoding="utf-8") as handle:
        documents = [doc for doc in yaml.safe_load_all(handle) if doc is not None]
    for document in documents:
        if isinstance(document, list):
            return [command for command in document if isinstance(command, (dict, str))]
    return []


def _command_name(command) -> str:
    """The name of a command.

    A mapping command is named by its single key; a bare command is its own
    name.
    """
    if isinstance(command, str):
        return command
    for name in command:
        return name
    return ""


def _line_of(path: pathlib.Path, needle: str) -> int:
    """The 1-based line of the first line that holds `needle`."""
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            if needle in line:
                return number
    return 0


def check_flow(path: pathlib.Path, root: pathlib.Path) -> List[Finding]:
    """The findings of one flow file.

    Args:
        path: the flow file.
        root: the flows directory, for the name in the finding.

    Returns:
        One finding per `hideKeyboard` that had nothing to hide.
    """
    findings: List[Finding] = []
    keyboard_may_be_up = False
    for command in _commands(path):
        name = _command_name(command)
        if name in TEXT_INPUT_COMMANDS:
            keyboard_may_be_up = True
        elif name == "hideKeyboard":
            if not keyboard_may_be_up:
                findings.append(
                    Finding(
                        flow=str(path.relative_to(root)),
                        line=_line_of(path, "hideKeyboard"),
                        reason="nothing to hide: no text was typed into before it",
                    ),
                )
            keyboard_may_be_up = False
    return findings


def check_flows(flows_dir: pathlib.Path) -> List[Finding]:
    """The findings of every flow under `flows_dir`."""
    findings: List[Finding] = []
    for path in sorted(flows_dir.rglob("*.yaml")):
        findings.extend(check_flow(path, flows_dir))
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
    @click.option("--check", is_flag=True, help="Print the findings without a non-zero exit.")
    @click.argument("flows_dir", type=click.Path(path_type=pathlib.Path), default=DEFAULT_FLOWS_DIR)
    def cli(as_json: bool, check: bool, flows_dir: pathlib.Path) -> None:
        """Fails when a `hideKeyboard` has nothing to hide."""
        if not flows_dir.is_dir():
            click.echo(f"not a directory: {flows_dir}", err=True)
            raise SystemExit(IO_ERROR)
        findings = check_flows(flows_dir)
        if as_json:
            click.echo(json.dumps([f._asdict() for f in findings], indent=2))
        if findings:
            click.echo("A `hideKeyboard` with nothing to hide is a BACK keypress:\n")
            for finding in findings:
                click.echo(f"  - {finding.flow}:{finding.line} — {finding.reason}")
            click.echo(
                "\nA `hideKeyboard` before an `inputText` saves an empty draft and\n"
                "closes the editor: follow a text input with exactly one\n"
                "`hideKeyboard`, and with none at all when the keyboard was already\n"
                "hidden.",
                err=True,
            )
            if not check:
                raise SystemExit(VALIDATION_FAILURE)
        else:
            click.echo("Every `hideKeyboard` has something to hide.")

    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
