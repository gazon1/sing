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

# Module-level so the paths do not depend on the caller's working directory.
ROOT = Path(__file__).resolve().parent.parent

# detekt.yml shape:
#   style:                       <- ruleset header, indent 0
#     BackingPropertyNaming:     <- rule header, indent 2
#       active: false            <- the rule's own flag, indent 4
# The indent is load-bearing and the distinction is easy to get wrong: the same
# file carries ruleset-level `active:` at indent 2, and a parser that keys on
# the nearest preceding header regardless of indent reports every ruleset's
# flag as a rule's. That mistake was made while writing this check and it
# reported 11 "rules" for the file's 5.
_RULE_HEADER_RE = re.compile(r"^  ([A-Za-z][\w-]*):\s*$")
_ACTIVE_FALSE_RE = re.compile(r"^\s+active:\s*false\s*$")


def disabled_rules(config_text: str) -> dict[str, int]:
    """Map rule name -> 1 for every rule declared `active: false`.

    A rule's flag is only read while the rule header is the innermost open
    block: a line at indent <= 2 closes it. Comments between the header and the
    flag are ignored, which is why every rule in this repository that needs a
    reason for its decision carries a comment there.
    """
    out: dict[str, int] = {}
    current: str | None = None
    for line in config_text.splitlines():
        if line.strip().startswith("#"):
            continue
        if current is not None and line.strip() and not line.startswith("    "):
            # Dedented back to (or past) the rule header: this rule is closed.
            if _RULE_HEADER_RE.match(line) or not line.startswith("  "):
                current = None
        header = _RULE_HEADER_RE.match(line)
        if header:
            current = header.group(1)
            continue
        if current and _ACTIVE_FALSE_RE.match(line):
            out[current] = 1
    return out


def count_entries(text: str) -> int:
    return len(_ID_RE.findall(text))


def entries(text: str) -> set[str]:
    return {m.strip() for m in _ID_RE.findall(text)}


def git_show(path: Path, rev: str = "HEAD") -> str | None:
    """Return the committed content of `path`, or None if it did not exist."""
    try:
        rel = path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        rel = path.as_posix()
    try:
        out = subprocess.run(
            ["git", "show", f"{rev}:{rel}"],
            capture_output=True,
            text=True,
            check=False,
            cwd=ROOT,
        )
    except FileNotFoundError:
        print("ERROR: git not found — cannot verify the baseline ratchet")
        raise SystemExit(1) from None
    if out.returncode != 0:
        return None
    return out.stdout


def rule_of_entry(entry: str) -> str:
    """The rule name an `<ID>` belongs to.

    Entries look like `RuleName:File.kt:Fqcn$signature`. A rule name cannot
    contain a colon, so the first colon delimits it. The signature half is
    free-form and is not parsed.

    The `<ID>` wrapper is stripped first. `entries()` deliberately keeps the
    tags so that the growth report prints the element verbatim, which means
    anything that wants the *rule* has to remove them itself — slicing the
    tagged string at its first colon silently yields `<ID>RuleName`, which
    matches nothing. That is not hypothetical: it is what this function did the
    first time, and the invariant it fed reported zero phantoms on a baseline
    that had one planted in it.
    """
    body = entry.strip()
    if body.startswith("<ID>"):
        body = body[len("<ID>") :]
    if body.endswith("</ID>"):
        body = body[: -len("</ID>")]
    return body.split(":", 1)[0]


