#!/usr/bin/env python3
"""check-rule-intent.py — a rule that reports findings must be a rule you chose.

Why this exists (2026-10-04): `check-gate-wiring.py` proved every *gate* is
reachable and can fail. This is the same question one level down, for *lint
rules*.

A detekt rule that appears nowhere in `config/detekt/detekt.yml` runs on detekt's
built-in default configuration. That is invisible in review — the rule is not off,
it is not misconfigured, it is simply *default-on*, which means nobody ever
decided it should apply. Eighteen such rules were producing **247 of the 428
entries** in `baseline-shared.xml` at the time this check was written, and two of
them were actively wrong:

- `BackingPropertyNaming` flagged 53 sites that follow AGENTS.md's canonical VM
  pattern (`private val _state = MutableStateFlow(…)`). Enforcing it would mean
  renaming 53 sites and rewriting the documented pattern — a style default
  beating the architecture.
- `MaximumLineLength` enforced detekt's default of 120 while `.editorconfig`
  declares 140, so the stricter number silently won.

Both are now declared explicitly. This check is what keeps the 19th from
appearing unannounced.

The check is one-directional on purpose: a rule that reports findings and has no
declaration is a failure. Adding a declaration for a rule that reports nothing is
harmless and is not policed, because a rule may legitimately be active and clean.

Usage:
    python3 scripts/check-rule-intent.py [--baseline <path>]

Exit codes:
    0 — every reporting rule is declared
    1 — a rule reports findings without a declaration in detekt.yml
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "detekt" / "detekt.yml"
DEFAULT_BASELINE = ROOT / "config" / "detekt" / "baseline-shared.xml"

# Rule names that are not detekt rules at all: our own custom rule sets live in
# the `detekt-rules/` module and are registered through detekt.yml separately.
# Anything matching this is still expected to be declared (it is), but the check
# does not need to special-case them, so there is no allowlist here on purpose.

_RULE_NAME = re.compile(r"<ID>([A-Za-z][A-Za-z0-9_]*):")
_DECLARED_RULE = re.compile(r"^\s{2,6}([A-Za-z][A-Za-z0-9_-]*):\s*(?:$|#)", re.M)


def _norm(name: str) -> str:
    """Case- and separator-insensitive rule key.

    A baseline entry and its config key do not always use the same spelling: the
    ktlint wrapper reports `BackingPropertyNaming` while its config key is
    `backing-property-naming`, and detekt's own `MaxLineLength` appears as
    `MaximumLineLength` in findings from some paths. Comparing the raw strings
    would report all of those as undeclared, which is why this exists.
    """
    return re.sub(r"[^a-z0-9]", "", name.lower())


def declared_rules(config_text: str) -> set[str]:
    """Normalised names of every rule key that appears in detekt.yml.

    Matches an indented `CamelCaseName:` or `kebab-case-name:` key with nothing or
    a comment after it, which is how a rule block is written. Two-space indent is
    the ruleset level and four is the rule level; both are declarations.
    """
    return {_norm(m) for m in _DECLARED_RULE.findall(config_text)}


def baseline_rules(baseline_text: str) -> Counter[str]:
    """Rule name -> finding count, from a detekt baseline file."""
    return Counter(_RULE_NAME.findall(baseline_text))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--baseline",
        default=str(DEFAULT_BASELINE),
        help="detekt baseline to read findings from (default: config/detekt/baseline-shared.xml)",
    )
    args = ap.parse_args()

    if not CONFIG.is_file():
        print(f"ERROR: {CONFIG} not found")
        return 1
    baseline_path = Path(args.baseline)
    if not baseline_path.is_file():
        print(f"ERROR: {baseline_path} not found")
        return 1

    declared = declared_rules(CONFIG.read_text(encoding="utf-8"))
    reporting = baseline_rules(baseline_path.read_text(encoding="utf-8"))

    undeclared = {
        rule: n for rule, n in reporting.items() if _norm(rule) not in declared
    }
    declared_and_reporting = {
        rule: n for rule, n in reporting.items() if rule in declared
    }

    print(f"declared in detekt.yml : {len(declared)}")
    print(f"reporting in baseline   : {len(reporting)} ({sum(reporting.values())} findings)")
    print(f"  declared + reporting  : {len(declared_and_reporting)}")
    print(f"  UNDECLARED            : {len(undeclared)}")
    for rule, n in sorted(undeclared.items(), key=lambda kv: -kv[1]):
        print(f"    {rule:36} {n:3d} finding(s)")

    if undeclared:
        print("")
        print("These rules are running on detekt's built-in defaults: they were never")
        print("named in config/detekt/detekt.yml, so nobody decided they apply. Add each")
        print("one with an explicit `active:` and a comment giving the reason — the")
        print("reason is the valuable part. If a default contradicts a documented")
        print("convention, `active: false` with a pointer to that convention is the")
        print("correct answer, not a code change to match the default.")
        return 1

    print("")
    print("check-rule-intent: OK — every reporting rule is a declared decision")
    return 0


if __name__ == "__main__":
    sys.exit(main())
