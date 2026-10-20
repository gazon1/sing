#!/usr/bin/env python3
"""e2e-shard-plan.py — which flows a nightly shard holds, and how to run it locally.

## The question this answers

The nightly splits the Maestro suite into shards, and when a shard is red the
first thing anybody wants is to run *that shard* again, on their own machine.
The CLI has had `--shard` and `--shards` for a while, and they still are not
enough to do it: the number of shards lives in the nightly's `plan` job, and a
local run has to guess it. A guess of 3 against a nightly that used 4 runs
*different flows* — the modulo lands elsewhere — and the reproduction then
"passes" while saying nothing about the shard that failed.

So the number of shards moves into one file, `config/docs/e2e-shards.txt`,
which both the nightly's plan job and this script read. One number, two
readers, no guess.

## What it prints

- `--suite full` — the shard count of that suite.
- `--shard N --suite full` — the flows shard N holds, and the commands that
  reproduce it locally.

The flow list is the same list the runner uses: every `*.yaml` under
`Maestro/flows`, sorted by name, and shard N holds the flows whose index
modulo the shard count is N-1. Sharding happens *before* tag filtering, so a
shard of the `full` suite is a shard of every flow — the same rule the runner
applies, reproduced here so the answer can be read without a device.

## Why the standard library only

The nightly's `plan` job runs before any Python tooling is installed — it
computes the matrix, and the matrix is what installs the tooling. A script
that needs Click would need the venv the job is about to decide to create, so
this one parses its own two flags and imports nothing. It is not a gate, and
`scripts/check-script-conventions.py` does not apply to it.

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
FLOWS_DIR = ROOT / "Maestro" / "flows"
APK_PATH = ROOT / "androidApp" / "build" / "outputs" / "apk" / "debug" / "androidApp-debug.apk"


def read_plan(path: pathlib.Path = PLAN_FILE) -> dict[str, tuple[int, str]]:
    """The plan, as suite → (shard count, flow directory).

    A file that cannot be read is an error, not a default: a default shard
    count is how a local run ends up reproducing the wrong flows.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"cannot read {path}: {exc}") from exc
    plan: dict[str, tuple[int, str]] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) != 3 or not parts[1].isdigit():
            raise ValueError(
                f"{path}:{number}: cannot read a shard plan from {stripped!r}; "
                "the format is '<suite> <count> <flow directory>'"
            )
        suite, count, flow_dir = parts[0], int(parts[1]), parts[2]
        if suite in plan:
            raise ValueError(f"{path}:{number}: the suite {suite!r} is listed twice")
        plan[suite] = (count, flow_dir)
    if not plan:
        raise ValueError(f"{path}: the file names no suite")
    return plan


def collect_flows(target: pathlib.Path = FLOWS_DIR) -> list[pathlib.Path]:
    """Every flow, in the runner's order.

    The order is the runner's order and not this script's preference: the
    shard is `index % shards`, so a different sort puts different flows in
    different shards. `scripts/tests/test_e2e_shard_plan.py` pins this against
    the runner's own `collect_flows`.
    """
    if not target.is_dir():
        return []
    return sorted(target.rglob("*.yaml"), key=lambda p: p.name.lower())


def shard_flows(flows: list[pathlib.Path], shard: int, shards: int) -> list[pathlib.Path]:
    """The flows of one shard — the runner's rule, reproduced."""
    return [f for i, f in enumerate(flows) if (i % shards) == (shard - 1)]


def local_commands(suite: str, shard: int, shards: int) -> list[str]:
    """The commands that reproduce a shard locally, in order."""
    return [
        f"./gradlew :androidApp:assembleDebug",
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
    shards, flow_dir = plan[suite]

    if shard is None:
        if matrix:
            # The GitHub matrix spelling: a JSON array of the shard indices.
            print("[" + ",".join(str(i) for i in range(1, shards + 1)) + "]")
            return 0
        if emit_json:
            print(
                json.dumps(
                    {"suite": suite, "shards": shards, "flow_dir": flow_dir}, indent=2
                )
            )
        else:
            print(f"{suite}: {shards} shard(s), flows from {flow_dir}")
        return 0

    if not 1 <= shard <= shards:
        print(
            f"e2e shard plan: shard {shard} is out of range — "
            f"the {suite} suite has {shards} shard(s)",
            file=sys.stderr,
        )
        return 1

    flows = collect_flows(ROOT / flow_dir)
    mine = shard_flows(flows, shard, shards)
    commands = local_commands(suite, shard, shards)

    if emit_json:
        print(
            json.dumps(
                {
                    "suite": suite,
                    "shard": shard,
                    "shards": shards,
                    "flow_dir": flow_dir,
                    "total_flows": len(flows),
                    "flows": [str(f.relative_to(ROOT)) for f in mine],
                    "commands": commands,
                },
                indent=2,
            )
        )
        return 0

    print(f"{suite} shard {shard} of {shards} — {len(mine)} of {len(flows)} flows")
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