def suppressed_for_disabled(text: str, disabled: dict[str, int]) -> list[str]:
    """Entries in `text` whose rule is declared `active: false`."""
    return sorted(
        {e for e in entries(text) if disabled.get(rule_of_entry(e))}
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--baseline",
        action="append",
        default=None,
        help="baseline file to ratchet; repeatable. Default: both module baselines.",
    )
    parser.add_argument(
        "--config",
        default="config/detekt/detekt.yml",
        help=(
            "detekt config to read `active: false` from. Default: detekt.yml. "
            "A module on a different config (mcp-server uses detekt-minimal.yml) "
            "must be named explicitly or the disabled set will be wrong for it."
        ),
    )
    parser.add_argument(
        "--max-growth",
        type=int,
        default=0,
        help="entries allowed to be added per file per run (default: 0)",
    )
    args = parser.parse_args()

    # Both module baselines are ratcheted. This originally watched only
    # `baseline-shared.xml`, which left `baseline-desktopApp.xml` free to grow
    # unchecked — and it had already drifted from 3 entries to 32 before anyone
    # noticed. A ratchet that watches one of the two files is a half-ratchet.
    baselines = (
        [Path(p) for p in args.baseline]
        if args.baseline
        else [
            ROOT / "config" / "detekt" / "baseline-shared.xml",
            ROOT / "config" / "detekt" / "baseline-desktopApp.xml",
        ]
    )
    config_path = ROOT / args.config
    if not config_path.is_file():
        print(f"ERROR: detekt config not found: {config_path}")
        return 1
    disabled = disabled_rules(config_path.read_text(encoding="utf-8"))

    failed = False
    for baseline in baselines:
        if not check_one(baseline, args.max_growth, disabled):
            failed = True
    return 1 if failed else 0


def check_one(baseline: Path, max_growth: int, disabled: dict[str, int]) -> bool:
    if not baseline.is_file():
        print(f"ERROR: baseline not found: {baseline}")
        return False

    current_text = baseline.read_text(encoding="utf-8")
    current = count_entries(current_text)
    name = baseline.name

    # A phantom invariant, checked before the ratchet. An entry for a rule that
    # is switched off is not debt, it is a claim the build can no longer make:
    # detekt will never report it, so it neither fails nor protects anything,
    # and it inflates the count the ratchet compares. 90 of 503 entries were
    # exactly this when the invariant was added — 53 for BackingPropertyNaming
    # and 26 for Filename, both disabled in detekt.yml with a written reason.
    # Because the ratchet only ever fails on *growth*, those 90 free entries
    # were a hole: 90 units of real growth would have passed it.
    phantoms = suppressed_for_disabled(current_text, disabled)
    if phantoms:
        by_rule: dict[str, int] = {}
        for item in phantoms:
            rule = rule_of_entry(item)
            by_rule[rule] = by_rule.get(rule, 0) + 1
        print("")
        print(
            f"check-baseline-ratchet: FAIL — {name} suppresses "
            f"{len(phantoms)} finding(s) for rules declared `active: false`"
        )
        for rule, n in sorted(by_rule.items(), key=lambda kv: -kv[1]):
            print(f"  {n:4}  {rule}")
        print("")
        print("These entries suppress nothing: the rule is switched off, so it")
        print("cannot report. They inflate the count the ratchet compares, which")
        print("means the suppressed count also under-reports real growth by this")
        print("much. Fix by regenerating the baseline — a disabled rule")
        print("contributes no current findings, so the entries disappear on their")
        print("own:")
        print(f"  ./gradlew :{name.replace('baseline-', '').replace('.xml', '')}:detektBaseline")
        print("")
        print("If instead a rule was disabled by mistake and the findings are")
        print("real, set it back to `active: true` in config/detekt/detekt.yml and")
        print("say why in a comment. Do not silence this check.")
        return False

    committed_text = git_show(baseline)
    if committed_text is None:
        print(
            f"check-baseline-ratchet: {name} is new in this branch "
            f"({current} entries) — ratcheting from this commit"
        )
        return True

    committed = count_entries(committed_text)
    growth = current - committed

    if growth > max_growth:
        added = sorted(entries(current_text) - entries(committed_text))
        print("")
        print(
            f"check-baseline-ratchet: FAIL — {name} grew by {growth} "
            f"entry(ies) ({committed} -> {current})"
        )
        print("")
        print("Newly suppressed findings:")
        for item in added[:20]:
            print(f"  + {item}")
        if len(added) > 20:
            print(f"  ... and {len(added) - 20} more")
        print("")
        print("A baseline is a ratchet, not a place to park new debt. Either")
        print("fix the finding, or -- if a rule was just enabled and the")
        print("pre-existing volume is genuinely being baselined on purpose --")
        print("pass --max-growth N in a commit whose message says why.")
        return False

    if growth < 0:
        print(
            f"check-baseline-ratchet: OK -- {name} shrank by {-growth} "
            f"entry(ies) ({committed} -> {current})"
        )
    else:
        print(f"check-baseline-ratchet: OK -- {name} unchanged ({current} entries)")
    return True


if __name__ == "__main__":
    sys.exit(main())
