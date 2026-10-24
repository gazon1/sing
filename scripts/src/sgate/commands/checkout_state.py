"""sgate.commands.checkout_state — what is the checkout I am standing in carrying?

Why this exists
---------------
A checkout on a machine with several clones of this repository is shared
state. It can be carrying commits that `origin/main` does not have — another
session's work, or a fixture nobody meant to push — and nothing in the tree
says so. The agent standing in that checkout finds out at the worst moment:
when it commits, and the commit carries somebody else's 666 669 deleted lines
with it.

On 2026-10-24 that is exactly what happened. The canonical checkout carried 22
commits that `origin/main` did not have, over a snapshot that was thousands of
files behind. Its `AGENTS.md` was 45165 bytes — over the project-instruction
budget — so the harness truncated the instructions and the agent read a file
that ended in the middle of a sentence. Neither fact was visible from inside.

So the checkout says what it is carrying, before the work starts:

    sgate checkout-state            # what this checkout is, and what it carries
    sgate checkout-state --json     # the same, as JSON
    sgate checkout-state --strict   # exit 1 when the checkout is not a clean base

Which checkout
--------------
The answer is about **the checkout the caller stands in**, not the checkout the
installed `sgate` belongs to. Those differ exactly when it matters: `sgate` on
PATH is an editable install pointing at one clone, while the agent works in
another. So the root is resolved with `git rev-parse --show-toplevel` in the
current directory, and `sgate whereami` keeps answering the other question —
"whose code is this?" — for the gate tooling that needs it.

What "not a clean base" means
-----------------------------
A branch that is ahead of `origin/main` and not behind it is a feature branch:
it carries work that is not upstream yet, and that is the normal state of a
branch. A checkout that is **both** ahead and behind is carrying something
that is not upstream *and* not based on it — a foreign fixture, a stale
snapshot, a rebase that was never finished. That is the state in which a
commit carries somebody else's work, so `--strict` fails on it.

Nothing is refused
------------------
Like `sgate host-load`, this command reports and does not refuse: a checkout
that is carrying something is a fact, and the person who reads the report
decides. `--strict` is there for the scripts that want the fact as an exit
code.

Usage:
    sgate checkout-state            # the report
    sgate checkout-state --json     # {"root": ..., "carrying": [...], ...}
    sgate checkout-state --strict   # exit 1 when the checkout is not a clean base

Exit codes:
    0 = the state was printed (and, with --strict, the checkout is a clean base)
    1 = with --strict: the checkout is not a clean base
    2 = the current directory is not inside a git checkout
"""

from __future__ import annotations

import json as _json
import pathlib
import subprocess

import click

from sgate.commands.base import CLEAN, CONFIG_ERROR, register
from sgate.gatekit.budget import PROJECT_INSTRUCTIONS_BUDGET, PROJECT_INSTRUCTIONS_FILE
from sgate.gatekit.gitenv import tree_env

#: The ref a checkout is compared against.
UPSTREAM_REF = "origin/main"

#: How many carried commits are listed. A checkout carrying twenty commits
#: that `origin/main` does not have is a report; a checkout carrying two
#: thousand is a wall, and the count says how many there are.
MAX_LISTED = 20


def _git(*args: str, cwd: pathlib.Path) -> str | None:
    """The output of one git command, or None when it fails.

    Every question this command asks is allowed to have no answer: a checkout
    without an `origin/main`, a detached HEAD, a directory that is not a
    repository at all. A missing answer is reported as missing, and it is
    never an error of the command itself.

    The git environment is scrubbed first: the command may be called from
    inside a hook, and a git that inherits `GIT_DIR` answers about the
    repository the hook is about, not about the checkout the caller stands in.
    """
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
        env=tree_env(),
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def _root() -> pathlib.Path | None:
    """The root of the checkout the caller stands in."""
    here = pathlib.Path.cwd()
    top = _git("rev-parse", "--show-toplevel", cwd=here)
    if not top:
        return None
    return pathlib.Path(top)


def _worktrees(root: pathlib.Path) -> list[dict[str, str]]:
    """The worktrees of this repository, and what each one holds."""
    raw = _git("worktree", "list", "--porcelain", cwd=root)
    if not raw:
        return []
    entries: list[dict[str, str]] = []
    current: dict[str, str] = {}
    for line in raw.splitlines():
        if not line:
            if current:
                entries.append(current)
                current = {}
            continue
        key, _, value = line.partition(" ")
        if key == "worktree":
            current["path"] = value
        elif key == "HEAD":
            current["head"] = value[:8]
        elif key == "branch":
            current["branch"] = value.removeprefix("refs/heads/")
    if current:
        entries.append(current)
    return entries


def _instructions(root: pathlib.Path) -> dict[str, object]:
    """The project instructions of this checkout: their size, and their origin."""
    path = root / PROJECT_INSTRUCTIONS_FILE
    if not path.is_file():
        return {"bytes": 0, "budget": PROJECT_INSTRUCTIONS_BUDGET, "fits": True, "differs": False}
    size = len(path.read_bytes())
    differs = _git("diff", "--quiet", UPSTREAM_REF, "--", PROJECT_INSTRUCTIONS_FILE, cwd=root) is None
    return {
        "bytes": size,
        "budget": PROJECT_INSTRUCTIONS_BUDGET,
        "fits": size <= PROJECT_INSTRUCTIONS_BUDGET,
        "differs": differs,
    }


