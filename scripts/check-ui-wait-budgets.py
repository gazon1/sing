#!/usr/bin/env python3
"""check-ui-wait-budgets.py — one number for the wait, in every layer that waits.

## The defect this catches

Two runners wait for the same screen, and they cannot share a constant: the Android
instrumented suite is Kotlin, the Maestro flows are YAML. So the number that is
right for one has to be written down twice, and nothing held the two copies
together.

That is not a hypothetical. The Maestro helper that reaches the agenda shell
learned that 8 s is not enough for a cold start on a loaded CI runner and moved to
30 s, with the reason written next to it: *"8 s is not always enough on a loaded CI
runner; 20 s covers cold-start variance including migration deferred-startup on
first launch"*. The instrumented suite kept 8 s, and `android-device-tests` was red
on every run of `main` for days with *"Today tab should be visible after cold
start"* — a message about a screen that was on screen 1.35 s after the activity
resumed.

Worse, the Maestro layer had already drifted against itself: forty-one of its waits
for the same `nav_tab_today` existed with five different timeouts, and the one that
had been reasoned about was the only one of the five that was right. Every one of
the others was as brittle as the instrumented suite had been, waiting for a screen a
loaded runner produces slowly.

## What this gate holds

`config/docs/ui-wait-budgets.txt` is the promise: `<name> <milliseconds>` per line.
This gate holds every layer that waits to it:

* the instrumented suite's wait constants must equal the catalogue — and a wait
  constant the catalogue does not know about is a finding, because a budget nobody
  promised is a budget nobody will keep in step;
* every Maestro wait for the shell must use the shell budget, in every flow and
  every helper, and a wait with no timeout at all is a finding too.

The gate does not care whether 30 s is the right number. It cares that there is
one number, and that the layers that wait on it agree. A number that is wrong for
everybody is a decision to make once, in the catalogue, where the reasoning can be
written down; a number that is wrong in one place is a flake waiting for a busy
runner.

Usage: python3 scripts/check-ui-wait-budgets.py [--json] [--quiet] [--root DIR]

`--root` names the tree to check. The pre-push hook uses it: the hook checks the
tree that is being pushed, not the worktree it happens to run in, and a gate
that checked the worktree would report green for a tree nobody pushed.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOGUE = ROOT / "config" / "docs" / "ui-wait-budgets.txt"
SHELL_PROBE = (
    ROOT
    / "androidApp"
    / "src"
    / "androidTest"
    / "kotlin"
    / "com"
    / "singularity"
    / "todo"
    / "ui"
    / "ShellProbe.kt"
)
MAESTRO_DIR = ROOT / "Maestro"

# The element whose appearance means "the app's shell is up". Every wait for it is
# a wait for a cold start, so every one of them is the shell budget.
SHELL_ELEMENT = "nav_tab_today"

# Catalogue name -> the constant that carries it in the instrumented suite.
#
# The mapping is written out rather than derived, because a derivation would have
# to guess which constants are budgets and which are something else, and a guess
# that silently stops matching is a gate that stopped asking its question. A new
# wait constant is not in this table, and the gate says so.
BUDGET_CONSTANTS = {
    "shell": "SHELL_TIMEOUT_MS",
    "tab": "TAB_TIMEOUT_MS",
    "device": "UI_DEVICE_TIMEOUT_MS",
}

CONSTANT_PATTERN = re.compile(
    r"^\s*(?:internal\s+)?const\s+val\s+(?P<name>[A-Z_]+_TIMEOUT_MS)\s*=\s*(?P<value>[\d_]+)L?\s*$",
)
CATALOGUE_PATTERN = re.compile(r"^(?P<name>[a-z][a-z0-9-]*)\s+(?P<value>\d+)$")
EXTENDED_WAIT_PATTERN = re.compile(r"^(\s*)-\s*extendedWaitUntil:\s*$")
VISIBLE_ID_PATTERN = re.compile(r"^\s*id:\s*(?P<id>\S+)\s*$")
TIMEOUT_PATTERN = re.compile(r"^\s*timeout:\s*(?P<timeout>\d+)\s*$")


def catalogue_budgets(root: pathlib.Path | None = None) -> dict[str, int]:
    """The budgets the catalogue promises, as name -> milliseconds."""
    catalogue = (root / "config" / "docs" / "ui-wait-budgets.txt") if root else CATALOGUE
    try:
        text = catalogue.read_text(encoding="utf-8")
    except OSError as exc:
        raise click.ClickException(f"cannot read {catalogue}: {exc}") from exc
    budgets: dict[str, int] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = CATALOGUE_PATTERN.match(stripped)
        if not match:
            raise click.ClickException(
                f"{catalogue}:{number}: cannot read a budget from {stripped!r}; "
                "the format is '<name> <milliseconds>'"
            )
        name = match.group("name")
        if name in budgets:
            raise click.ClickException(
                f"{catalogue}:{number}: the budget {name!r} is listed twice"
            )
        budgets[name] = int(match.group("value"))
    if not budgets:
        raise click.ClickException(f"{catalogue} promises no budgets at all")
    return budgets


def kotlin_budgets(root: pathlib.Path | None = None) -> dict[str, int]:
    """The wait constants the instrumented suite declares, as constant name -> ms."""
    probe = (
        root
        / "androidApp"
        / "src"
        / "androidTest"
        / "kotlin"
        / "com"
        / "singularity"
        / "todo"
        / "ui"
        / "ShellProbe.kt"
    ) if root else SHELL_PROBE
    try:
        text = probe.read_text(encoding="utf-8")
    except OSError as exc:
        raise click.ClickException(f"cannot read {probe}: {exc}") from exc
    budgets: dict[str, int] = {}
    for line in text.splitlines():
        match = CONSTANT_PATTERN.match(line)
        if match:
            budgets[match.group("name")] = int(match.group("value").replace("_", ""))
    return budgets


def shell_waits(root: pathlib.Path | None = None) -> list[dict]:
    """Every Maestro wait for the shell, as a list of records.

    The scan is line-based on purpose: the question is "which wait in this file
    waits for the shell, and how long does it give itself", and a YAML parse of a
    flow file that carries its own header and several documents answers a
    different, larger question.
    """
    waits: list[dict] = []
    base = root if root else ROOT
    maestro_dir = (base / "Maestro") if root else MAESTRO_DIR
    if not maestro_dir.is_dir():
        return waits
    for path in sorted(maestro_dir.rglob("*.yaml")):
        if not path.is_file():
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        index = 0
        while index < len(lines):
            match = EXTENDED_WAIT_PATTERN.match(lines[index])
            if not match:
                index += 1
                continue
            start = index
            indent = len(match.group(1))
            block: list[str] = []
            index += 1
            while index < len(lines):
                line = lines[index]
                if line.strip() and not line.startswith(" " * (indent + 1)):
                    break
                block.append(line)
                index += 1
            visible = None
            timeout = None
            timeout_line = start
            for offset, line in enumerate(block):
                id_match = VISIBLE_ID_PATTERN.match(line)
                if id_match:
                    visible = id_match.group("id")
                timeout_match = TIMEOUT_PATTERN.match(line)
                if timeout_match:
                    timeout = int(timeout_match.group("timeout"))
                    timeout_line = start + 1 + offset
            if visible == SHELL_ELEMENT:
                waits.append(
                    {
                        "file": str(path.relative_to(base)),
                        "line": timeout_line,
                        "timeout": timeout,
                    }
                )
    return waits


def scan(root: pathlib.Path | None = None) -> list[dict]:
    """The mismatches between the catalogue and the layers that wait."""
    budgets = catalogue_budgets(root)
    constants = kotlin_budgets(root)
    findings: list[dict] = []

    if "shell" not in budgets:
        findings.append(
            {
                "kind": "catalogue has no shell budget",
                "detail": (
                    "the shell budget is the one every layer waits on, and the "
                    "catalogue does not name it"
                ),
            }
        )
        return findings

    for name, constant in sorted(BUDGET_CONSTANTS.items()):
        if name not in budgets:
            findings.append(
                {
                    "kind": "catalogue has no budget",
                    "detail": (
                        f"the instrumented suite declares {constant}, and the "
                        f"catalogue does not name the {name} budget it carries"
                    ),
                }
            )
            continue
        if constant not in constants:
            findings.append(
                {
                    "kind": "missing constant",
                    "detail": (
                        f"the catalogue promises a {name} budget of "
                        f"{budgets[name]}ms, and {constant} is not declared"
                    ),
                }
            )
            continue
        if constants[constant] != budgets[name]:
            findings.append(
                {
                    "kind": "constant disagrees with the catalogue",
                    "detail": (
                        f"{constant} is {constants[constant]}ms and the catalogue "
                        f"promises {budgets[name]}ms"
                    ),
                }
            )

    for constant, value in sorted(constants.items()):
        expected = next(
            (name for name, const in BUDGET_CONSTANTS.items() if const == constant),
            None,
        )
        if expected is None:
            findings.append(
                {
                    "kind": "constant is not in the catalogue",
                    "detail": (
                        f"{constant} is {value}ms and no catalogue budget carries "
                        "it — a wait nobody promised is a wait nobody keeps in step"
                    ),
                }
            )

    shell_budget = budgets["shell"]
    for wait in shell_waits():
        if wait["timeout"] is None:
            findings.append(
                {
                    "kind": "shell wait without a timeout",
                    "detail": (
                        f"{wait['file']} waits for {SHELL_ELEMENT} with no timeout, "
                        "so it waits for Maestro's default rather than for the "
                        "budget the catalogue promises"
                    ),
                }
            )
        elif wait["timeout"] != shell_budget:
            findings.append(
                {
                    "kind": "shell wait disagrees with the catalogue",
                    "detail": (
                        f"{wait['file']} waits {wait['timeout']}ms for "
                        f"{SHELL_ELEMENT} and the catalogue promises {shell_budget}ms"
                    ),
                }
            )
    return findings


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--quiet", is_flag=True, help="Print nothing when the gate is clean")
@click.option(
    "--root",
    default=None,
    help="The tree to check (default: this repository)",
)
@click.pass_context
def cli(ctx: click.Context, emit_json: bool, quiet: bool, root: str | None) -> None:
    """Hold every layer that waits to the one written budget."""
    tree = pathlib.Path(root).resolve() if root else None
    try:
        findings = scan(tree)
    except click.ClickException as exc:
        click.echo(f"ui wait budgets: {exc.format_message()}")
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(json.dumps({"findings": findings}, indent=2))
    elif not findings and not quiet:
        budgets = catalogue_budgets(tree)
        click.echo(
            "ui wait budgets: "
            + ", ".join(f"{name}={value}ms" for name, value in sorted(budgets.items()))
        )
    elif findings:
        click.echo(f"ui wait budgets: {len(findings)} mismatch(es)")
        for finding in findings:
            click.echo(f"  - {finding['kind']}: {finding['detail']}")
    raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
