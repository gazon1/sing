#!/usr/bin/env python3
"""e2e-shard-plan.py — which flows a nightly shard holds, and how to run it locally.

## The question this answers

The nightly splits the Maestro suite into shards, and when a shard is red the
first thing anybody wants is to run *that shard* again, on their own machine.
The CLI has had `--shard` and `--shards` for a while, and they are still not
enough to do it: the number of shards lives in the nightly's `plan` job, and a
local run has to guess it. A guess of 3 against a nightly that used 4 runs
*different flows* — the modulo lands elsewhere — and the reproduction then
"passes" while saying nothing about the shard that failed.

So the number of shards moves into one file, `config/docs/e2e-shards.txt`,
which both the nightly's `plan` job and this script read. One number, two
readers, no guess.

## What it prints

- `--suite full` — the shard count of that suite.
- `--shard N --suite full` — the flows shard N holds, and the commands that
  reproduce it locally.

The flow list is the runner's own list, and the shard is the runner's own
rule: the flows are collected by the runner's `collect_flows`, the suite's
selection by the runner's `suite_selection`, and shard N holds the flows
whose index modulo the shard count is N-1. The tag filter is applied
*before* the shard, so a shard holds an equal share of the flows the suite
actually runs — sharding the whole list first gave the smoke suite shards of
7, 9, 4 and 7 flows, and the runner waiting on the largest one set the wall
clock for every pull request. The `full` suite selects no tag, so its shards
are the same either way.

Nothing here is re-implemented on purpose. A plan that repeated the runner's
rules would be a second place for them to be wrong, and the disagreement
would show up as a green reproduction of a red shard.

Usage:

    python3 scripts/e2e-shard-plan.py --suite full
    python3 scripts/e2e-shard-plan.py --suite full --shard 3
    python3 scripts/e2e-shard-plan.py --suite full --shard 3 --json

Exit codes:
  0 — the plan was printed
  1 — the plan file is missing or unreadable, or the shard is out of range
"""
from __future__ import annotations

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLAN_FILE = ROOT / "config" / "docs" / "e2e-shards.txt"
FLOWS_ROOT = ROOT / "Maestro" / "flows"
APK_PATH = ROOT / "androidApp" / "build" / "outputs" / "apk" / "debug" / "androidApp-debug.apk"

# The runner's own modules are the source of truth for which flows a suite
# runs and in what order, so this script asks them rather than re-implementing
# them. They are imported lazily, in the branch that needs them: the nightly's
# plan job runs before the tooling is installed, and it needs the shard *count*
# — the plan file — and nothing else. Importing the runner's modules at module
# level would have made that job fail on a missing third-party dependency, for
# a listing nobody asked it for.


def _runner_modules():
    """The runner's flow discovery, or a clear error explaining what is missing."""
    sys.path.insert(0, str(ROOT / "scripts" / "src"))
    try:
        from emulator.maestro._flow import (
            collect_flows,
            filter_flows_by_tag,
            suite_selection,
        )
    except ImportError as exc:
        raise ValueError(
            "the runner's flow discovery could not be imported "
            f"({exc}). Install the tooling first: pip install -e scripts/src"
        ) from exc
    return collect_flows, filter_flows_by_tag, suite_selection


def read_plan(path: pathlib.Path = PLAN_FILE) -> dict[str, int]:
    """The suite → shard-count map, read from the plan file.

    A file that cannot be read is an error, not a default: a default shard
    count is how a local run ends up reproducing the wrong flows.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"cannot read {path}: {exc}") from exc
    plan: dict[str, int] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) != 2 or not parts[1].isdigit():
            raise ValueError(
                f"{path}:{number}: cannot read a shard count from {stripped!r}; "
                "the format is '<suite> <count>'"
            )
        suite, count = parts[0], int(parts[1])
        if suite in plan:
            raise ValueError(f"{path}:{number}: the suite {suite!r} is listed twice")
        plan[suite] = count
    if not plan:
        raise ValueError(f"{path}: the file names no suite")
    return plan


def collect_flows(target: pathlib.Path = FLOWS_ROOT) -> list[pathlib.Path]:
    """The runner's flow list, in the runner's order.

    A thin re-export of the runner's own function, so a caller of this module
    gets the runner's list rather than a second implementation of it.
    """
    collect, _, _ = _runner_modules()
    return collect(target)


def shard_flows(flows: list[pathlib.Path], shard: int, shards: int) -> list[pathlib.Path]:
    """The flows of one shard — the runner's rule, reproduced."""
    return [f for i, f in enumerate(flows) if (i % shards) == (shard - 1)]


