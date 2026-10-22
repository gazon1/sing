#!/usr/bin/env python3
"""check-stable-content-key.py — every destination has a content key, and the code says so.

The defect this catches
-----------------------
nav3 files a screen's saved state under its *content key*, and this repository
names the key after the record the screen shows (`task:t1`, `note:n1`). A
destination that has no key is a screen whose state is filed under nothing.

`AppDestination.stableContentKey()` is a `when` over the sealed hierarchy, and
Kotlin makes that `when` exhaustive — a new destination does not compile until
it is named there. The hole is one level down: the destinations that stand for
a record are grouped and delegated to `recordContentKey()`, and *that* `when`
ends in `else -> error("no content key for $this")`. A destination added to the
group compiles, compiles, and fails at runtime on the first screen that opens
it.

So the check is between the two: every destination the outer `when` delegates
must be named in the delegated `when`. The compiler cannot see it — the group
is a list of branches, and the delegation is a call — but the source can.

The same reasoning covers the other direction: a branch in the delegated
`when` that names a destination which no longer exists is a branch that will
never run, and it hides the fact that the destination it used to name is now
uncovered.

ADR `docs/decisions/2026-10-22-the-gate-suite-runs-this-checkout.md`.

Usage:
    python3 scripts/check-stable-content-key.py --check    # fail when it is stale
    python3 scripts/check-stable-content-key.py --json     # JSON instead of text

Exit codes:
  0 = every destination is covered
  1 = a destination is delegated without being named, or named without existing
"""
from __future__ import annotations

import re
import pathlib
from pathlib import Path
import sys

# The gate suite is a package of this repository. This script is run as
# `python3 scripts/check-<name>.py`, and the `sgate` the interpreter finds may
# be an editable install of *another* checkout — so this repository's own
# `scripts/src` goes in front of it. The same thing
# `scripts/check-gate-wiring.py` does for `workflow_gradle`.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run
from sgate.gatekit.cli import gate_command
from sgate.gatekit.findings import BLOCKING, Finding

ROOT = Path(__file__).resolve().parent.parent

#: The hierarchy and the keys.
NAV = Path("shared/src/commonMain/kotlin/com/singularity/todo/feature/nav")
DESTINATION_FILE = NAV / "AppDestination.kt"
KEY_FILE = NAV / "StableContentKey.kt"