def _state(root: pathlib.Path) -> dict[str, object]:
    """Everything the report says about one checkout."""
    counts = _git("rev-list", "--left-right", "--count", f"{UPSTREAM_REF}...HEAD", cwd=root)
    behind = ahead = 0
    upstream_known = counts is not None
    if counts:
        parts = counts.split()
        if len(parts) == 2:
            behind, ahead = int(parts[0]), int(parts[1])

    carried: list[dict[str, str]] = []
    listing = _git(
        "log",
        "--format=%h%x1f%an%x1f%ad%x1f%s",
        "--date=short",
        f"-{MAX_LISTED}",
        f"{UPSTREAM_REF}..HEAD",
        cwd=root,
    )
    if listing:
        for line in listing.splitlines():
            parts = line.split("\x1f")
            if len(parts) == 4:
                carried.append(
                    {"sha": parts[0], "author": parts[1], "date": parts[2], "subject": parts[3]}
                )

    return {
        "root": str(root),
        "branch": _git("rev-parse", "--abbrev-ref", "HEAD", cwd=root) or "",
        "head": (_git("rev-parse", "--short", "HEAD", cwd=root) or "")[:8],
        "headSubject": _git("log", "-1", "--format=%s", cwd=root) or "",
        "upstream": UPSTREAM_REF,
        "upstreamKnown": upstream_known,
        "behind": behind,
        "ahead": ahead,
        "carrying": carried,
        "worktrees": _worktrees(root),
        "instructions": _instructions(root),
    }


def _report(state: dict[str, object]) -> str:
    """The report, as text."""
    instructions = state["instructions"]
    assert isinstance(instructions, dict)
    lines: list[str] = [
        f"checkout:   {state['root']}",
        f"branch:     {state['branch']}",
        f"head:       {state['head']} {state['headSubject']}",
    ]
    if state["upstreamKnown"]:
        lines.append(
            f"upstream:   {state['upstream']} is {state['behind']} commits ahead of this "
            f"checkout; this checkout is {state['ahead']} commits ahead of it"
        )
        if state["behind"] and state["ahead"]:
            lines.append(
                "            DIVERGED — this checkout carries commits that are not upstream "
                "and is not based on upstream. Do not commit here: use a worktree of your own."
            )
    else:
        lines.append(
            f"upstream:   {state['upstream']} is not known here — run `git fetch origin` before "
            f"trusting the numbers above"
        )

    carried = state["carrying"]
    assert isinstance(carried, list)
    if carried:
        lines.append("")
        lines.append(f"carrying {state['ahead']} commit(s) that {state['upstream']} does not have:")
        for entry in carried:
            assert isinstance(entry, dict)
            lines.append(
                f"  {entry['sha']}  {entry['date']}  {entry['author']}  {entry['subject']}"
            )

    worktrees = state["worktrees"]
    assert isinstance(worktrees, list)
    if worktrees:
        lines.append("")
        lines.append("worktrees:")
        for entry in worktrees:
            assert isinstance(entry, dict)
            lines.append(
                f"  {entry.get('path', '')}  {entry.get('branch', '(detached)')}  "
                f"{entry.get('head', '')}"
            )

    lines.append("")
    size = instructions["bytes"]
    budget = instructions["budget"]
    if size:
        verdict = "fits" if instructions["fits"] else "DOES NOT FIT — the harness truncates it"
        lines.append(f"instructions: {PROJECT_INSTRUCTIONS_FILE} is {size} bytes of {budget} — {verdict}")
        if instructions["differs"]:
            lines.append(
                f"             it differs from {state['upstream']}'s — a checkout on a branch "
                f"changes it on purpose; a checkout nobody owns changes it by accident"
            )
    else:
        lines.append(f"instructions: {PROJECT_INSTRUCTIONS_FILE} is missing here")
    return "\n".join(lines)


@click.command("checkout-state")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option(
    "--strict",
    is_flag=True,
    help="Exit 1 when the checkout is not a clean base (diverged, or the instructions do not fit).",
)
def checkout_state(emit_json: bool, strict: bool) -> None:
    """Print what the checkout the caller stands in is carrying."""
    root = _root()
    if root is None:
        message = f"{pathlib.Path.cwd()} is not inside a git checkout."
        if emit_json:
            click.echo(_json.dumps({"error": message}))
        else:
            click.echo(message)
        raise SystemExit(CONFIG_ERROR)

    state = _state(root)
    if emit_json:
        click.echo(_json.dumps(state, ensure_ascii=False))
    else:
        click.echo(_report(state))

    instructions = state["instructions"]
    assert isinstance(instructions, dict)
    diverged = bool(state["behind"]) and bool(state["ahead"])
    if strict and (diverged or not instructions["fits"]):
        raise SystemExit(1)
    raise SystemExit(CLEAN)


register(checkout_state)
