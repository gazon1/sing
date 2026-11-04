#!/usr/bin/env python3
"""check-skill-pointers.py — a skill reaches a sibling section once, under its own title.

What went wrong
---------------
The `singularity-todo-maestro-flows` skill pointed at the runbook's
`Seed fixtures, never type them` section three times: once under a body
heading that repeated the section's title, once in the middle of a paragraph,
once in the runbook pointer list. Nothing failed, because a pointer does not
fail. The reader pays for every extra one: three routes to one paragraph read
as three different places to go, and a heading that repeats the title it points
at says "here is the thing" and then hands the reader a second hop.

The neighbouring gates check that a link resolves (`check-doc-links.sh`), that
a path reference exists (`check-doc-dead-refs.py`), and that the skills
catalogue is current. None of them looks at the *shape* of a pointer, which is
what this gate does.

What this gate reports
----------------------
Two findings, over every `.agents/skills/*/SKILL.md`:

1. **the same fragment of the same sibling document is reached more than once**
   from one skill body. A skill body is one continuous argument; a section it
   reaches twice is a duplicated step in that argument.

2. **a heading in the skill is character-identical to a heading in a sibling
   it points at, while the section under it carries no material of its own.**
   The body under such a heading is only the pointer, so the heading restates
   the target's title and adds nothing — the reader could have been sent
   straight to the target.

The second finding compares the section body, not the titles. A heading that
repeats a sibling's title and carries its own content is fine, and this
repository has one: the test-helpers skill's `Three Test Shapes` section holds
a table that the sibling document does not. Making the title comparison alone
report that case would be a gate that cries wolf, so "material" is defined
below and the body decides.

What is not checked
-------------------
- **A link that does not resolve.** `check-doc-links.sh` owns that, and two
  gates reporting one defect is two places to fix it.
- **Links to anything but a sibling document** — the file next to the skill.
  A pointer into `docs/` or into another skill's directory is a different
  shape, and this gate does not claim to read it.
- **The pointer lists and the prose themselves.** The gate reads them; it does
  not say what they should say.

Not vacuous
-----------
If the corpus yields fewer than `MIN_POINTERS` sibling-fragment pointers, the
gate fails. A rule that finds nothing to check has proved nothing — and the
corpus carries three today, so a run that sees none is a run reading the wrong
tree.

Usage:
    python3 scripts/check-skill-pointers.py             # the report
    python3 scripts/check-skill-pointers.py --check     # fail when anything is found
    python3 scripts/check-skill-pointers.py --json      # the report, as one JSON object

Exit codes:
  0 = every skill reaches a sibling section once, and no heading shadows one
  1 = --check, and there are findings; or the corpus yields too few pointers
  2 = the skills directory could not be read
"""
from __future__ import annotations

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
SKILLS = ROOT / ".agents" / "skills"

#: The fewest sibling-fragment pointers the corpus must carry for the check to
#: mean anything. The corpus carries three today, all in the Maestro skill's
#: runbook list; a tree that yields fewer is a tree this gate is not reading.
MIN_POINTERS = 2

#: `## A heading`, with the optional closing hashes a Markdown author may write.
HEADING = re.compile(r"^(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$")

#: `[text](target)` — the whole link, so `sub` can remove it, with the target in
#: group 1. The optional quoted title is part of the link and not part of the
#: path.
LINK = re.compile(r"\[[^\]]*\]\([ \t]*([^)\s]+)(?:[ \t]+\"[^\"]*\")?[ \t]*\)")

#: A fence marker: everything inside is code, not prose, headings or links.
FENCE = re.compile(r"^(```|~~~)")

#: The punctuation a line may consist of and still say nothing.
ONLY_PUNCTUATION = re.compile(r"[\W_]+")


def body_lines(text: str) -> list[tuple[int, str]]:
    """The skill's lines after its frontmatter, with the file's own line numbers."""
    lines = text.splitlines()
    start = 0
    if lines and lines[0].strip() == "---":
        for index in range(1, len(lines)):
            if lines[index].strip() == "---":
                start = index + 1
                break
    return [(index + 1, lines[index]) for index in range(start, len(lines))]


