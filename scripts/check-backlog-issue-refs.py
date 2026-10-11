#!/usr/bin/env python3
"""check-backlog-issue-refs.py — bidirectional backlog↔issues consistency gate.

Four invariants are enforced:

  I1  Every #NN that a backlog entry cites as `Tracked as: #NN` exists in the
      issue tracker snapshot. A cite to a non-existent issue is a broken link.

  I2  Every OPEN or PARTIAL backlog entry that cites `Tracked as: #NN` cites an
      OPEN issue. If the cited issue is CLOSED, the entry has stale tracking
      and should be closed or re-tracked.

  I3  Every OPEN or PARTIAL backlog entry has a `Tracked as:` reference or a
      `Tracking: none — reason` explaining why not. (Entries with `Tracking:
      none` are environment-findings that need no issue; this is already
      enforced by check-backlog-status.py, but I3 makes the contract explicit
      for the issues side.)

  I4  Every OPEN issue in the snapshot cites a backlog entry via a `Backlog:`
      field in its body. This is the reverse direction: the issue tracker is
      the queue, the backlog is the reasoning — both sides must agree.

      This invariant starts ADVISORY because the convention is new and existing
      issues do not yet carry the field. Once the field is added to all open
      issues during routine triage, this will become blocking.

Output format follows K2: "current / expected / delta / reason" per finding.
Exit code 0 = all invariants pass; 1 = at least one invariant fails.

Usage:
    python3 scripts/check-backlog-issue-refs.py \\
        --backlog docs/decisions/deferred-backlog.md \\
        --snapshot config/docs/issues-snapshot.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import adr_corpus  # noqa: E402  — single owner of corpus discovery (#520)

CORPUS = adr_corpus.load(ROOT)

# ── regexes (mirrors check-backlog-status.py conventions) ────────────────────

_ENTRY_RE = re.compile(r"^## (.+)$", re.M)
_TRACKED_RE = re.compile(r"^\*\*Tracked as:?\*\*", re.M)
_TRACKING_WHY_RE = re.compile(r"^\*\*Tracking:?\*\*", re.M)
_ISSUE_REF_RE = re.compile(r"#(\d+)")
_BACKLOG_FIELD_RE = re.compile(r"(?i)^\s*Backlog:\s*([^\n]+)", re.M)

# ── status vocabulary (from check-backlog-status.py) ────────────────────────

OPEN_STATES = ("OPEN",)
CLOSED_STATES = ("CLOSED", "RESOLVED", "SUPERSEDED")
PARTIAL_STATES = ("PARTIALLY", "HALF", "MEASURED")
_NON_WORD = " *_`.-—–:"


def _first_word(status: str) -> str:
    parts = status.split()
    if not parts:
        return ""
    return parts[0].strip(_NON_WORD).upper()


def _classify(status: str | None) -> str:
    if not status:
        return "unclassifiable"
    word = _first_word(status)
    if word in CLOSED_STATES:
        return "closed"
    if word in OPEN_STATES:
        return "open"
    if word in PARTIAL_STATES:
        return "partial"
    return "unclassifiable"


# ── backlog parser ────────────────────────────────────────────────────────────

_STATUS_RE = re.compile(r"^\*\*\s*Status[^:*]*:?\*?\*?:?\s*(.+?)\s*$")


def _parse_backlog_entries(text: str) -> list[dict]:
    matches = list(_ENTRY_RE.finditer(text))
    out: list[dict] = []
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = text[start:end]
        status_raw = None
        for line in body.splitlines():
            sm = _STATUS_RE.match(line.strip())
            if sm:
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


def _extract_tracked_issue(slug: str, body: str) -> int | None:
    """Extract the issue number from `**Tracked as:** #NN` in an entry body.

    Handles: `#NN`, `[#NN](url)`, `[#NN](url) — note`.
    Returns None if no reference is found.
    """
    m = _TRACKED_RE.search(body)
    if not m:
        return None
    # Find the #NN after the marker
    tail = body[m.end() :]
    # Skip markdown link structure if present
    m2 = re.search(r"#(\d+)", tail)
    if m2:
        return int(m2.group(1))
    return None


# ── snapshot loader ─────────────────────────────────────────────────────────


def _load_snapshot(path: Path) -> dict[int, dict]:
    """Load the snapshot as {issue_number: {number, state, title, backlog_slug}}."""
    if not path.is_file():
        print(f"ERROR: snapshot not found: {path}", file=sys.stderr)
        print(
            "Run `just issues-refresh` to fetch the current issue state from GitHub.",
            file=sys.stderr,
        )
        sys.exit(1)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"ERROR: snapshot is not valid JSON: {exc}", file=sys.stderr)
        sys.exit(1)
    result: dict[int, dict] = {}
    for item in data:
        if "number" not in item or "state" not in item:
            print(
                f"ERROR: snapshot entry missing required field: {item!r}",
                file=sys.stderr,
            )
            sys.exit(1)
        result[int(item["number"])] = item
    return result


# ── gate logic ───────────────────────────────────────────────────────────────


def _backlog_text() -> str:
    """The per-entry files under `deferred/`, assembled into the combined shape.

    T2 (PR #508) split the combined `deferred-backlog.md` into one file per entry and
    deleted it. This gate still pointed at the deleted path, so it had been failing with
    "backlog not found" since then — silently, because it is registered advisory.

    Entries are rebuilt as `## <slug>` headings because that is the shape
    `_parse_backlog_entries` reads, and it is the shape the tests exercise.
    """
    return "\n".join(
        f"## {p.stem}\n{p.read_text(encoding='utf-8')}"
        for p in CORPUS.deferred()
    )


def check_invariants(
    backlog_path: Path,
    snapshot_path: Path,
) -> list[str]:
    """Return a list of finding lines; empty means all invariants pass.

    Takes the combined-file shape, as it always has. `main` builds that shape out
    of the per-entry files (below) rather than this function reaching into the
    corpus itself, so the fixtures in the tests keep working unchanged.
    """
    return check_text(backlog_path.read_text(encoding="utf-8"), snapshot_path, backlog_path)


def check_text(backlog_text: str, snapshot_path: Path, label: Path | str = "backlog") -> list[str]:
    """The invariants, over already-assembled backlog text."""
    entries = _parse_backlog_entries(backlog_text)
    snapshot = _load_snapshot(snapshot_path)

    findings: list[str] = []

    # Pre-index snapshot by backlog_slug for I4
    slug_to_issue: dict[str, dict] = {}
    for issue in snapshot.values():
        bs = issue.get("backlog_slug", "") or ""
        if bs:
            slug_to_issue[bs] = issue

    # I1: every Tracked as: #NN cites an existing issue
    # I2: OPEN/PARTIAL entry citing a CLOSED issue → stale tracking
    for e in entries:
        classification = _classify(e["status"])
        issue_num = _extract_tracked_issue(e["slug"], e["body"])

        if issue_num is None:
            # Entry has no tracked issue reference.
            # I3: OPEN/PARTIAL entries must have Tracked as: or Tracking: none
            if classification in ("open", "partial") and not e["tracked"]:
                findings.append(
                    f"{label}:{e['line']}  {e['slug']}"
                    f"  [I3] open entry has no Tracked as: and no Tracking: — "
                    f"open entries must cite an issue or explain why they don't"
                )
            continue

        issue = snapshot.get(issue_num)
        if issue is None:
            findings.append(
                f"{label}:{e['line']}  {e['slug']}"
                f"  [I1] tracked as #{issue_num} but issue does not exist in snapshot"
            )
        else:
            # I2: OPEN/PARTIAL entry tracking a CLOSED issue
            if classification in ("open", "partial") and issue["state"] == "CLOSED":
                findings.append(
                    f"{label}:{e['line']}  {e['slug']}"
                    f"  [I2] tracks CLOSED issue #{issue_num} ({issue['title'][:50]})"
                    f" — entry should be closed or re-tracked"
                )

    # I4: every OPEN issue cites a backlog entry via Backlog: field
    # This starts ADVISORY (not added to findings as hard errors) because
    # existing issues do not yet carry the Backlog: field. Once the field
    # is added to all open issues, this check will be promoted to blocking.
    for issue_num, issue in snapshot.items():
        if issue["state"] != "OPEN":
            continue
        bs = issue.get("backlog_slug", "") or ""
        if not bs:
            findings.append(
                f"issue #{issue_num}  [I4] open issue has no Backlog: field"
                f" — add `Backlog: <slug>` to the issue body"
            )

    return findings


# ── output format (K2: current / expected / delta / reason) ──────────────────


def _summarise(counts: dict[str, int]) -> str:
    parts = [f"{v} {k}" for k, v in sorted(counts.items())]
    return ", ".join(parts) or "0 entries"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--backlog",
        type=Path,
        default=CORPUS.root,
        help="corpus root; entries are read from its deferred/ subdirectory",
    )
    ap.add_argument(
        "--snapshot",
        type=Path,
        default=ROOT / "config" / "docs" / "issues-snapshot.json",
        help="issue snapshot file (default: issues-snapshot.json)",
    )
    args = ap.parse_args()

    backlog_path: Path = args.backlog
    snapshot_path: Path = args.snapshot

    if not CORPUS.deferred():
        print(
            f'ERROR: no backlog entries under {CORPUS.root}/{CORPUS.deferred_dir}',
            file=sys.stderr,
        )

    # Resolve relative to ROOT
    if not snapshot_path.is_absolute():
        snapshot_path = ROOT / snapshot_path
    if not backlog_path.is_absolute():
        backlog_path = ROOT / backlog_path

    findings = check_text(_backlog_text(), snapshot_path, backlog_path)

    # Collect summary stats
    # T2 (PR #508) split the combined file into one file per entry and deleted it.
    # This gate still pointed at `deferred-backlog.md`, so it had been failing with
    # "backlog not found" since then — silently, because it is registered advisory.
    backlog_text = "\n".join(
        f"## {p.stem}\n{p.read_text(encoding='utf-8')}"
        for p in CORPUS.deferred()
    )
    entries = _parse_backlog_entries(backlog_text)
    snapshot = _load_snapshot(snapshot_path)

    open_entries = [e for e in entries if _classify(e["status"]) in ("open", "partial")]
    open_issues = [i for i in snapshot.values() if i["state"] == "OPEN"]

    print(f"backlog-issue-refs: {len(entries)} entries, {len(open_entries)} open")
    print(f"  snapshot: {len(snapshot)} issues ({len(open_issues)} open)")

    errors = [f for f in findings if "  [I4]" not in f]
    warnings = [f for f in findings if "  [I4]" in f]

    if errors:
        print("")
        for err in errors:
            print(f"ERROR: {err}")
    if warnings:
        print("")
        print(f"WARNING: {len(warnings)} open issue(s) missing Backlog: field")
        for warn in warnings[:10]:
            print(f"  {warn}")
        if len(warnings) > 10:
            print(f"  ... and {len(warnings) - 10} more")

    if errors:
        print("")
        print(f"check-backlog-issue-refs: FAIL — {len(errors)} blocking error(s)")
        return 1

    print("  ok  all blocking invariants pass")
    if warnings:
        print(f"  ⚠   {len(warnings)} open issue(s) still need Backlog: field")
    return 0


if __name__ == "__main__":
    sys.exit(main())
