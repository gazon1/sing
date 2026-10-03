#!/usr/bin/env python3
"""check-doc-sizes.py — enforce the size budgets for agent-facing docs.

Budgets follow the Agent Skills spec and the industry rules of thumb:
  - AGENTS.md   <= 250 lines  — the "two hundred lines helps you notice growth" guideline
  - SKILL.md    <= 500 lines  — agentskills.io: "Keep your main SKILL.md under 500 lines"
  - description <= 1024 chars — agentskills.io spec hard cap on the description field

Exits non-zero if any budget is exceeded, so it can gate a PR.
Usage: python3 scripts/check-doc-sizes.py [--warn-only]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKILLS_DIR = ROOT / ".agents" / "skills"
AGENTS_MD = ROOT / "AGENTS.md"
DIGEST_MD = ROOT / "docs" / "decisions" / "DIGEST.md"

AGENTS_MAX = 250
SKILL_MAX = 500
DESCRIPTION_MAX = 1024
DIGEST_MAX = 1250  # matches MAX_DIGEST_LINES in refresh-decisions-digest.py; DIGEST.md ~1219 lines at 2026-10-03


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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--warn-only",
        action="store_true",
        help="report violations but always exit 0",
    )
    args = parser.parse_args()

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

    for skill_md in sorted(SKILLS_DIR.glob("*/SKILL.md")):
        rel = skill_md.parent.name
        n = count_lines(skill_md)
        if n > SKILL_MAX:
            violations.append((rel, f"SKILL.md {n} lines (max {SKILL_MAX}) — consider a router + leaf split"))
        desc = description_of(frontmatter_of(skill_md))
        if desc is not None and len(desc) > DESCRIPTION_MAX:
            violations.append((rel, f"description {len(desc)} chars (max {DESCRIPTION_MAX})"))

    if violations:
        print("Doc size budgets exceeded:\n")
        for name, detail in violations:
            print(f"  {name}: {detail}")
        print(
            "\nFix: trim the file, or split an oversized skill into a router SKILL.md "
            "plus sibling leaf files loaded only when needed."
        )
        if args.warn_only:
            print("\n(--warn-only: exiting 0)")
            return 0
        return 1

    print(
        f"Doc sizes OK: AGENTS.md <= {AGENTS_MAX}, DIGEST.md <= {DIGEST_MAX}, "
        f"SKILL.md <= {SKILL_MAX} lines, description <= {DESCRIPTION_MAX} chars"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
