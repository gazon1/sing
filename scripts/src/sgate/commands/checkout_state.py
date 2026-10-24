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
A branch that is ahead of `origin/main` is doing its job: it carries work that
is not upstream yet, and that is the normal state of a branch. The command
does not guess whether that work is the reader's — it lists the commits, with
their author and date, and asks.

A base that is **many commits behind** is a different thing: the tree the
reader is looking at is not the tree the work will be based on, and the work
will need a rebase before it merges. That is what `--strict` fails on, and
the threshold is written down in the module (`STALE_BASE_COMMITS`).

The tree itself is reported too — one line of `git diff --shortstat` — because
"12127 files changed" and "3 files changed" are different states of a
checkout, and the second is what a working branch looks like.

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
    0 = the state was printed (and, with --strict, the base is not stale)
    1 = with --strict: the base is stale, or the instructions do not fit
    3 = the current directory is not inside a git checkout
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

#: How many commits behind `origin/main` make a checkout's base stale.
#:
#: One commit behind is ordinary: `main` moves in parallel tranches, and a
#: branch made yesterday is behind today. Many commits behind means the work
#: will need a rebase before it merges, and the tree the agent is looking at
#: is not the tree the work will be based on.
#:
#: The threshold is a judgement, and it is written down here so that it can
#: be argued with rather than discovered.
STALE_BASE_COMMITS = 10


def _git_code(*args: str, cwd: pathlib.Path) -> int | None:
    """The exit code of one git command, or None when it could not run.

    The exit code is the answer for the questions whose answer is a difference
    rather than a text: `git diff --quiet` exits 1 when the files differ and
    128 when the ref does not exist, and those are not the same fact.
    """
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
        env=tree_env(),
    )
    return result.returncode


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
    """The project instructions of this checkout: their size, and their origin.

    `differs` is a fact about the file or it is unknown: `git diff --quiet`
    exits 1 when the files differ and 128 when the ref is not there, and a
    missing ref is not a difference.
    """
    path = root / PROJECT_INSTRUCTIONS_FILE
    if not path.is_file():
        return {"bytes": 0, "budget": PROJECT_INSTRUCTIONS_BUDGET, "fits": True, "differs": None}
    size = len(path.read_bytes())
    code = _git_code("diff", "--quiet", UPSTREAM_REF, "--", PROJECT_INSTRUCTIONS_FILE, cwd=root)
    differs = True if code == 1 else (False if code == 0 else None)
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

    tree = _git("diff", "--shortstat", UPSTREAM_REF, "HEAD", cwd=root) or ""
    upstream_date = _git("log", "-1", "--format=%cs", UPSTREAM_REF, cwd=root) or ""

    return {
        "root": str(root),
        "branch": _git("rev-parse", "--abbrev-ref", "HEAD", cwd=root) or "",
        "head": (_git("rev-parse", "--short", "HEAD", cwd=root) or "")[:8],
        "headSubject": _git("log", "-1", "--format=%s", cwd=root) or "",
        "upstream": UPSTREAM_REF,
        "upstreamKnown": upstream_known,
        "upstreamDate": upstream_date,
        "behind": behind,
        "ahead": ahead,
        "carrying": carried,
        "tree": tree,
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
        date = state.get("upstreamDate")
        if date:
            lines.append(
                f"            counted against the local {state['upstream']} of {date} — "
                f"the numbers are only as fresh as that ref: `git fetch origin` first"
            )
        if state["behind"] >= STALE_BASE_COMMITS:
            lines.append(
                f"            STALE BASE — this checkout is {state['behind']} commits behind "
                f"{state['upstream']}. The tree you are looking at is not the tree the work "
                f"will be based on: fetch and rebase before you build on it."
            )
        tree = state.get("tree")
        if tree:
            lines.append(f"            tree vs {state['upstream']}: {tree}")
    else:
        lines.append(
            f"upstream:   {state['upstream']} is not known here — run `git fetch origin` before "
            f"trusting the numbers"
        )

    carried = state["carrying"]
    assert isinstance(carried, list)
    if carried:
        lines.append("")
        lines.append(
            f"carrying {state['ahead']} commit(s) that {state['upstream']} does not have "
            f"(are they yours?):"
        )
        for entry in carried:
            assert isinstance(entry, dict)
            lines.append(
                f"  {entry['sha']}  {entry['date']}  {entry['author']}  {entry['subject']}"
            )
        if state["ahead"] > len(carried):
            lines.append(f"  … and {state['ahead'] - len(carried)} more")

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
        differs = instructions["differs"]
        if differs is True:
            lines.append(
                f"             it differs from {state['upstream']}'s — a checkout on a branch "
                f"changes it on purpose; a checkout nobody owns changes it by accident"
            )
        elif differs is None:
            lines.append(
                f"             it cannot be compared with {state['upstream']}'s — the ref is "
                f"not here, so the comparison is unknown rather than equal"
            )
    else:
        lines.append(f"instructions: {PROJECT_INSTRUCTIONS_FILE} is missing here")
    return "\n".join(lines)


@click.command("checkout-state")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option(
    "--strict",
    is_flag=True,
    help=(
        "Exit 1 when the base is stale (at least STALE_BASE_COMMITS behind "
        "origin/main), or the project instructions do not fit the budget."
    ),
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
    # The strict answer is about the base, not about the commits: a branch that
    # is ahead of `origin/main` is a branch that is doing its job. A branch
    # that is many commits behind is a branch whose work will need a rebase
    # before it merges.
    stale_base = int(state["behind"]) >= STALE_BASE_COMMITS
    if strict and (stale_base or not instructions["fits"]):
        raise SystemExit(1)
    raise SystemExit(CLEAN)


register(checkout_state)
