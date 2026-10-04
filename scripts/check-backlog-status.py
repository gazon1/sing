#!/usr/bin/env python3
"""check-backlog-status.py — every backlog entry states whether it is still true.

Why this exists (2026-10-05): the file this project calls `deferred-backlog.md`
is 2900 lines of findings, and 42 of its 82 entries carried no status line at
all — not a stale one, not a contradictory one, none. Two different regexes
written to count the resolved entries returned 18 and 26 on the same file, which
is the honest measurement of how unclassifiable it was: each parser silently
classified what it could and skipped the rest, and neither said so.

An entry nobody can classify is an entry nobody can triage. And the failure mode
is already documented in the file's own
`an-open-backlog-entry-does-not-mean-the-work-is-still-open`: nine entries read
`Status: OPEN` while describing work that had shipped, two of them actively
misleading, and nothing in the toolchain noticed. A status line is only as
current as the last person who remembered to look — so the thing worth enforcing
is not that the status is *right*, which no static check can know, but that it
*exists and is one of a fixed set of words*, so a reader can triage the file and
so a future sweep has something to sweep.

This check is deliberately static. It cannot tell you whether a finding is still
true; the project already answered that question and the answer was a process
change (sweep during the retro, while the session still knows what it changed).
What this check does is make the ambiguity countable instead of invisible.

Budget: the live file is a queue, and a queue with no bound is a document. The
cap is on entries, not on lines — line count is a property of how verbose an
entry happens to be and would be lowered by editing prose rather than by
deciding anything.

Contract:
  * every `## slug` entry carries a `**Status:**` line whose value is one of
    OPEN / CLOSED / RESOLVED / SUPERSEDED / PARTIALLY … / MEASURED …
  * an entry whose status is OPEN must carry a `**Tracked as:**` reference, or
    a `**Tracking:**` line explaining why it has none
  * the entry count is at or under `--max-entries`

Usage:
  python3 scripts/check-backlog-status.py [--backlog <path>] [--max-entries N]

Exit codes:
  0 — every entry is classifiable and the file is within budget
  1 — an entry has no usable status, an open one is untracked, or the budget is
      exceeded
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

_ENTRY_RE = re.compile(r"^## (.+)$", re.M)
_STATUS_RE = re.compile(r"^\*\*\s*Status[^:*]*:?\*?\*?:?\s*(.+?)\s*$")
_TRACKED_RE = re.compile(r"^\*\*Tracked as:?\*\*", re.M)
_TRACKING_WHY_RE = re.compile(r"^\*\*Tracking:?\*\*", re.M)

# Deliberately a fixed vocabulary. "Any word followed by a colon" would classify
# every entry in the file as resolved-or-not by accident, which is the mistake
# two hand-written regexes made while writing this.
OPEN_STATES = ("OPEN",)
CLOSED_STATES = ("CLOSED", "RESOLVED", "SUPERSEDED")
# Partly-done states are legitimate and must be spelled, not hidden: an entry
# marked PARTIALLY CLOSED is a live commitment with a recorded part of it done,
# and that is a different thing from OPEN.
PARTIAL_STATES = ("PARTIALLY", "HALF", "MEASURED")

# Keyword vocabulary, ordered. `first_word` normalises the value first.

# Budget on entries, not lines, and with headroom on purpose.
#
# 57 live entries after the 2026-10-05 archive split. The budget is 70, not 57:
# a cap set at the current count is a wall, not a ratchet — the next finding
# would fail the build and the cheapest way out would be to delete a real
# finding, which is worse than a slightly long queue. `AGENTS.md` is sitting at
# 250/250 for the same reason and it is the thing this budget exists to avoid
# repeating. 70 is roughly 20% of headroom: enough that ordinary work adds
# entries without a conversation, tight enough that a backlog doubling in size
# is caught rather than absorbed.
DEFAULT_MAX_ENTRIES = 70


def parse_entries(text: str) -> list[dict[str, object]]:
    """Split the file into entries and read each one's status.

    The first `**Status…**` line anywhere in the entry wins.

    There was a 12-line window here first, on the reasoning that a status buried
    deep in the body is more likely to be prose about an earlier status. A test
    caught it on a real shape — `Found in:` paragraphs run long, and an entry
    whose status sat at line 14 was silently skipped, which is the exact
    behaviour that made this file unclassifiable in the first place. A window
    that quietly drops what it does not reach is the same defect as a rule that
    matches nothing. Where a later status contradicts the first, the first wins
    and the contradiction stays visible in the body.
    """
    matches = list(_ENTRY_RE.finditer(text))
    out: list[dict[str, object]] = []
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = text[start:end]
        status_raw = None
        for line in body.splitlines():
            sm = _STATUS_RE.match(line.strip())
            if sm:
                # The closing bold markers come along with the value, so a raw
                # read of `**Status: CLOSED**` is `CLOSED**`. Classification
                # coped, but the "status reads: …" line in a failure report is
                # meant for a human, and `CLOSED**` is not what the file says.
                status_raw = sm.group(1).strip().strip("*_` ").strip()
                break
        tracked = bool(_TRACKED_RE.search(body)) or bool(_TRACKING_WHY_RE.search(body))
        out.append(
            {
                "slug": m.group(1).strip(),
                "line": text[: m.start()].count("\n") + 1,
                "status": status_raw,
                "tracked": tracked,
                "body": body,
            }
        )
    return out


_NON_WORD = " *_`.-—–:"


def first_word(status: str) -> str:
    """The leading keyword of a status value, uppercased.

    `**Status: OPEN.**` puts the period *inside* the bold, so the captured value
    is `OPEN.**` and stripping only `*` and `_` leaves `OPEN.` — which matches no
    state and silently turned a perfectly good entry into an unclassifiable one.
    That is the third time in this change that a hand-written parse of this file
    disagreed with the file: 42 unmarked entries, 18 vs 26 resolved, and now a
    period. Each was a parser assuming a shape the file does not keep to.
    """
    parts = status.split()
    if not parts:
        return ""
    return parts[0].strip(_NON_WORD).upper()


def classify(status: str | None) -> str:
    """`open`, `closed`, `partial`, or `unclassifiable`."""
    if not status:
        return "unclassifiable"
    word = first_word(status)
    if word in CLOSED_STATES:
        return "closed"
    if word in OPEN_STATES:
        return "open"
    if word in PARTIAL_STATES:
        return "partial"
    return "unclassifiable"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--backlog",
        default="docs/decisions/deferred-backlog.md",
        help="backlog file to check (default: the live one)",
    )
    ap.add_argument(
        "--max-entries",
        type=int,
        default=DEFAULT_MAX_ENTRIES,
        help=f"entry budget (default: {DEFAULT_MAX_ENTRIES})",
    )
    args = ap.parse_args()

    path = ROOT / args.backlog
    if not path.is_file():
        print(f"ERROR: backlog not found: {path}")
        return 1
    text = path.read_text(encoding="utf-8")
    entries = parse_entries(text)
    if not entries:
        print(f"ERROR: no `## slug` entries found in {path}")
        return 1

    errors: list[str] = []

    unclassifiable = [e for e in entries if classify(e["status"]) == "unclassifiable"]
    untracked_open = [
        e
        for e in entries
        if classify(e["status"]) in ("open", "partial") and not e["tracked"]
    ]
    over_budget = len(entries) > args.max_entries

    counts: dict[str, int] = {}
    for e in entries:
        counts[classify(e["status"])] = counts.get(classify(e["status"]), 0) + 1

    rel = args.backlog
    print(f"{rel}: {len(entries)} entries (budget {args.max_entries})")
    for state in ("open", "partial", "closed", "unclassifiable"):
        if state in counts:
            print(f"  {state:15} {counts[state]}")

    if unclassifiable:
        lines = [
            f"check-backlog-status: FAIL — {len(unclassifiable)} entr"
            f"{'y' if len(unclassifiable) == 1 else 'ies'} carry no status this check can read.",
            "",
        ]
        for e in unclassifiable[:20]:
            raw = e["status"]
            lines.append(
                f"  {rel}:{e['line']}  {e['slug']}"
                + (f"   (status reads: {raw!r})" if raw else "   (no Status line)")
            )
        if len(unclassifiable) > 20:
            lines.append(f"  ... and {len(unclassifiable) - 20} more")
        lines += [
            "",
            "An entry nobody can classify is an entry nobody can triage. Give it a",
            "`**Status:**` line whose value starts with OPEN, CLOSED, RESOLVED,",
            "SUPERSEDED, PARTIALLY, HALF or MEASURED, and — while you are there —",
            "check whether the finding is still true. Two regexes written to count",
            "the resolved entries in this file disagreed by 8, which is what a file",
            "with no fixed vocabulary measures.",
        ]
        errors.append("\n".join(lines))

    if untracked_open:
        lines = [
            f"check-backlog-status: FAIL — {len(untracked_open)} open entr"
            f"{'y' if len(untracked_open) == 1 else 'ies'} is not tracked anywhere.",
            "",
        ]
        for e in untracked_open[:20]:
            lines.append(f"  {rel}:{e['line']}  {e['slug']}")
        lines += [
            "",
            "An open entry with no `**Tracked as:**` reference is a commitment with",
            "nowhere to live, and it is the shape the file's own entry",
            "`an-open-backlog-entry-does-not-mean-the-work-is-still-open` describes.",
            "File an issue and cite it, or add a `**Tracking:**` line saying why there",
            "is deliberately none.",
        ]
        errors.append("\n".join(lines))

    if over_budget:
        errors.append(
            f"check-backlog-status: FAIL — {rel} holds {len(entries)} entries, "
            f"budget is {args.max_entries}. The budget is on entries, not lines: "
            f"line count moves when prose is edited, entry count moves only when "
            f"work is added or closed."
        )

    if errors:
        print("")
        for err in errors:
            print(f"ERROR: {err}\n")
        return 1

    print("  ok  every entry is classifiable, every open one is tracked, within budget")
    return 0


if __name__ == "__main__":
    sys.exit(main())