def outside_fences(paired: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """The lines that are prose, not a fenced code block."""
    fenced = False
    kept: list[tuple[int, str]] = []
    for number, line in paired:
        if FENCE.match(line.strip()):
            fenced = not fenced
            continue
        if not fenced:
            kept.append((number, line))
    return kept


def sections(paired: list[tuple[int, str]]) -> list[tuple[str, int, list[tuple[int, str]]]]:
    """Every heading, its line, and the body under it.

    The body runs to the next heading of the same or a higher level, so a
    subsection is part of the section above it: a heading with a subsection
    under it is a heading whose section says more than its pointer.
    """
    kept = outside_fences(paired)
    heads = [
        (index, match)
        for index, (_, line) in enumerate(kept)
        if (match := HEADING.match(line))
    ]
    found: list[tuple[str, int, list[tuple[int, str]]]] = []
    for position, (index, match) in enumerate(heads):
        level = len(match.group(1))
        end = len(kept)
        for later_index, later_match in heads[position + 1 :]:
            if len(later_match.group(1)) <= level:
                end = later_index
                break
        found.append((match.group(2).strip(), kept[index][0], kept[index + 1 : end]))
    return found


def slug(text: str) -> str:
    """A heading as GitHub spells its anchor: lower case, punctuation dropped,
    spaces turned into hyphens.

    The space rule keeps runs, because GitHub does: `flow — the trick` becomes
    `flow--the-trick`, and dropping the run would make the anchor in a real
    pointer look like a different section.
    """
    lowered = text.strip().lower()
    stripped = re.sub(r"[^\w\s-]", "", lowered)
    return stripped.replace(" ", "-")


def pointers(
    skill: pathlib.Path, paired: list[tuple[int, str]]
) -> list[dict[str, object]]:
    """The relative links into a sibling document, with the file and line they sit on."""
    found: list[dict[str, object]] = []
    skills_dir = skill.parent.resolve()
    for number, line in outside_fences(paired):
        for match in LINK.finditer(line):
            target = match.group(1)
            if "://" in target or target.startswith(("#", "mailto:")):
                continue
            path, _, fragment = target.partition("#")
            if not path:
                continue
            sibling = (skill.parent / path).resolve()
            if sibling.parent != skills_dir or not sibling.is_file():
                continue
            found.append(
                {
                    "file": sibling.name,
                    "fragment": fragment or None,
                    "line": number,
                }
            )
    return found


def sibling_headings(skill: pathlib.Path, name: str, cache: dict[str, set[str]]) -> set[str]:
    """The heading texts of the sibling document `name`, read once per skill."""
    if name not in cache:
        document = skill.parent / name
        cache[name] = {
            heading
            for heading, _, _ in sections(body_lines(document.read_text(encoding="utf-8")))
        }
    return cache[name]


def has_material(body: list[tuple[int, str]]) -> bool:
    """Whether a section says anything beyond the links it carries.

    The links are removed, then every remaining line has its list and table
    markers taken off. A line that is left is material — a sentence, a table
    cell, a bullet's gloss, a subsection. A line that is only punctuation is
    not: a section whose whole body is a dash says nothing of its own.
    """
    text = LINK.sub("", "\n".join(line for _, line in body))
    for raw in text.splitlines():
        line = raw.strip().lstrip("-*+>|").strip()
        if line and not ONLY_PUNCTUATION.fullmatch(line):
            return True
    return False


def duplicate_findings(skill: pathlib.Path, found: list[dict[str, object]]) -> list[dict]:
    """The sibling sections one skill body reaches more than once."""
    groups: dict[tuple[str, str], list[int]] = {}
    for pointer in found:
        fragment = pointer["fragment"]
        if not fragment:
            continue
        groups.setdefault((str(pointer["file"]), str(fragment)), []).append(
            int(pointer["line"])
        )

    findings: list[dict] = []
    for (name, fragment), lines in sorted(groups.items()):
        if len(lines) < 2:
            continue
        where = ", ".join(str(line) for line in lines)
        findings.append(
            {
                "kind": "duplicate-pointer",
                "skill": skill.parent.name,
                "file": name,
                "fragment": fragment,
                "lines": lines,
                "detail": (
                    f"{skill.parent.name}: {name}#{fragment} is reached {len(lines)} "
                    f"times (lines {where}) — one skill body reaches one sibling "
                    f"section once"
                ),
            }
        )
    return findings


def shadow_findings(skill: pathlib.Path) -> list[dict]:
    """The headings that repeat a pointed-at section's title and only point at it."""
    paired = body_lines(skill.read_text(encoding="utf-8"))
    cache: dict[str, set[str]] = {}
    findings: list[dict] = []

    for heading, line, body in sections(paired):
        if not heading or has_material(body):
            continue
        for pointer in pointers(skill, body):
            name = str(pointer["file"])
            fragment = pointer["fragment"]
            # The body has to reach the section it collides with: the sibling
            # document itself, or the anchor its own heading spells.
            if fragment is not None and fragment != slug(heading):
                continue
            if heading not in sibling_headings(skill, name, cache):
                continue
            section = f"{name}#{fragment}" if fragment else name
            findings.append(
                {
                    "kind": "shadow-heading",
                    "skill": skill.parent.name,
                    "file": name,
                    "fragment": fragment,
                    "heading": heading,
                    "line": line,
                    "detail": (
                        f"{skill.parent.name}: heading {heading!r} (line {line}) repeats "
                        f"the title of {section} and the section under it carries only "
                        f"the pointer — the two are one section, not two"
                    ),
                }
            )
            break
    return findings


def scan(skills_dir: pathlib.Path) -> tuple[list[dict], int, int]:
    """The findings, the number of skills read, and the number of pointers checked."""
    skills = sorted(skills_dir.glob("*/SKILL.md"))
    findings: list[dict] = []
    checked = 0
    for skill in skills:
        paired = body_lines(skill.read_text(encoding="utf-8"))
        found = pointers(skill, paired)
        checked += sum(1 for pointer in found if pointer["fragment"])
        findings.extend(duplicate_findings(skill, found))
        findings.extend(shadow_findings(skill))
    return findings, len(skills), checked


@click.command()
@click.option(
    "--skills",
    "skills_dir",
    type=click.Path(path_type=pathlib.Path),
    default=SKILLS,
    help="The skills directory to read (default: .agents/skills).",
)
@click.option("--quiet", is_flag=True, help="Findings only, for CI.")
@click.option("--check", is_flag=True, help="Fail when anything is found.")
@click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
@click.pass_context
def cli(
    ctx: click.Context,
    skills_dir: pathlib.Path,
    quiet: bool,
    check: bool,
    as_json: bool,
) -> None:
    """Report the pointers that repeat a section, and the headings that shadow one."""
    try:
        findings, skills, checked = scan(skills_dir)
    except OSError as exc:
        click.echo(f"check-skill-pointers: cannot read the skills: {exc}")
        raise ctx.exit(IO_ERROR)

    # A corpus this gate cannot read is not a clean corpus, and it is reported
    # before any verdict — including the JSON one, which must stay one object.
    if skills == 0:
        click.echo(f"ERROR: {skills_dir} holds no skill — this gate is reading the wrong tree")
        raise ctx.exit(VALIDATION_FAILURE)

    if checked < MIN_POINTERS:
        click.echo(
            f"ERROR: {checked} sibling-section pointer(s); a check that finds fewer "
            f"than {MIN_POINTERS} proves nothing"
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if as_json:
        click.echo(
            json.dumps(
                {"findings": findings, "count": len(findings), "skills": skills},
                indent=2,
            )
        )
    else:
        if not quiet:
            click.echo(
                f"check-skill-pointers: {skills} skills, {checked} pointers into a "
                f"sibling document's section"
            )
        for finding in findings:
            click.echo(f"  [{finding['kind']}] {finding['detail']}")
        if not quiet:
            click.echo()
            click.echo(
                f"{len(findings)} finding(s). A section is reached once; a heading "
                f"that repeats a pointed-at title carries its own content instead."
            )

    if findings and check:
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
