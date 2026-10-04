#!/usr/bin/env python3
"""check-unwired-backlog-refs.py — every baseline row must name a real backlog entry.

Why this exists (2026-10-04): `find-unwired-surfaces-baseline.txt` documents the
invariant in its own header —

    Rule: a line without a live backlog reference is a gate failure (the symbol
    must have a home in deferred-backlog.md or an ADR before it can be exempted).

— and nothing implemented it. `find-unwired-surfaces.py` splits each row on `|`
and reads `parts[2]` (the Reason) into its report; the reference in `parts[3]`
is never parsed, never resolved, and never checked. So four of the five
referenced anchors (`recurrence-parser-is-unwired`,
`note-editor-unwired-domain-classes`, `editoroverflow-test-tag-unused`,
`awt-menubarinstaller-jvm-unused`) pointed at headings that did not exist in
`docs/decisions/deferred-backlog.md`, and the gate stayed green.

The invariant was real; only the check was missing. This is that check. It is
separate from the detector rather than folded into it because the detector
answers "is this symbol wired?", and this answers "is the exemption honest?" —
a question about the baseline file, not the source tree.

Contract:
  * Every non-comment row must have 4 `|`-separated columns.
  * Column 4 is either `none` (accepted dead debt, consciously untracked) or
    `deferred-backlog.md:<anchor>` / `decisions/<file>.md:<anchor>` /
    `<file>.md:<anchor>` naming a `## <anchor>` heading that exists.
  * The 4th column is parsed as a regex `[A-Za-z0-9._/-]+`, because the baseline
    stores the short name (`deferred-backlog.md`) while the file on disk lives at
    `docs/decisions/deferred-backlog.md`. Resolution tries `docs/decisions/<ref>`
    first, then the raw path.

Usage:
  python3 scripts/check-unwired-backlog-refs.py [--baseline <path>]

Exit codes:
  0 — every row's reference resolves
  1 — a row is malformed or points at a heading that does not exist
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

DEFAULT_BASELINE = ROOT / "scripts" / "find-unwired-surfaces-baseline.txt"
BACKLOG_SEARCH_DIRS = ("docs/decisions", "docs")

# A reference is `path-part.md:anchor` — the path may contain `/` and `.`.
REF_RE = re.compile(r"^(?P<file>[A-Za-z0-9._/-]+\.md):(?P<anchor>.+)$")


def resolve_baseline_ref(ref: str) -> pathlib.Path | None:
    """Find the file a `file.md:anchor` reference points at, if it exists."""
    for base in BACKLOG_SEARCH_DIRS:
        candidate = ROOT / base / ref.split(":")[0]
        if candidate.is_file():
            return candidate
    return None


def anchors_in(path: pathlib.Path) -> set[str]:
    """Every `## <slug>` heading in the file, lowercased for comparison."""
    found: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^#{1,6}\s+(.*?)\s*$", line)
        if not m:
            continue
        slug = m.group(1).strip()
        # Drop a trailing status marker like "## foo (RESOLVED)".
        slug = re.sub(r"\s*\([^)]*\)\s*$", "", slug)
        found.add(slug.lower())
    return found


def check_baseline(baseline: pathlib.Path) -> list[str]:
    errors: list[str] = []
    if not baseline.is_file():
        return [f"baseline not found: {baseline}"]

    for lineno, raw in enumerate(baseline.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) < 4:
            errors.append(
                f"{baseline.name}:{lineno}: expected 4 '|'-separated columns "
                f"(Symbol | File | Reason | BacklogRef), got {len(parts)}"
            )
            continue

        symbol, ref = parts[0], parts[3]
        if not ref:
            errors.append(f"{baseline.name}:{lineno}: {symbol} has an empty BacklogRef")
            continue
        if ref == "none":
            # Explicitly accepted dead debt. Allowed by design — see the
            # baseline header — but it must be spelled exactly, not blank.
            continue

        m = REF_RE.match(ref)
        if not m:
            errors.append(
                f"{baseline.name}:{lineno}: {symbol} has malformed BacklogRef "
                f"{ref!r} (expected 'file.md:anchor' or the literal 'none')"
            )
            continue

        target = resolve_baseline_ref(ref)
        if target is None:
            errors.append(
                f"{baseline.name}:{lineno}: {symbol} references {ref!r} but no such "
                f"file exists under {', '.join(BACKLOG_SEARCH_DIRS)}/"
            )
            continue

        anchor = m.group("anchor").lower()
        if anchor not in anchors_in(target):
            errors.append(
                f"{baseline.name}:{lineno}: {symbol} references "
                f"## {m.group('anchor')} in {target.relative_to(ROOT)}, "
                f"but that heading does not exist"
            )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--baseline",
        default=str(DEFAULT_BASELINE),
        help="baseline file to validate",
    )
    args = parser.parse_args()

    baseline = pathlib.Path(args.baseline)
    errors = check_baseline(baseline)

    if errors:
        for err in errors:
            print(f"ERROR: {err}")
        print("")
        print(
            "Every exempt symbol needs a home in deferred-backlog.md before it "
            "can stay in the baseline. Add the entry (or drop the symbol from the "
            "baseline if the debt is gone). A reference to a heading that does "
            "not exist is the same as no reference at all."
        )
        return 1

    print(f"check-unwired-backlog-refs: OK — every exemption in {baseline.name} resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
