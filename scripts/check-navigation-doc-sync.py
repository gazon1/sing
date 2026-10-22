#!/usr/bin/env python3
"""check-navigation-doc-sync.py — the navigation doc must say what the code does.

The defect this catches
-----------------------
`docs/navigation.md` is a pointer: an agent that reads it learns which route
opens which screen, which deep link opens what, and how the nested graphs are
seeded. It is pointed at from the nav3 skill, so a false row in it is worse
than no row — the agent builds on it.

Two rows of that table went false on 2026-10-22 and nothing noticed. The
deep-link row for `singularity://view/{viewId}` still named `SavedAgendaEdit`
after the code moved to `SavedAgendaResults`, and the platform-differences row
still said the desktop tasks stack pushes the `Detail` start onto the stack
after the code changed to seed the stack with the requested route itself.

Why the existing gates did not catch it
---------------------------------------
`doc links` checks that the links in the docs resolve. It cannot see that a
*claim* about behaviour has stopped being true — the link is fine, the sentence
is wrong. This gate reads the claim and the code and compares them.

What it compares
----------------
  * **Deep links.** Every `singularity://…` row of the deep-link table names
    the types it opens. Those types must appear in the matcher that implements
    the link, and every link the code declares must have a row.
  * **The tasks stack.** A row or a paragraph that says the tasks stack is
    pushed onto must agree with the code: the platform assemblers seed the
    stack with the requested route, they do not push a second route onto it.

Both are text comparisons, so they are a floor and not a proof: they catch a
claim that names a type the code does not name, and a claim of a push the code
does not make. The runtime tests are what prove the behaviour.

Two severities, because the two claims are not equally certain
------------------------------------------------------------
A **deep-link row** names a type. If the code does not name that type, the row
is false — a fact, and the gate blocks on it.

The **push claim** is read out of prose: the gate looks for a paragraph that
mentions both the task stack and a push. That is a guess about a sentence, and
a sentence that says "the tasks stack is seeded, and the push policy is Push"
would be read as a claim of a push even though it is not one. So that finding
is **advisory**: it is printed, and it does not fail the build. A gate that
blocks on a guess about prose blocks on the wrong thing, and the cost of that
is a build that is red for a sentence nobody has to fix.

Usage:
    scripts/check-navigation-doc-sync.py            # human-readable report
    scripts/check-navigation-doc-sync.py --quiet    # findings only, for CI
    scripts/check-navigation-doc-sync.py --check    # fail when anything is found
    scripts/check-navigation-doc-sync.py --json     # JSON instead of text

Exit code is 0 by default and 1 under `--check` when anything is found.
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
from sgate.gatekit.findings import ADVISORY, Finding
from sgate.gatekit.rules import LinkTableRule, check_link_table

CLEAN = 0
VALIDATION_FAILURE = 1

#: A finding that states a fact the code contradicts. It fails the build.
BLOCKING = "blocking"

#: A finding that states a guess about prose. It is reported, and it does not
#: fail the build — see the module docstring.
ADVISORY = "advisory"

#: The rule: which table of which document, and the naming convention that
#: ties a row of it to the code. It is data, so that a document that moves or
#: a convention that changes is a change to a file, not to a program.
RULE_PATH = Path(__file__).resolve().parent.parent / "config" / "gates" / "navigation-doc-sync.yaml"

#: The tree to check. The pre-push hook runs this script against a throwaway
#: checkout of the tree being pushed, while the script itself comes from the
#: repository the hook runs in, so the root is an argument and not a derivation.
DEFAULT_ROOT = Path(__file__).resolve().parent.parent

#: The navigation doc, relative to the root.
DOC_RELATIVE = Path("docs/navigation.md")

#: The sources that declare a deep link, and the sources that match one.
SOURCES_RELATIVE = Path("shared/src/commonMain")

#: The first cell of a deep-link row: `| `singularity://task/{id}` | …`.
LINK_CELL = re.compile(r"`[^`]*://[^`]+`")

#: Where the tasks stack is created — the platform assemblers own the seed.
TASKS_ENTRY_MARKER = "entry<AppDestination.TasksGraph>"

#: What a push onto a nav3 stack looks like.
STACK_PUSH = re.compile(r"\.add\(|\.push\(|\.plus\(")


def doc_text(root: Path) -> str:
    doc = root / DOC_RELATIVE
    return doc.read_text(encoding="utf-8") if doc.exists() else ""


def tasks_stack_is_pushed(root: Path) -> bool:
    """Whether the code pushes a route onto the tasks stack instead of seeding it."""
    assemblers = [
        root / "shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt",
        root / "shared/src/androidMain/kotlin/com/singularity/todo/feature/nav/AndroidNavEntries.kt",
    ]
    for path in assemblers:
        if not path.exists():
            continue
        source = path.read_text(encoding="utf-8")
        marker = source.find(TASKS_ENTRY_MARKER)
        if marker < 0:
            continue
        following = source.find("\n    entry<", marker)
        block = source[marker : following if following > 0 else len(source)]
        if STACK_PUSH.search(block):
            return True
    return False


def doc_blocks(text: str) -> list[str]:
    """The doc's blocks: every table row on its own, every paragraph as one."""
    blocks: list[str] = []
    paragraph: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            if paragraph:
                blocks.append("\n".join(paragraph))
                paragraph = []
            continue
        if stripped.startswith("|"):
            if paragraph:
                blocks.append("\n".join(paragraph))
                paragraph = []
            blocks.append(stripped)
            continue
        paragraph.append(stripped)
    if paragraph:
        blocks.append("\n".join(paragraph))
    return blocks


def mismatches(root: Path) -> list[Finding]:
    """The claims the navigation doc makes that the code does not support."""
    findings: list[Finding] = []
    rule = LinkTableRule.from_file(RULE_PATH)
    findings += check_link_table(root, rule)

    text = doc_text(root)
    doc_relative = DOC_RELATIVE.as_posix()
    pushed = tasks_stack_is_pushed(root)
    for block in doc_blocks(text):
        lowered = block.lower()
        if "task" not in lowered or "push" not in lowered:
            continue
        if pushed:
            continue
        findings.append(
            Finding(ADVISORY,
                    f"{doc_relative}: the doc says the tasks stack is pushed onto, "
                    f"and the code seeds the tasks stack: {block.splitlines()[0][:120]}"),
        )

    return findings


cli = gate_command(
    mismatches,
    name="check-navigation-doc-sync",
    title="the navigation doc against the code",
    default_root=DEFAULT_ROOT,
    note=(
        f"{DOC_RELATIVE.as_posix()} is a pointer: an agent reads it to learn what "
        "opens what. A row that names a type the code does not name sends it the wrong way."
    ),
)


if __name__ == "__main__":
    raise SystemExit(run(cli))