def local_commands(suite: str, shard: int, shards: int) -> list[str]:
    """The commands that reproduce a shard locally, in order."""
    return [
        "./gradlew :androidApp:assembleDebug",
        (
            f"python3 scripts/run-maestro-e2e.py --apk {APK_PATH.relative_to(ROOT)} "
            f"--suite {suite} --shard {shard} --shards {shards}"
        ),
    ]


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    suite = "full"
    shard: int | None = None
    emit_json = False
    matrix = False

    index = 0
    while index < len(args):
        arg = args[index]
        if arg == "--suite" and index + 1 < len(args):
            suite = args[index + 1]
            index += 2
        elif arg == "--shard" and index + 1 < len(args):
            try:
                shard = int(args[index + 1])
            except ValueError:
                print(f"--shard wants a number, got {args[index + 1]!r}", file=sys.stderr)
                return 1
            index += 2
        elif arg == "--json":
            emit_json = True
            index += 1
        elif arg == "--matrix":
            matrix = True
            index += 1
        elif arg in ("--help", "-h"):
            print(__doc__)
            return 0
        else:
            print(f"unknown argument: {arg}", file=sys.stderr)
            return 1

    try:
        plan = read_plan()
    except ValueError as exc:
        print(f"e2e shard plan: {exc}", file=sys.stderr)
        return 1

    if suite not in plan:
        print(
            f"e2e shard plan: no plan for suite {suite!r} "
            f"(known: {', '.join(sorted(plan))})",
            file=sys.stderr,
        )
        return 1
    shards = plan[suite]

    if shard is None:
        if matrix:
            # The GitHub matrix spelling: a JSON array of the shard indices.
            print("[" + ",".join(str(i) for i in range(1, shards + 1)) + "]")
            return 0
        if emit_json:
            print(json.dumps({"suite": suite, "shards": shards}, indent=2))
        else:
            print(f"{suite}: {shards} shard(s)")
        return 0

    if not 1 <= shard <= shards:
        print(
            f"e2e shard plan: shard {shard} is out of range — "
            f"the {suite} suite has {shards} shard(s)",
            file=sys.stderr,
        )
        return 1

    try:
        collect_flows, filter_flows_by_tag, suite_selection = _runner_modules()
    except ValueError as exc:
        print(f"e2e shard plan: {exc}", file=sys.stderr)
        return 1
    target, tags = suite_selection(FLOWS_ROOT, suite)
    flows = collect_flows(target)
    selected = filter_flows_by_tag(flows, tags) if tags else list(flows)
    mine = shard_flows(selected, shard, shards)
    commands = local_commands(suite, shard, shards)

    if emit_json:
        print(
            json.dumps(
                {
                    "suite": suite,
                    "shard": shard,
                    "shards": shards,
                    "tags": tags,
                    "total_flows": len(flows),
                    "selected_flows": len(selected),
                    "flows": [str(f.relative_to(ROOT)) for f in mine],
                    "selected": [str(f.relative_to(ROOT)) for f in mine],
                    "commands": commands,
                },
                indent=2,
            )
        )
        return 0

    print(f"{suite} shard {shard} of {shards} — {len(mine)} of {len(selected)} flows")
    if tags:
        print(f"The {suite} suite selects {len(selected)} flow(s) by tag {tags}.")
    print()
    for flow in mine:
        print(f"  {flow.relative_to(ROOT)}")
    print()
    print("Reproduce it locally:")
    for command in commands:
        print(f"  {command}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
