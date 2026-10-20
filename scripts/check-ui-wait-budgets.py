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
on every run of `main` for days, with *"Today tab should be visible after cold
start"* — a message about a screen that was on screen 1.35 s after the activity
resumed.

Worse, the Maestro layer had already drifted against *itself*: forty-one of its waits
for the same `nav_tab_today` existed with five different timeouts, and the one that
had been reasoned about was the only one of the five that was right. Every one of
the others was as brittle as the instrumented suite had been.

Fixing the shell was not the end of it. The Maestro layer waits for 137 different
elements, and it waited for the *same* element with different timeouts in different
flows — `menu_settings` with 5 s, 8 s and 10 s; `task_editor_title_input` with 3 s,
5 s, 8 s and 15 s. The number depended on which flow asked. That is the same defect
the shell had, one layer down, and it is answered the same way: a budget per *kind*
of element, written down once.

## What this gate holds

`config/docs/ui-wait-budgets.txt` is the promise. It has two sections:

* the instrumented suite's budgets — `<name> <milliseconds>` — which the Kotlin wait
  constants must equal, and a wait constant the catalogue does not know about is a
  finding, because a budget nobody promised is a budget nobody will keep in step;
* the Maestro layer's budgets, by the kind of element waited for —
  `kind <name> <milliseconds>`.

`config/docs/maestro-element-kinds.txt` maps every Maestro element that is waited
for to its kind, and this gate holds every wait to the budget of its kind:

* an element nobody budgeted is a finding — nobody promised a budget for it, so
  nobody will keep it in step;
* a wait that disagrees with its kind's budget is a finding, naming the element, its
  kind, the budget and the number that was used;
* a wait with no timeout at all is a finding — it waits for Maestro's default rather
  than for the budget;
* `nav_tab_today` keeps its cross-layer rule: it is the shell kind, and the shell
  kind's budget must equal the instrumented shell budget, because both layers wait
  for the same screen.

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
ELEMENT_KINDS = ROOT / "config" / "docs" / "maestro-element-kinds.txt"
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

# The kind the shell element belongs to, and the kind whose budget must equal the
# instrumented shell budget: both layers wait for the same screen.
SHELL_KIND = "shell"

# No kind waits for less than this. A budget below it is a budget that gives up
# before a loaded runner has finished composing.
MIN_KIND_BUDGET = 5000

# The Maestro commands that wait for an element. `extendedWaitUntil` waits for a
# condition to hold, `scrollUntilVisible` waits for an element to be scrolled to;
# both name an element and both carry a timeout.
WAIT_COMMANDS = ("extendedWaitUntil", "scrollUntilVisible")

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
KIND_PATTERN = re.compile(r"^kind\s+(?P<name>[a-z][a-z0-9-]*)\s+(?P<value>\d+)$")
ELEMENT_KIND_PATTERN = re.compile(r"^(?P<id>\S+)\s+(?P<kind>[a-z][a-z0-9-]*)\s*$")
COMMAND_PATTERN = re.compile(r"^(?P<indent>\s*)-\s*(?P<command>[A-Za-z_][A-Za-z0-9_]*):\s*$")
VISIBLE_ID_PATTERN = re.compile(r"^\s*id:\s*(?P<id>\S+)\s*$")
TIMEOUT_PATTERN = re.compile(r"^(?P<lead>\s*timeout:\s*)(?P<timeout>\d+)(?P<tail>\s*)$")


def _catalogue_text(root: pathlib.Path | None = None) -> tuple[pathlib.Path, str]:
    catalogue = (root / "config" / "docs" / "ui-wait-budgets.txt") if root else CATALOGUE
    try:
        text = catalogue.read_text(encoding="utf-8")
    except OSError as exc:
        raise click.ClickException(f"cannot read {catalogue}: {exc}") from exc
    return catalogue, text


def _parse_catalogue(root: pathlib.Path | None = None) -> tuple[dict[str, int], dict[str, int]]:
    """The catalogue's two sections: the instrumented budgets and the kind budgets."""
    catalogue, text = _catalogue_text(root)
    budgets: dict[str, int] = {}
    kinds: dict[str, int] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        kind_match = KIND_PATTERN.match(stripped)
        if kind_match:
            name = kind_match.group("name")
            if name in kinds:
                raise click.ClickException(
                    f"{catalogue}:{number}: the kind {name!r} is listed twice"
                )
            kinds[name] = int(kind_match.group("value"))
            continue
        if stripped.startswith("kind "):
            raise click.ClickException(
                f"{catalogue}:{number}: cannot read a kind budget from {stripped!r}; "
                "the format is 'kind <name> <milliseconds>'"
            )
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
    if not kinds:
        raise click.ClickException(f"{catalogue} promises no kind budgets at all")
    return budgets, kinds


def catalogue_budgets(root: pathlib.Path | None = None) -> dict[str, int]:
    """The instrumented suite's budgets, as name -> milliseconds."""
    budgets, _ = _parse_catalogue(root)
    return budgets


def kind_budgets(root: pathlib.Path | None = None) -> dict[str, int]:
    """The Maestro layer's budgets, as kind name -> milliseconds."""
    _, kinds = _parse_catalogue(root)
    return kinds


