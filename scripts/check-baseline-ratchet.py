#!/usr/bin/env python3
"""check-baseline-ratchet.py — a detekt baseline may shrink, never grow.

Why this exists (2026-10-04): `config/detekt/baseline-shared.xml` held 413
suppressed findings and nothing anywhere compared it to its previous size. A
baseline is a ratchet by nature — each entry is a debt someone accepted — but
without this check it is a dumping ground: enabling a new rule, or writing code
that violates an existing one, silently appends an entry and stays green. That
is how "detekt reports 0 findings" came to mean "detekt reports 0 findings
outside the baseline", which is not the same claim.

The check is deliberately one-directional. Removing entries is always allowed
(that's the debt being paid down); adding them is a failure that must be
justified in review. This is the industry-standard replacement for a mass
baseline purge: ratchet, don't purge.

Contract:
  * Baseline file is compared against its committed state in git. A PR that
    grows the baseline fails, naming the delta.
  * `git` is required. Without it the check fails loudly rather than skipping —
    a check that silently passes when it cannot run is the defect class this
    whole change is about.
  * A brand-new baseline file (first commit) is allowed once, then ratchets.

Usage:
  python3 scripts/check-baseline-ratchet.py [--baseline <path>] [--max-growth N]

Exit codes:
  0 — baseline did not grow (or shrank)
  1 — baseline grew, or the check could not run
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# Detekt baseline entries look like:
#   <ID>RuleName:File.kt:Fqcn$signature</ID>
# One <ID> element per suppressed finding. Counting them is the ratchet unit —
# counting lines would break when detekt rewraps a long signature.
_ID_RE = re.compile(r"<ID>.*?</ID>", re.DOTALL)


def count_entries(text: str) -> int:
    return len(_ID_RE.findall(text))


def entries(text: str) -> set[str]:
    return {m.strip() for m in _ID_RE.findall(text)}


def git_show(path: Path, rev: str = "HEAD") -> str | None:
    """Return the committed content of `path`, or None if it did not exist."""
    try:
        out = subprocess.run(
            ["git", "show", f"{rev}:{path.as_posix()}"],
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        print("ERROR: git not found — cannot verify the baseline ratchet")
        raise SystemExit(1) from None
    if out.returncode != 0:
        return None
    return out.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--baseline",
        default="config/detekt/baseline-shared.xml",
        help="baseline file to ratchet (default: config/detekt/baseline-shared.xml)",
    )
    parser.add_argument(
        "--max-growth",
        type=int,
        default=0,
        help="entries allowed to be added per run (default: 0)",
    )
    args = parser.parse_args()

    baseline = Path(args.baseline)
    if not baseline.is_file():
        print(f"ERROR: baseline not found: {baseline}")
        return 1

    current_text = baseline.read_text(encoding="utf-8")
    current = count_entries(current_text)

    committed_text = git_show(baseline)
    if committed_text is None:
        # First introduction of this baseline. Allow it, then ratchet from here.
        print(
            f"check-baseline-ratchet: {baseline} is new in this branch "
            f"({current} entries) — ratcheting from this commit"
        )
        return 0

    committed = count_entries(committed_text)
    growth = current - committed

    if growth > args.max_growth:
        added = sorted(entries(current_text) - entries(committed_text))
        print("")
        print(
            f"check-baseline-ratchet: FAIL — {baseline} grew by {growth} "
            f"entry(ies) ({committed} → {current})"
        )
        print("")
        print("Newly suppressed findings:")
        for item in added[:20]:
            print(f"  + {item}")
        if len(added) > 20:
            print(f"  … and {len(added) - 20} more")
        print("")
        print("A baseline is a ratchet, not a place to park new debt. Either")
        print("fix the finding, or — if the rule was just enabled and the")
        print("pre-existing volume is genuinely being baselined on purpose —")
        print("pass --max-growth N in a commit whose message says why.")
        return 1

    if growth < 0:
        print(
            f"check-baseline-ratchet: OK — {baseline} shrank by {-growth} "
            f"entry(ies) ({committed} → {current})"
        )
    else:
        print(
            f"check-baseline-ratchet: OK — {baseline} unchanged "
            f"({current} entries)"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
