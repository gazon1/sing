#!/usr/bin/env python3
"""check-maestro-behaviour-ownership.py — the runbook owns the app behaviour facts.

The defect this gate holds closed
---------------------------------
`.agents/skills/singularity-todo-maestro-flows/` is a pair of documents. `SKILL.md`
teaches a reader how to write a flow; `diagnosing-failures.md` is the runbook, and
its section `## App behaviour facts flows encode` is the one home for what the app
does at a screen a flow drives.

The two documents drifted, twice, and nothing noticed:

* the skill body said the notes quick-add lands on the note's preview *or* in the
  editor "run to run", and the product always opens the editor
  (`NotesUiEvent.NavigateToEditor`);
* the skill body said the saved-view editor's Save leaves the editor open, and the
  product pops back to the list (`SavedAgendaScreen` navigates back once the view
  is written).

Both sentences were plausible and both sat in the file for as long as the text
existed. The same section also carried a tag, `sheet_item_<label>`, that no screen
answers to any more, and a screen, `SyncConfigScreen`, that had been renamed — a
stale identifier in the *authoritative* place, which is worse than a stale
sentence anywhere else, because every flow written from it addresses nothing.

What this gate checks
---------------------
1. **The skill body does not restate a behaviour the runbook owns.** A short list
   of markers — a save button on a screen, a quick-add destination, a
   preview-versus-editor statement, an unreachable-feature list, a save that does
   not navigate — is scanned over `SKILL.md`, and a match names the line.
2. **Every product identifier the runbook section names still exists.** The
   section's backticked code spans are read, and each is looked for in the product
   sources (`shared`, `shared-ui`, `androidApp`, `desktopApp`).

What it deliberately does not catch
-----------------------------------
The markers are anchored on the *claim*, not on a word. `inputText` appending to a
pre-filled field is a Maestro quirk and stays in the skill; so does
`hideKeyboard`'s "it saves and navigates away". A
backticked word that is prose rather than a code name — `back` — is not a claim
about the product, and is skipped: only a span that contains `_` or starts with a
capital is read as a declaration.

A tag can be parameterised, and then the family is what the source declares:
`note_action_edit` is `note_action_` plus a slug. The gate accepts the literal or
its family prefix, and `task_item_<slug>` is read as the family `task_item_`.

Being a text comparison, this is a floor and not a proof: a correctly-named tag in
a sentence that is wrong still passes. The device says whether a behaviour is
right; this gate says the two documents and the source still agree about where it
is written down.

Usage:
    python3 scripts/check-maestro-behaviour-ownership.py            # report
    python3 scripts/check-maestro-behaviour-ownership.py --check    # fail on drift
    python3 scripts/check-maestro-behaviour-ownership.py --json     # JSON
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

# This checkout's package goes in front of whatever `sgate` the interpreter
# finds: an editable install of a sibling worktree is a different tree under the
# same name (`scripts/check-gate-tooling-origin.py` is the gate for that).
_SCRIPTS_SRC = str(Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run
from sgate.gatekit.cli import gate_command
from sgate.gatekit.findings import BLOCKING, Finding

#: The repository, located from the script and never from the installed package.
ROOT = Path(__file__).resolve().parent.parent

#: The document that keeps the authoring rules.
SKILL = Path(".agents/skills/singularity-todo-maestro-flows/SKILL.md")

#: The document that owns the app-behaviour facts.
RUNBOOK = Path(".agents/skills/singularity-todo-maestro-flows/diagnosing-failures.md")

#: The section of the runbook that owns them. Its heading is the contract: a
#: renamed section is a moved home, which this gate reports rather than follows.
SECTION = "## App behaviour facts flows encode"

#: Where a product identifier may be declared.
PRODUCT_SOURCES = ("shared", "shared-ui", "androidApp", "desktopApp")


@dataclass(frozen=True)
class Marker:
    """One way the skill body can restate a behaviour the runbook owns."""

    #: What the claim is about — the word the finding reports.
    kind: str
    #: The claim's shape. Anchored on the claim, never on a bare word: the
    #: authoring rules quote the same vocabulary ("it saves and navigates away").
    pattern: re.Pattern[str]
    #: Why the behaviour belongs to the runbook rather than to the skill.
    why: str


#: The markers. Data, so that a behaviour this pair learns to own is a line
#: here and not a second rule in the program.
MARKERS: tuple[Marker, ...] = (
    Marker(
        "save-button-on-a-screen",
        re.compile(r"\bno save button\b|`task_editor_save`\s+exists only", re.IGNORECASE),
        "where a save button is — or is not — is what the screen does, not how to write a step",
    ),
    Marker(
        "quick-add-destination",
        re.compile(r"\bquick-add\b", re.IGNORECASE),
        "where the quick-add lands is the behaviour that drifted, and it is stated once",
    ),
    Marker(
        "preview-versus-editor",
        re.compile(r"\bpreview\b", re.IGNORECASE),
        "which screen a tap opens is a behaviour, and two statements of it drift",
    ),
    Marker(
        "unreachable-feature-list",
        re.compile(
            r"modelled but unreachable|composed by nothing|no control that dispatches",
            re.IGNORECASE,
        ),
        "a list of features no control reaches is a fact about the product, and it goes stale",
    ),
    Marker(
        "save-that-does-not-navigate",
        re.compile(r"\bstays open\b|\bdoes not navigate\b|\bdo not navigate\b", re.IGNORECASE),
        "where a save leaves the screen is the claim that went false twice",
    ),
)

#: A backticked code span in the runbook.
CODE_SPAN = re.compile(r"`([^`]+)`")

#: What a code name looks like, once the placeholder is cut off.
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def restatements(root: Path) -> list[Finding]:
    """The behaviours the skill body states, which the runbook owns."""
    skill = root / SKILL
    if not skill.is_file():
        return [
            Finding(
                BLOCKING,
                f"{SKILL.as_posix()}: the skill body this gate reads is not there, "
                "so nothing was checked",
            )
        ]
    findings: list[Finding] = []
    for number, line in enumerate(skill.read_text(encoding="utf-8").splitlines(), start=1):
        for marker in MARKERS:
            if marker.pattern.search(line):
                findings.append(
                    Finding(
                        BLOCKING,
                        f"{SKILL.as_posix()}: line {number} restates an app behaviour "
                        f"the runbook owns ({marker.kind}) — {marker.why}: {line.strip()}",
                    )
                )
    return findings


def section(text: str) -> tuple[str, int]:
    """The runbook's behaviour section, and the line number it starts on.

    The section ends at the next second-level heading: a behaviour added after
    the probe recipe is not in the section, and reading it as if it were would
    check prose that belongs to another owner.
    """
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.strip() != SECTION:
            continue
        body: list[str] = []
        for following in lines[index + 1 :]:
            if following.startswith("## "):
                break
            body.append(following)
        return "\n".join(body), index + 2
    return "", 0


def identifier_of(span: str) -> str:
    """The identifier a code span names, with a placeholder cut off.

    `task_item_<slug>` names the family `task_item_`: what sits in the angle
    brackets is the flow's own value, and the source declares the family.
    """
    if "<" in span:
        return span.split("<", 1)[0]
    return span.strip()


def is_code_name(identifier: str) -> bool:
    """Whether a span names a declaration rather than a word of prose.

    `back` is a key on the device, `Discard` is a button's copy, `inputText` is a
    Maestro command — each is backticked in the documents and none is a name the
    product declares.
    """
    if not IDENTIFIER.match(identifier):
        return False
    return "_" in identifier or identifier[0].isupper()


def product_source(root: Path) -> str:
    """Every Kotlin file of the product, as one text.

    Tags, events and classes are all declared in Kotlin, so one index answers
    both "does this tag exist" and "does this type exist".
    """
    chunks: list[str] = []
    for name in PRODUCT_SOURCES:
        for path in sorted((root / name).rglob("*.kt")):
            chunks.append(path.read_text(encoding="utf-8", errors="replace"))
    return "\n".join(chunks)


def declared(identifier: str, source: str) -> bool:
    """Whether the product declares the identifier or the family it belongs to."""
    if identifier in source:
        return True
    if identifier.endswith("_"):
        return False
    family, _, _ = identifier.rpartition("_")
    return bool(family) and family + "_" in source


def stale_identifiers(root: Path) -> list[Finding]:
    """The identifiers the runbook section names, which the product does not declare."""
    runbook = root / RUNBOOK
    if not runbook.is_file():
        return [
            Finding(
                BLOCKING,
                f"{RUNBOOK.as_posix()}: the runbook this gate reads is not there, "
                "so nothing was checked",
            )
        ]
    text, first_line = section(runbook.read_text(encoding="utf-8"))
    if not text:
        return [
            Finding(
                BLOCKING,
                f"{RUNBOOK.as_posix()}: the section `{SECTION[3:]}` is not there — the "
                "behaviour facts have no home, so nothing was checked",
            )
        ]

    source = product_source(root)
    findings: list[Finding] = []
    seen: set[str] = set()
    for offset, line in enumerate(text.splitlines()):
        for span in CODE_SPAN.findall(line):
            identifier = identifier_of(span)
            if not is_code_name(identifier) or identifier in seen:
                continue
            seen.add(identifier)
            if declared(identifier, source):
                continue
            findings.append(
                Finding(
                    BLOCKING,
                    f"{RUNBOOK.as_posix()}: line {first_line + offset} names "
                    f"`{identifier}`, which the product sources do not declare "
                    f"({', '.join(PRODUCT_SOURCES)}) — a flow written from this fact "
                    "addresses nothing, and this is the place it was promised from",
                )
            )
    if not seen:
        findings.append(
            Finding(
                BLOCKING,
                f"{RUNBOOK.as_posix()}: the behaviour section names no identifier at "
                "all, so the staleness rule above checked nothing",
            )
        )
    return findings


def mismatches(root: Path) -> list[Finding]:
    """Every way the two documents and the source disagree."""
    return restatements(root) + stale_identifiers(root)


cli = gate_command(
    mismatches,
    name="check-maestro-behaviour-ownership",
    title="the Maestro runbook as the home of the app behaviour facts",
    default_root=ROOT,
    note=(
        "The skill body keeps the authoring rules; what the app does at a screen "
        "is stated once, in the runbook's behaviour section, and checked against "
        "the source here."
    ),
)


if __name__ == "__main__":
    raise SystemExit(run(cli))
