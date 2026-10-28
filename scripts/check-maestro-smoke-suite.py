#!/usr/bin/env python3
"""check-maestro-smoke-suite.py — the smoke suite is what the doc says it is.

## The problem this solves

`Maestro/CONVENTIONS.md` tells a reader how big the suite a pull request runs
is, so they can plan against it. That number was fiction for months: the doc
said six smoke flows, four of the six paths it named did not exist, and the
`smoke` tag had meanwhile spread to 27 flows.

Nobody noticed, because the **tag** is what selects the suite. The prose is
not read by the runner, so a prose that lies is not a red build — it is a
number that everybody quotes and nobody checks. The flows themselves were
fine; the promise about them was not.

So the size is written down in one place (the conventions) and checked from
there. Two things are checked, and they are the two ways the promise can
break:

* **the count** — the number in the doc against the number of flows that
  carry the tag;
* **the cost** — the largest smoke shard against the largest full-suite
  shard. A smoke suite that costs more per shard than the nightly's full
  suite is not a subset: it is the slow path, and it is the path every pull
  request takes.

Both readings come from the runner's own modules and the repository's own
shard plan. A gate that re-implemented the selection would be a second place
for the suite to be wrong — the same argument the shard plan makes.

Usage: python3 scripts/check-maestro-smoke-suite.py [--list] [--json] [--check]
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import re
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
CONVENTIONS = ROOT / "Maestro" / "CONVENTIONS.md"
PLAN_FILE = ROOT / "config" / "docs" / "e2e-shards.txt"
SHARD_PLAN_SCRIPT = ROOT / "scripts" / "e2e-shard-plan.py"
FLOWS_ROOT = ROOT / "Maestro" / "flows"

#: The line in the conventions that carries the size. The number in it is the
#: promise; the gate holds it to the tag.
DOCUMENTED_COUNT = re.compile(r"^Current smoke flows \((\d+)\):", re.MULTILINE)


def _load_shard_plan():
    """The repository's own shard-plan reader.

    The script has a hyphen in its name, so it is loaded by path. Its
    `read_plan` is the one rule for the plan file's format, and a gate that
    parsed the file a second way would be a second place for the plan to be
    wrong.
    """
    spec = importlib.util.spec_from_file_location("e2e_shard_plan", SHARD_PLAN_SCRIPT)
    if not spec or not spec.loader:
        raise ValueError(f"cannot load {SHARD_PLAN_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def documented_count(text: str) -> int | None:
    """The suite size the conventions promise, or None when it promises none."""
    match = DOCUMENTED_COUNT.search(text)
    return int(match.group(1)) if match else None


def _display(path: pathlib.Path) -> str:
    """The path as a reader would name it: repo-relative when it is in the repo."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def smoke_flow_paths() -> list[str]:
    """The smoke flows, as repo-relative paths, in the runner's order."""
    from emulator.maestro._flow import smoke_flows

    return [str(p.relative_to(ROOT)) for p in smoke_flows(FLOWS_ROOT)]


def actual_count() -> int:
    """The number of flows that carry the `smoke` tag, by the runner's reader."""
    return len(smoke_flow_paths())


def smoke_shard_size(shards: int) -> int:
    """The largest shard the smoke suite produces.

    The runner shards over *every* flow and applies the tag afterwards, so a
    shard's load is the number of selected flows whose index lands on it. The
    largest of those is the one a runner waits for, and it is what the
    comparison has to be about.
    """
    from emulator.maestro._flow import SMOKE_TAG, collect_flows, filter_flows_by_tag

    flows = collect_flows(FLOWS_ROOT)
    return max(
        len(
            filter_flows_by_tag(
                [f for i, f in enumerate(flows) if (i % shards) == (s - 1)], [SMOKE_TAG]
            )
        )
        for s in range(1, shards + 1)
    )


def full_shard_size(shards: int) -> int:
    """The largest shard the full suite produces."""
    from emulator.maestro._flow import collect_flows

    flows = collect_flows(FLOWS_ROOT)
    return max(
        len([f for i, f in enumerate(flows) if (i % shards) == (s - 1)])
        for s in range(1, shards + 1)
    )


def scan() -> list[dict]:
    """The findings, as a list of dictionaries."""
    findings: list[dict] = []

    try:
        text = CONVENTIONS.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"cannot read {CONVENTIONS}: {exc}") from exc

    promised = documented_count(text)
    if promised is None:
        findings.append(
            {
                "kind": "no documented size",
                "detail": (
                    f"{_display(CONVENTIONS)} has no 'Current smoke flows (N):' "
                    "line — the size of the suite a pull request runs is not written "
                    "down, and a number nobody wrote down is a number nobody can check"
                ),
            }
        )
        return findings

    actual = actual_count()
    if promised != actual:
        findings.append(
            {
                "kind": "size does not match",
                "detail": (
                    f"the conventions promise {promised} smoke flow(s); the tag "
                    f"selects {actual}. Update the number in "
                    f"{_display(CONVENTIONS)} in the same commit that "
                    "changed the tag."
                ),
            }
        )

    plan = _load_shard_plan().read_plan()
    smoke_shards = plan.get("smoke")
    full_shards = plan.get("full")
    if not smoke_shards or not full_shards:
        findings.append(
            {
                "kind": "no shard plan",
                "detail": (
                    f"{_display(PLAN_FILE)} does not give a shard count for both "
                    "suites — the cost of a pull request cannot be compared with the "
                    "cost of the nightly without one"
                ),
            }
        )
        return findings

    smoke_shard = smoke_shard_size(smoke_shards)
    full_shard = full_shard_size(full_shards)
    if smoke_shard > full_shard:
        findings.append(
            {
                "kind": "smoke shard costs more than a full shard",
                "detail": (
                    f"the smoke suite runs {actual} flow(s) over {smoke_shards} "
                    f"shard(s), so the largest shard holds {smoke_shard} flow(s); the "
                    f"full suite's largest shard holds {full_shard}. A pull request "
                    "would then cost more than the nightly. Raise the smoke shard "
                    f"count in {_display(PLAN_FILE)}, or narrow the tag."
                ),
            }
        )

    return findings


@click.command()
@click.option("--list", "list_flows", is_flag=True, help="Print the smoke flows and exit")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--check", is_flag=True, help="Dry-run: print the result without exiting non-zero")
@click.pass_context
def cli(ctx: click.Context, list_flows: bool, emit_json: bool, check: bool) -> None:
    if list_flows:
        for path in smoke_flow_paths():
            click.echo(path)
        raise ctx.exit(CLEAN)

    try:
        findings = scan()
    except ValueError as exc:
        click.echo(f"maestro smoke suite: {exc}")
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(json.dumps({"findings": findings}, indent=2))
    elif not findings:
        click.echo(f"maestro smoke suite: {actual_count()} flow(s), as documented")
    else:
        click.echo(f"maestro smoke suite: {len(findings)} finding(s)")
        for finding in findings:
            click.echo(f"  {finding['kind']}: {finding['detail']}")
    if check:
        raise ctx.exit(CLEAN)
    raise ctx.exit(VALIDATION_FAILURE if findings else CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
