#!/usr/bin/env python3
"""check-doc-sizes.py — enforce the size budgets for agent-facing docs.

Budgets follow the Agent Skills spec and the industry rules of thumb:
  - AGENTS.md   <= 240 lines  — see AGENTS_MAX below for why not 250
  - SKILL.md    <= 500 lines  — agentskills.io: "Keep your main SKILL.md under 500 lines"
  - description <= 1024 chars — agentskills.io spec hard cap on the description field
  - DIGEST.md   <= 120 lines  — the always-loaded orientation layer
  - DIGEST-full.md <= 3000    — the complete index, opened on demand

AGENTS_MAX is 240, set on 2026-10-05 from a measurement rather than kept as a
round number. `AGENTS.md` had reached 250/250 — zero headroom — and the reason it
got there is worth recording: the cap was 250, the file grew to meet it, and
nothing else objected along the way. A budget that is *at* its limit is not a ratchet,
it is a wall, and the cheapest way through a wall is deleting something true.

So: the two blocks that were pure lookup tables moved out (the expect/actual
port inventory to `docs/PLATFORM-REFERENCE.md`, and a paragraph that restated
`openspec/specs/test-execution-integrity/spec.md` down to a pointer), which
brought the file to 228. The cap went to 240 — roughly 5% above the measurement,
so ordinary work fits without a conversation and a slow doubling is still caught.

What stayed in `AGENTS.md` is what an agent must not get wrong on any task: the
rules. What moved out is what an agent needs when it does a particular one.

Exits non-zero if any budget is exceeded, so it can gate a PR.
Usage: python3 scripts/check-doc-sizes.py [--warn-only]
"""
from __future__ import annotations

import pathlib
import re
import sys

import click
from sgate.commands.base import run

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKILLS_DIR = ROOT / ".agents" / "skills"
AGENTS_MD = ROOT / "AGENTS.md"
DIGEST_MD = ROOT / "docs" / "decisions" / "DIGEST.md"
ARCHITECTURE_MD = ROOT / "ARCHITECTURE.md"
PROGRESS_MD = ROOT / "PROGRESS.md"
DIGEST_FULL_MD = ROOT / "docs" / "decisions" / "DIGEST-full.md"

AGENTS_MAX = 240
SKILL_MAX = 500
DESCRIPTION_MAX = 1024
DIGEST_MAX = 120  # the always-loaded orientation layer; matches MAX_ORIENTATION_LINES
DIGEST_FULL_MAX = 3000  # matches MAX_DIGEST_FULL_LINES in refresh-decisions-digest.py
ARCHITECTURE_MAX = 600
PROGRESS_MAX = 300

CLEAN = 0
VALIDATION_FAILURE = 1


def count_lines(path: pathlib.Path) -> int:
    with path.open(encoding="utf-8", errors="replace") as fh:
        return sum(1 for _ in fh)


def frontmatter_of(path: pathlib.Path) -> str:
    """Return the raw YAML frontmatter block, or '' when there is none."""
    text = path.read_text(encoding="utf-8", errors="replace")
    if not text.startswith("---"):
        return ""
    end = text.find("\n---", 3)
    return text[3:end] if end != -1 else ""


def description_of(fm: str) -> str | None:
    """Extract the description value; supports single-line and quoted values."""
    match = re.search(r"^description:\s*(.*)$", fm, re.M)
    if not match:
        return None
    value = match.group(1).strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    return value


@click.command()
@click.option(
    "--warn-only",
    is_flag=True,
    help="report violations but always exit 0",
)
@click.pass_context
def cli(ctx: click.Context, warn_only: bool) -> None:
    violations: list[tuple[str, str]] = []

    def check(path: pathlib.Path, limit: int, unit: str) -> None:
        if not path.exists():
            return
        n = count_lines(path)
        if n > limit:
            rel = path.relative_to(ROOT)
            violations.append((str(rel), f"{n} {unit} (max {limit})"))

    check(AGENTS_MD, AGENTS_MAX, "lines")
    check(DIGEST_MD, DIGEST_MAX, "lines")
    check(DIGEST_FULL_MD, DIGEST_FULL_MAX, "lines")
    check(ARCHITECTURE_MD, ARCHITECTURE_MAX, "lines")
    check(PROGRESS_MD, PROGRESS_MAX, "lines")

    for skill_md in sorted(SKILLS_DIR.glob("*/SKILL.md")):
        rel = skill_md.parent.name
        n = count_lines(skill_md)
        if n > SKILL_MAX:
            violations.append((rel, f"SKILL.md {n} lines (max {SKILL_MAX}) — consider a router + leaf split"))
        desc = description_of(frontmatter_of(skill_md))
        if desc is not None and len(desc) > DESCRIPTION_MAX:
            violations.append((rel, f"description {len(desc)} chars (max {DESCRIPTION_MAX})"))

    if violations:
        click.echo("Doc size budgets exceeded:\n")
        for name, detail in violations:
            click.echo(f"  {name}: {detail}")
        click.echo(
            "\nFix: trim the file, or split an oversized skill into a router SKILL.md "
            "plus sibling leaf files loaded only when needed."
        )
        if warn_only:
            click.echo("\n(--warn-only: exiting 0)")
            raise ctx.exit(CLEAN)
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo(
        f"Doc sizes OK: AGENTS.md <= {AGENTS_MAX}, DIGEST.md <= {DIGEST_MAX}, "
        f"ARCHITECTURE.md <= {ARCHITECTURE_MAX}, PROGRESS.md <= {PROGRESS_MAX}, "
        f"SKILL.md <= {SKILL_MAX} lines, description <= {DESCRIPTION_MAX} chars, "
        f"DIGEST-full.md <= {DIGEST_FULL_MAX} lines"
    )
    raise ctx.exit(CLEAN)




if __name__ == "__main__":
    raise SystemExit(run(cli))
