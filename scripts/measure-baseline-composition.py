#!/usr/bin/env python3
"""measure-baseline-composition.py — where the detekt suppressions actually are.

Why this exists (2026-10-05): a plan proposed that `BackingPropertyNaming`'s 53
suppressions were a rule being "decorated rather than enforced". The rule had
been switched off with a written reason two weeks earlier — the codebase
deliberately chose the opposite convention — and the 53 entries were
suppressions for a rule that could not report. They were phantom debt.

Nobody could see that from the baseline file. It carries no marker saying which
rules are live, so every number quoted about it had to be recomputed by hand,
and the hand computation was wrong. The same wrong number then reached a GitHub
issue as a confident claim about which rules were untested.

So the numbers are printed by a command, and that command is re-runnable:

    python3 scripts/measure-baseline-composition.py

It imports the parser from `check-baseline-ratchet.py` rather than repeating
it. Two hand-written parsers of the same YAML is how the ratchet and this
script would drift into disagreeing about which rules are disabled — and a
measurement tool that disagrees with the gate it supports is worse than none.

Usage:
  python3 scripts/measure-baseline-composition.py [--config <path>] [--top N]

Exit codes:
  0 — printed the table
  1 — a baseline or the config could not be read
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RATCHET = Path(__file__).resolve().parent / "check-baseline-ratchet.py"

DEFAULT_BASELINES = (
    "config/detekt/baseline-shared.xml",
    "config/detekt/baseline-desktopApp.xml",
    "config/detekt/baseline-androidApp.xml",
)


def _load_ratchet():
    spec = importlib.util.spec_from_file_location("_ratchet", RATCHET)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {RATCHET}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ratchet = _load_ratchet()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--config",
        default="config/detekt/detekt.yml",
        help="detekt config the modules analysed here use (default: detekt.yml)",
    )
    ap.add_argument("--top", type=int, default=12, help="how many rules to list")
    args = ap.parse_args()

    config_path = ROOT / args.config
    if not config_path.is_file():
        print(f"ERROR: detekt config not found: {config_path}", file=sys.stderr)
        return 1
    disabled = ratchet.disabled_rules(config_path.read_text(encoding="utf-8"))

    per_rule: Counter[str] = Counter()
    total = 0
    phantoms = 0
    phantom_rules: Counter[str] = Counter()
    per_file: list[tuple[str, int, int]] = []

    for rel in DEFAULT_BASELINES:
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        ids = ratchet.entries(text)
        n_all = len(ids)
        dead = ratchet.suppressed_for_disabled(text, disabled)
        n_dead = len(dead)
        per_file.append((rel, n_all, n_dead))
        total += n_all
        phantoms += n_dead
        for item in ids:
            per_rule[ratchet.rule_of_entry(item)] += 1
        for item in dead:
            phantom_rules[ratchet.rule_of_entry(item)] += 1

    if not per_file:
        print("ERROR: no baseline file found", file=sys.stderr)
        return 1

    print(f"detekt config: {args.config}")
    print(f"rules declared `active: false`: {len(disabled)}")
    print()
    print(f"{'baseline':44} {'entries':>8} {'phantom':>8}")
    for rel, n_all, n_dead in per_file:
        print(f"{rel:44} {n_all:>8} {n_dead:>8}")
    print(f"{'TOTAL':44} {total:>8} {phantoms:>8}")
    print()

    if phantoms:
        print(
            f"PHANTOM suppressions: {phantoms} entries name a rule that is switched "
            f"off, so detekt cannot report them. They inflate the count the ratchet "
            f"compares, which hides that much real growth."
        )
        for rule, n in phantom_rules.most_common():
            print(f"  {n:4}  {rule}")
        print()
        print("Fix by regenerating the baseline, not by editing the XML:")
        print("  ./gradlew :shared:detektBaseline :desktopApp:detektBaseline")
        print()

    print(f"top {args.top} rules by suppression count:")
    for rule, n in per_rule.most_common(args.top):
        flag = "  (disabled)" if rule in disabled else ""
        print(f"  {n:4}  {rule}{flag}")
    print()
    print(
        "A rule with a high count is not automatically wrong — a formatting rule "
        "the project has not adopted is expected to have many. Read it as: this "
        "many places disagree with the rule. Whether the rule or the code should "
        "change is a decision, and it belongs in detekt.yml with a reason."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