def element_kinds(root: pathlib.Path | None = None) -> dict[str, str]:
    """The Maestro elements that are waited for, as element id -> kind.

    The file is the promise that every waited-for element has a budget, so a
    mapping to a kind the catalogue does not price is an error, not a finding:
    the file cannot mean what it says.
    """
    path = (root / "config" / "docs" / "maestro-element-kinds.txt") if root else ELEMENT_KINDS
    kinds = kind_budgets(root)
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise click.ClickException(f"cannot read {path}: {exc}") from exc
    mapping: dict[str, str] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = ELEMENT_KIND_PATTERN.match(stripped)
        if not match:
            raise click.ClickException(
                f"{path}:{number}: cannot read a mapping from {stripped!r}; "
                "the format is '<maestro id> <kind>'"
            )
        element = match.group("id")
        kind = match.group("kind")
        if kind not in kinds:
            raise click.ClickException(
                f"{path}:{number}: the element {element!r} is mapped to the kind "
                f"{kind!r}, which the catalogue does not price"
            )
        if element in mapping:
            raise click.ClickException(
                f"{path}:{number}: the element {element!r} is listed twice"
            )
        mapping[element] = kind
    return mapping


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


def maestro_waits(root: pathlib.Path | None = None) -> list[dict]:
    """Every Maestro wait for an element, as a list of records.

    The scan is line-based on purpose: the question is "which wait in this file
    waits for which element, and how long does it give itself", and a YAML parse of
    a flow file that carries its own header and several documents answers a
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
            match = COMMAND_PATTERN.match(lines[index])
            if not match or match.group("command") not in WAIT_COMMANDS:
                index += 1
                continue
            start = index
            indent = len(match.group("indent"))
            block: list[str] = []
            index += 1
            while index < len(lines):
                line = lines[index]
                if line.strip() and not line.startswith(" " * (indent + 1)):
                    break
                block.append(line)
                index += 1
            elements: list[str] = []
            timeout = None
            timeout_line = start
            for offset, line in enumerate(block):
                id_match = VISIBLE_ID_PATTERN.match(line)
                if id_match:
                    elements.append(id_match.group("id").strip("\"'"))
                timeout_match = TIMEOUT_PATTERN.match(line)
                if timeout_match:
                    timeout = int(timeout_match.group("timeout"))
                    timeout_line = start + 1 + offset
            if not elements:
                continue
            waits.append(
                {
                    "file": str(path.relative_to(base)),
                    "line": timeout_line,
                    "command": match.group("command"),
                    "elements": elements,
                    "timeout": timeout,
                }
            )
    return waits


def scan(root: pathlib.Path | None = None) -> list[dict]:
    """The mismatches between the catalogue and the layers that wait."""
    budgets, kinds = _parse_catalogue(root)
    constants = kotlin_budgets(root)
    mapping = element_kinds(root)
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

    for name, value in sorted(kinds.items()):
        if value < MIN_KIND_BUDGET:
            findings.append(
                {
                    "kind": "kind budget is below the floor",
                    "detail": (
                        f"the {name} kind is {value}ms, and no kind waits for less "
                        f"than {MIN_KIND_BUDGET}ms"
                    ),
                }
            )

    if SHELL_KIND not in kinds:
        findings.append(
            {
                "kind": "catalogue has no kind budget",
                "detail": (
                    f"the catalogue prices no {SHELL_KIND!r} kind, and every wait "
                    "for the shell is a wait for that kind"
                ),
            }
        )
    else:
        if kinds[SHELL_KIND] != budgets["shell"]:
            findings.append(
                {
                    "kind": "the shell kind disagrees with the instrumented shell budget",
                    "detail": (
                        f"the {SHELL_KIND} kind is {kinds[SHELL_KIND]}ms and the "
                        f"instrumented shell budget is {budgets['shell']}ms — both "
                        "layers wait for the same screen"
                    ),
                }
            )
        shell_kind = mapping.get(SHELL_ELEMENT)
        if shell_kind is not None and shell_kind != SHELL_KIND:
            findings.append(
                {
                    "kind": "the shell element is not budgeted as the shell kind",
                    "detail": (
                        f"{SHELL_ELEMENT} is budgeted as the {shell_kind} kind, and "
                        f"every wait for it is a wait for a cold start"
                    ),
                }
            )

    waits = maestro_waits(root)
    for wait in waits:
        for element in wait["elements"]:
            kind = mapping.get(element)
            if kind is None:
                findings.append(
                    {
                        "kind": "element nobody budgeted",
                        "detail": (
                            f"{wait['file']} waits for {element}, and no kind "
                            "prices it — an element nobody budgeted is a budget "
                            "nobody keeps in step"
                        ),
                    }
                )
                continue
            budget = kinds[kind]
            if wait["timeout"] is None:
                findings.append(
                    {
                        "kind": "wait without a timeout",
                        "detail": (
                            f"{wait['file']} waits for {element} with no timeout, "
                            f"so it waits for Maestro's default rather than for the "
                            f"{budget}ms the {kind} kind promises"
                        ),
                    }
                )
            elif wait["timeout"] != budget:
                findings.append(
                    {
                        "kind": "wait disagrees with its kind's budget",
                        "detail": (
                            f"{wait['file']} waits {wait['timeout']}ms for "
                            f"{element} (a {kind}), and the {kind} kind's budget "
                            f"is {budget}ms"
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
        kinds = kind_budgets(tree)
        click.echo(
            "ui wait budgets: "
            + ", ".join(f"{name}={value}ms" for name, value in sorted(budgets.items()))
            + " | "
            + ", ".join(f"kind {name}={value}ms" for name, value in sorted(kinds.items()))
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