#: A declaration of a nested type: `data object Plans`, `data class TasksGraph`.
#:
#: It is anchored to the start of a line because the prose of the file mentions
#: its own declarations: a reader that matches anywhere reads a sentence about
#: `data class Create` as a declaration of `Create`.
DECLARATION = re.compile(
    r"^[ \t]*data\s+(?:object|class)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)

#: The supertype a declaration names: `: AppDestination {`.
SUPERTYPE = re.compile(r":\s*AppDestination\b")

#: A branch of a `when`: `is AppDestination.Plans ->`.
BRANCH = re.compile(r"is\s+AppDestination\.([A-Za-z_][A-Za-z0-9_]*)")

#: The function that holds the outer `when`, and the one it delegates to.
OUTER = "internal fun AppDestination.stableContentKey(): String = when (this) {"
DELEGATE = "private fun AppDestination.recordContentKey(): String = when (this) {"

#: The call that means "the group goes to the other `when`".
DELEGATION = "recordContentKey()"


def _branches_with_right_hand_side(body: str) -> tuple[set[str], set[str]]:
    """The branches of a `when`, and the ones that delegate.

    A branch is `is AppDestination.X -> …`. What matters is the right-hand
    side: a branch that ends in the delegation call is a destination the other
    `when` has to name, and a branch that names its own key is not.

    The delegation is often the right-hand side of a *group*:

        is AppDestination.TasksGraph,
        is AppDestination.TasksByProject,
        -> recordContentKey()

    The call belongs to the last line of the group, and it belongs to all of
    it. So the group is read as the run of branches that are separated by
    commas and end at the delegation — a reader that looks at each branch on
    its own sees one delegated destination instead of nine.
    """
    names: set[str] = set()
    delegating: set[str] = set()
    heads = list(BRANCH.finditer(body))

    terminator = None
    for index, head in enumerate(heads):
        end = heads[index + 1].start() if index + 1 < len(heads) else len(body)
        if DELEGATION in body[head.end() : end]:
            terminator = index
            break
    if terminator is None:
        for head in heads:
            names.add(head.group(1))
        return names, delegating

    for index, head in enumerate(heads):
        names.add(head.group(1))

    # Walk back from the terminator over the commas of the group.
    for index in range(terminator, -1, -1):
        delegating.add(heads[index].group(1))
        if index == 0:
            break
        between = body[heads[index - 1].end() : heads[index].start()]
        if between.strip().strip(","):
            # Something other than a comma: this branch is its own branch.
            break
    return names, delegating


def _body(text: str, header: str) -> str:
    """The text of a `when` that starts with the given header.

    The body ends at the line that closes the `when` — the first line that is
    `}` at the start of a line, after the header.
    """
    start = text.find(header)
    if start < 0:
        return ""
    rest = text[start + len(header) :]
    end = rest.find("\n}")
    return rest if end < 0 else rest[:end]


def destinations(root: Path) -> set[str]:
    """The names of the destinations of the hierarchy.

    A destination is a declaration whose *header* names `AppDestination` as
    its supertype. The header is the text between the declaration and the
    opening brace of its body, which is what makes the read independent of
    how the parameters are written: a parameter list may span lines and may
    hold parentheses of its own, and a reader that counts parentheses breaks
    on the first declaration that does.
    """
    path = root / DESTINATION_FILE
    if not path.exists():
        return set()
    text = path.read_text(encoding="utf-8")
    names: set[str] = set()
    for match in DECLARATION.finditer(text):
        # The header ends at the first brace, the first closing brace, or the
        # first blank line — whichever comes first. A member of a sealed
        # interface may be declared without a body (`data class Create(…) :
        # TasksStartRoute`), and a reader that waits for the opening brace runs
        # past it into the next declaration.
        ends = [
            index
            for index in (
                text.find("{", match.end()),
                text.find("}", match.end()),
                text.find("\n\n", match.end()),
            )
            if index >= 0
        ]
        if not ends:
            continue
        header = text[match.end() : min(ends)]
        if SUPERTYPE.search(header):
            names.add(match.group(1))
    return names


def check(root: Path) -> list[Finding]:
    """The destinations whose key is delegated but not named."""
    findings: list[Finding] = []
    key_path = root / KEY_FILE
    if not key_path.exists():
        return [Finding(BLOCKING, f"{KEY_FILE.as_posix()} does not exist")]
    text = key_path.read_text(encoding="utf-8")

    outer = _body(text, OUTER)
    delegated = _body(text, DELEGATE)
    if not outer or not delegated:
        return [
            Finding(
                BLOCKING,
                f"{KEY_FILE.as_posix()}: the content-key `when` over the hierarchy was not found — "
                f"the gate cannot tell what is covered",
            )
        ]

    outer_branches, delegated_by_outer = _branches_with_right_hand_side(outer)
    delegated_branches = set(BRANCH.findall(delegated))

    # The group: the branches of the outer `when` whose right-hand side is the
    # delegation. A branch that names its own key is not delegated, and it is
    # not the hole this gate looks for.
    group = delegated_by_outer
    if DELEGATION not in outer:
        group = set()

    for name in sorted(group - delegated_branches):
        findings.append(
            Finding(
                BLOCKING,
                f"{KEY_FILE.as_posix()}: `AppDestination.{name}` is delegated to "
                f"`recordContentKey()` but has no branch there — it would fail at runtime "
                f"with \"no content key for ...\"",
            )
        )

    for name in sorted(delegated_branches - outer_branches):
        findings.append(
            Finding(
                BLOCKING,
                f"{KEY_FILE.as_posix()}: `recordContentKey()` names `AppDestination.{name}`, "
                f"which the outer `when` does not — the branch can never run",
            )
        )

    known = destinations(root)
    for name in sorted(outer_branches - known):
        findings.append(
            Finding(
                BLOCKING,
                f"{KEY_FILE.as_posix()}: the content key names `AppDestination.{name}`, "
                f"which the hierarchy in {DESTINATION_FILE.as_posix()} does not declare",
            )
        )
    for name in sorted(known - outer_branches):
        findings.append(
            Finding(
                BLOCKING,
                f"{KEY_FILE.as_posix()}: `AppDestination.{name}` has no content key — a "
                f"destination without a key is a screen whose state is filed under nothing",
            )
        )

    return findings


cli = gate_command(
    check,
    name="check-stable-content-key",
    title="every destination has a content key",
    default_root=ROOT,
    note=(
        "The compiler makes the `when` over the sealed hierarchy exhaustive, but it "
        "cannot see the delegation: a destination added to the group compiles and "
        "fails at runtime."
    ),
)


if __name__ == "__main__":
    raise SystemExit(run(cli))
