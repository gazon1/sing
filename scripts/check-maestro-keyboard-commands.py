#!/usr/bin/env python3
"""
check-maestro-keyboard-commands.py — a `hideKeyboard` must have something to hide.

## The defect this gate holds closed

`hideKeyboard` is not "make sure the keyboard is not on the screen". When the
keyboard *is* on the screen, that is what it does. When it is **not**, there is
nothing to hide, and Maestro's way of hiding a keyboard that is not there is a
BACK keypress.

The editors are dialogs, so a BACK keypress closes them. Measured on a real
phone (2026-10-28, realme RMX3938, SDK 35): `tasks/17-create-daily-recurring`
failed with `Element not found: Id matching regex: task_editor_save`, and the
debug hierarchy showed the agenda — the editor had been dismissed by the flow's
own second `hideKeyboard`, the one after the keyboard was already hidden.

So the rule is: **a `hideKeyboard` follows a text input, and there is one
`hideKeyboard` per text input.** A second one, or one with no text input
before it, is a BACK keypress in disguise.

## What the gate reports

For each flow, the commands are read in order and the keyboard's state is
tracked:

- `inputText` / `eraseText` — the field was focused and typed into: the
  keyboard may be up, and there is something to hide;
- `hideKeyboard` — the keyboard is hidden again.

A `hideKeyboard` in a state where the keyboard is already hidden is a finding.

## What it cannot see

A helper flow (`runFlow`) that types text would make the state of the calling
flow wrong. No helper in `Maestro/helpers` types today, and the gate says so
in its failure message rather than pretending to know.

Usage: python3 scripts/check-maestro-keyboard-commands.py [--json] [flows-dir]
"""
from __future__ import annotations

import json
import pathlib
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
DEFAULT_FLOWS_DIR = ROOT / "Maestro" / "flows"

#: Commands that put text into a field, and therefore may leave the keyboard up.
TEXT_INPUT_COMMANDS = frozenset({"inputText", "eraseText"})


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
    @click.argument("flows_dir", type=click.Path(path_type=pathlib.Path), default=DEFAULT_FLOWS_DIR)
    def cli(as_json: bool, flows_dir: pathlib.Path) -> None:
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
                "\nA `hideKeyboard` that follows another `hideKeyboard`, or that follows\n"
                "anything but a text input, closes the screen instead of the keyboard:\n"
                "the editors are dialogs, and BACK dismisses them. Follow a text input\n"
                "with exactly one `hideKeyboard`, and with none at all when the keyboard\n"
                "was already hidden.\n"
                "\nSee docs/decisions/2026-10-28-the-keyboard-was-already-down.md.",
                err=True,
            )
            raise SystemExit(VALIDATION_FAILURE)
        click.echo("Every `hideKeyboard` has something to hide.")

    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
