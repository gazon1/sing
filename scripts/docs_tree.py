"""docs_tree.py — draw `docs/` as a bounded tree for the orientation layer.

Split out of `refresh-decisions-digest.py` on 2026-10-15, and the split is the point.

`refresh-decisions-digest.py` is a registered ADR-corpus consumer: `check-adr-config-sync.py`
scans it and fails any directory walk in it, because #520 was five consumers each growing
their own glob over `docs/decisions/` until they disagreed about how many files there were.
Adding a tree renderer to it tripped that gate, and the temptation was to contort a
variable name until the regex stopped matching — which teaches the next reader that the
gate can be talked out of anything.

The honest fix is the ownership boundary the gate is actually protecting. **This module
draws directories; it does not discover decisions.** Every ADR count is passed in by the
caller, which got it from `adr_corpus`. The one number computed here is the residual —
files in `docs/decisions/` that the corpus does not classify — and it is computed by
*subtracting* the corpus's slug set, not by deciding what an ADR is.

So this module does not call `adr_corpus.load()`, is not a corpus consumer, and is not
scanned. That is a statement about what it does, not a way around a rule.
"""

from __future__ import annotations

import pathlib

# The generator's own output, excluded from the residual count below.
GENERATED = frozenset({"DIGEST.md", "DIGEST-full.md"})


def _count_files(directory: pathlib.Path) -> int:
    """Files under a directory, for the count beside its name."""
    return sum(1 for p in directory.rglob("*") if p.is_file())


def unclassified_in_decisions(decisions_dir: pathlib.Path, known_slugs: set[str]) -> int:
    """How many files in the decisions directory the corpus does not classify.

    That directory holds more than decisions. Two plain notes sit at the top level without
    a date prefix (`coroutines-test-background-work-advance-until-idle.md` and
    `ktlint-cli-formatter-editorconfig-detekt-alignment.md` as of 2026-10-15), and
    `templates/` holds a third. A label that reported only the decision counts would be
    quietly wrong, and a reader who adds the numbers up and finds a remainder stops
    trusting the whole tree.

    Subtracts `known_slugs` rather than testing the date pattern: what counts as a decision
    is `adr_corpus`'s rule, and re-deriving it here is the exact duplication #520 exists to
    prevent.
    """
    return sum(
        1
        for p in decisions_dir.iterdir()
        if p.is_file() and p.name not in GENERATED and p.stem not in known_slugs
    )


def render(
    docs_dir: pathlib.Path,
    decisions_dir: pathlib.Path,
    arch_dir: pathlib.Path,
    *,
    decision_count: int,
    deferred_count: int,
    archived_count: int,
    known_slugs: set[str],
    deferred_dir: str,
    archive_dir: str,
) -> list[str]:
    """Render `docs/` as a bounded tree: names at the top level, counts inside.

    `docs/architecture/` is expanded by name — it is where the current state of the system
    lives, it is small, and it is the section an agent actually navigates. `docs/decisions/`
    is the opposite: hundreds of date-prefixed files whose names are already
    self-describing, so it collapses to a breakdown.

    Nothing is hidden silently. Every directory carries its file count, and every
    subdirectory of the collapsed one is named with its size, so the whole folder adds up.
    An agent must be able to tell "there is nothing there" from "I did not show it" — the
    difference between an honest index and one that quietly lies, which this repo has an
    ADR about (`2026-10-06-an-instrument-nobody-reads-is-not-an-instrument.md`).
    """
    entries = sorted(docs_dir.iterdir(), key=lambda p: (p.is_file(), p.name))
    last_index = len(entries) - 1
    lines: list[str] = []
    for i, path in enumerate(entries):
        branch = "└── " if i == last_index else "├── "
        cont = "    " if i == last_index else "│   "
        if path.is_file():
            lines.append(f"{branch}{path.name}")
            continue
        if path == decisions_dir:
            other = unclassified_in_decisions(decisions_dir, known_slugs)
            breakdown = f"{decision_count} decisions"
            if other > 0:
                unit = "note" if other == 1 else "notes"
                breakdown += f" + {other} other {unit}"
            lines.append(f"{branch}{decisions_dir.name}/  {breakdown} "
                         "— index in `DIGEST-full.md`")
            subdirs = sorted(
                (p for p in path.iterdir() if p.is_dir()), key=lambda p: p.name)
            for k, sub in enumerate(subdirs):
                tick = "└── " if k == len(subdirs) - 1 else "├── "
                n = _count_files(sub)
                unit = "record" if n == 1 else "records"
                if sub.name == deferred_dir:
                    note = f"{deferred_count} deferred backlog entries"
                elif sub.name == archive_dir:
                    note = f"{archived_count} non-decision records, excluded"
                else:
                    note = f"{n} {unit}, not part of the corpus"
                lines.append(f"{cont}{tick}{sub.name}/  {note}")
            continue
        if path == arch_dir:
            files = sorted(p for p in path.iterdir() if p.is_file())
            lines.append(f"{branch}{arch_dir.name}/  {len(files)} files:")
            for j, f in enumerate(files):
                tick = "└── " if j == len(files) - 1 else "├── "
                lines.append(f"{cont}│   {tick}{f.name}")
            continue
        n = _count_files(path)
        unit = "file" if n == 1 else "files"
        lines.append(f"{branch}{path.name}/  {n} {unit}")
    return lines