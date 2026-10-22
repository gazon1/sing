#!/usr/bin/env python3
"""check-ai-write-tools.py — a tool that writes a user's data must say so.

## The defect this catches

A proposal is a change nobody has agreed to yet. The user sees it, reads it and
decides. That is the whole point of the write path (`2026-10-22-the-write-path-asks-first`).

So far the write path is half a write path. Four delete tools and `write_task_proposal`
propose; `create_task`, `update_task`, `create_project`, `update_project`,
`create_note`, `update_note`, `create_tag` and `decompose_and_create` write to the
user's data immediately. The model can create a task without the user seeing a
proposal for it — the same act, two different treatments, decided by which tool the
model happened to pick. A user who agreed to "set the priority" and then found a task
created is reading a rule that does not exist.

## What this gate measures

It does not decide which tools are right — that is a decision, and the decision
belongs to the change that converts a tool. It measures the state:

- a tool that writes a user's data directly is listed in
  `config/docs/ai-direct-writes.txt`, with the reason it writes directly;
- a tool that is not listed fails the gate, and the reason it must be written down;
- a listed tool that no longer writes directly fails the gate too — a stale entry
  hides the fact that the work was done, or that the tool was deleted;
- a tool that both proposes and writes directly fails the gate: one tool, two
  treatments, and the user cannot tell which one they are looking at.

The list is the measure. When a tool is converted to the proposal path, its entry
goes away, and the list gets shorter — which is the direction this gate wants.

The registry is a data file rather than a constant in this script, because a
registry nobody can read without running a program is a registry nobody reads.
It is a file, so a reviewer sees the reasons in a diff, and it is a committed
file, so a control can mutate it and prove this gate can fail.

## What counts as a direct write

A call on a repository of the user's own data: `taskRepository.create(...)`,
`notesRepository.update(...)`. The proposal repository is not the user's data — a
proposal is a proposal — so `proposals.save(...)` is not a direct write, and a tool
that does only that proposes.

Usage:
    python3 scripts/check-ai-write-tools.py
    python3 scripts/check-ai-write-tools.py --list

Exit codes:
    0 — every direct writer is listed with a reason, and nothing else writes
    1 — an unlisted direct writer, a stale entry, or a tool that does both
"""

from __future__ import annotations

import re
from pathlib import Path

import click

from sgate.commands.base import CLEAN, VALIDATION_FAILURE, run

ROOT = Path(__file__).resolve().parent.parent

#: Where the tools live. One directory: every tool the model can call is a class
#: in one of these files, and a tool in another directory would be a tool the gate
#: cannot see.
TOOLS_DIR = ROOT / "shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools"

#: The registry of direct writers and their reasons. A data file, so the reasons
#: are reviewable in a diff and the control can mutate the registry rather than
#: this script.
DIRECT_WRITES_FILE = ROOT / "config/docs/ai-direct-writes.txt"

#: A call on a repository. The receiver is captured so the proposal repository can
#: be told apart from the user's data.
REPOSITORY_CALL = re.compile(r"\b(?P<receiver>\w*[Rr]epository)\.(?P<method>create|update|delete|upsert|save)\(")

#: The proposal repository, by the names it is given in the tools. A proposal is
#: not the user's data: saving one is how a tool proposes, not how it writes.
PROPOSAL_RECEIVERS = {"proposals", "proposalRepository"}

#: What a tool has to mention for it to be on the proposal path.
PROPOSAL_MARKERS = ("ProposalBuilder", "ProposalOperation")


def read_registry() -> dict[str, str]:
    """The listed direct writers, and the reason each one writes directly.

    A line is `ToolName: the reason`. A comment line (`#`) and a blank line are
    not entries. A line with no reason is a finding rather than an entry with an
    empty reason: "it writes directly" without a reason is not a written decision.
    """
    registry: dict[str, str] = {}
    problems: list[str] = []
    if not DIRECT_WRITES_FILE.is_file():
        return registry
    for number, line in enumerate(DIRECT_WRITES_FILE.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        name, separator, reason = stripped.partition(":")
        name = name.strip()
        reason = reason.strip()
        if not separator or not name or not reason:
            problems.append(
                f"{DIRECT_WRITES_FILE.relative_to(ROOT)}:{number}: an entry must read "
                "`ToolName: the reason it writes directly` — an entry without a reason is "
                "not a written decision"
            )
            continue
        registry[name] = reason
    if problems:
        # A malformed registry cannot be compared with the code, so the gate has
        # to say so rather than compare against a partial list and report the
        # missing tools as unlisted writers.
        for problem in problems:
            click.echo(f"ERROR: {problem}")
        raise RegistryUnreadable(f"{len(problems)} malformed entr(ies) in the registry")
    return registry


class RegistryUnreadable(RuntimeError):
    """The registry could not be read — an infrastructure failure, not a verdict."""


def tool_files() -> list[Path]:
    """Every file that holds a tool class."""
    if not TOOLS_DIR.is_dir():
        return []
    return sorted(p for p in TOOLS_DIR.glob("*.kt") if re.search(r"class\s+\w*Tool\b", p.read_text(encoding="utf-8")))


def direct_writes(path: Path) -> list[str]:
    """The calls in this file that write a user's data directly."""
    text = path.read_text(encoding="utf-8")
    found = []
    for match in REPOSITORY_CALL.finditer(text):
        receiver = match.group("receiver")
        if receiver.lower() in {r.lower() for r in PROPOSAL_RECEIVERS}:
            continue
        found.append(f"{receiver}.{match.group('method')}")
    return sorted(set(found))


def proposes(path: Path) -> bool:
    """Whether this tool is on the proposal path."""
    text = path.read_text(encoding="utf-8")
    return any(marker in text for marker in PROPOSAL_MARKERS)


def problems() -> list[str]:
    """Every way the state and the registry can disagree."""
    found: list[str] = []
    writers: set[str] = set()
    registry = read_registry()
    for path in tool_files():
        name = path.stem
        writes = direct_writes(path)
        if not writes:
            continue
        writers.add(name)
        if proposes(path):
            found.append(
                f"{name}: proposes and writes at the same time ({', '.join(writes)}). "
                "One tool, two treatments: the user cannot tell which one they are looking at."
            )
            continue
        if name not in registry:
            found.append(
                f"{name}: writes a user's data directly ({', '.join(writes)}) and is not in "
                f"{DIRECT_WRITES_FILE.relative_to(ROOT)}. Either it proposes, or the reason it "
                "writes directly is written down — a tool that writes where another proposes is a "
                "rule nobody made."
            )
    for name in sorted(set(registry) - writers):
        found.append(
            f"{name}: listed in {DIRECT_WRITES_FILE.relative_to(ROOT)} but no longer writes "
            "directly. A stale entry hides the fact that the work was done, or that the tool "
            "is gone."
        )
    return found


@click.command()
@click.option("--list", "list_only", is_flag=True, help="print the direct writers and their reasons")
@click.pass_context
def cli(ctx: click.Context, list_only: bool) -> None:
    if list_only:
        for name, reason in sorted(read_registry().items()):
            click.echo(f"{name}: {reason}")
        raise ctx.exit(CLEAN)

    try:
        found = problems()
    except RegistryUnreadable as exc:
        click.echo(f"check-ai-write-tools: ERROR — {exc}", err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    if found:
        click.echo(f"check-ai-write-tools: {len(found)} finding(s)")
        for finding in found:
            click.echo(f"ERROR: {finding}")
        click.echo(
            "A tool that writes a user's data directly applies a change the user never saw. "
            "Propose it, or write down why it does not."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    writers = sum(1 for path in tool_files() if direct_writes(path))
    click.echo(
        f"check-ai-write-tools: OK — {writers} direct writer(s), all listed with a reason; "
        f"{len(read_registry())} entr(ies) in {DIRECT_WRITES_FILE.relative_to(ROOT)}"
    )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
