#!/usr/bin/env python3
"""Regenerate config/detekt/detekt-rules-module.yml from config/detekt/detekt.yml.

A module cannot apply the detekt rules it defines to itself: the custom rules live in
:detekt-rules, so they are not on that module's classpath until it is built. Applying the
full detekt.yml to :detekt-rules therefore fails on every custom rule as unresolvable.

Rather than hand-maintaining a second config (which drifts the moment detekt.yml
changes), this drops only the custom rule-set blocks and copies everything else — so
ktlint formatting, the function-count and complexity settings, and any future built-in
tuning stay single-sourced in detekt.yml.

The config/provider relationship that this file cannot check is asserted by
detekt-rules/src/test/kotlin/com/singularity/todo/detekt/DetektConfigWiringTest.kt.

Usage: python3 scripts/gen-detekt-rules-config.py [--check]
  --check  exit non-zero if the generated file is stale (for CI)
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'config' / 'detekt' / 'detekt.yml'
DST = ROOT / 'config' / 'detekt' / 'detekt-rules-module.yml'
RULES_SRC = ROOT / 'detekt-rules' / 'src' / 'main' / 'kotlin' / 'com' / 'singularity' / 'todo' / 'detekt'

HEADER = """# detekt config for the :detekt-rules module itself.
#
# GENERATED — do not edit by hand. Regenerate with:
#   python3 scripts/gen-detekt-rules-config.py
#   python3 scripts/gen-detekt-rules-config.py --check   # CI: fail if stale
#
# This is config/detekt/detekt.yml with the custom rule-set blocks removed. A module
# cannot apply the rules it defines to itself: the custom rules live in :detekt-rules and
# are not on its classpath until it is built, so the full config would fail on every one
# of them as unresolvable.
#
# Everything else is copied verbatim, so ktlint formatting and the built-in rule tuning
# stay single-sourced in detekt.yml. The custom rules are covered by :detekt-rules:test —
# including DetektConfigWiringTest, which asserts that every configured rule name is
# supplied by a registered provider and that every provided rule has a config block.
#
# ── Module-specific overrides ───────────────────────────────────────────────────
# Appended below, and regenerated with the rest, so they cannot silently disappear.

"""

# ReturnCount: raised from 4 to 16.
#
# Rule logic is a chain of guard clauses, and that is not a style preference here — it is
# what makes an unsatisfiable guard *visible*. Two rules shipped that could never fire
# because of exactly this shape: each had a guard no input could satisfy, hidden in the
# middle of an early-return chain. See
# docs/decisions/2026-10-05-no-direct-dispatchers-rule-was-a-no-op.
#
# Rewriting nine guard chains to satisfy a count would make each denser and the guards
# harder to audit individually, which is the opposite of what a rule is for. The limit is
# not removed: 16 still catches a genuinely sprawling function, and
# PassThroughUseCaseRule.checkFunction — 13 returns, the worst here — is the honest upper
# bound rather than an accident.
OVERRIDES = [
    ("style", """  ReturnCount:
    # See the module header: guard clauses are how an unsatisfiable guard stays visible.
    active: true
    max: 16"""),
    ("ktlint", """  # The ktlint `filename` rule (reported as "contains a single class") fires on
  # KDocEnforcementRules.kt, which groups a cohesive pair of private top-level rule
  # classes in one file. One file per private class would fragment it for no benefit.
  Filename:
    active: false"""),
]


def _drop_rule_block(lines: list[str], rule: str) -> list[str]:
    """Remove an existing `  <rule>:` block from a section, so the override replaces it.

    Emitting a second block with the same name is a duplicate YAML key, which detekt
    rejects outright — the same failure mode check-detekt-registrations.sh exists to catch.
    """
    out: list[str] = []
    i = 0
    while i < len(lines):
        if lines[i].rstrip() == f"  {rule}:":
            i += 1
            while i < len(lines) and (not lines[i].strip() or lines[i][:1] in (" ", "\t")):
                i += 1
            continue
        out.append(lines[i])
        i += 1
    return out


def _first_duplicate_key(text: str) -> tuple[str, int] | None:
    """The first key this YAML text repeats inside one mapping, or None.

    Implemented over the rendered text rather than by calling the gate script, so the
    generator stands alone: it must not depend on a gate that runs *after* it in order
    to avoid producing a file that gate rejects.
    """
    import yaml

    class StrictLoader(yaml.SafeLoader):
        pass

    def construct_mapping(loader, node, deep=False):
        mapping: dict = {}
        for key_node, _ in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in mapping:
                raise _DuplicateFound(key, node.start_mark.line + 1)
            mapping[key] = None
        return yaml.SafeLoader.construct_mapping(loader, node, deep)

    StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping)
    try:
        list(yaml.load_all(text, Loader=StrictLoader))
    except _DuplicateFound as exc:
        return exc.key, exc.line_no
    except yaml.YAMLError:
        # A malformed file is a different defect, and one this function must not
        # silently swallow into "no duplicates found".
        raise
    return None


class _DuplicateFound(Exception):
    def __init__(self, key: object, line_no: int) -> None:
        super().__init__(repr(key))
        self.key = key
        self.line_no = line_no


def _rule_name_of(block_lines: list[str]) -> str:
    """The rule an override block configures: its first line that is not a comment.

    Reading `block_lines[0]` was wrong, and the consequence was silent. Both overrides
    open with an explanatory comment, so the "rule name" came out as that comment text,
    `_drop_rule_block` found nothing to remove, and the block was appended alongside the
    copy already present — producing the `Filename:` duplicate key that made
    `:detekt-rules:detekt` refuse to load its config (ADR
    2026-10-07-three-files-that-only-one-gate-reads). The generator's own docstring
    warned about exactly this; the code just did not do what the docstring said.
    """
    for line in block_lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        return stripped.rstrip(":")
    return ""


def apply_overrides(body: str) -> str:
    """Merge each override into its existing top-level section, or append the section.

    Appending verbatim would produce a duplicate YAML key, which detekt rejects — the same
    'duplicated key' failure mode check-detekt-registrations.sh was written to catch.
    """
    lines = body.splitlines(keepends=True)
    for section, block in OVERRIDES:
        start = None
        for i, line in enumerate(lines):
            if line.rstrip() == f"{section}:":
                start = i
                break
        addition = [l + "\n" if l.strip() else "\n" for l in block.splitlines()]
        if start is None:
            if not lines[-1].endswith("\n"):
                lines.append("\n")
            lines.append(f"{section}:\n")
            lines.extend(addition)
            continue
        rule = _rule_name_of(addition)
        if rule:
            # Drop the existing rule first, then re-find the section end: dropping shifts
            # every index after it.
            lines = _drop_rule_block(lines, rule)
            start = next((i for i, l in enumerate(lines) if l.rstrip() == f"{section}:"), None)
            if start is None:
                lines.append(f"{section}:\n")
                lines.extend(addition)
                continue
        # End of this section: the next line that starts at column 0.
        end = len(lines)
        for j in range(start + 1, len(lines)):
            if lines[j].strip() and not lines[j][0].isspace():
                end = j
                break
        lines[end:end] = addition
    return "".join(lines)


def custom_rule_set_ids() -> set[str]:
    out = subprocess.run(
        ['grep', '-rhoE', r'RuleSetId\("[a-z0-9-]+"\)', str(RULES_SRC)],
        capture_output=True, text=True, check=False,
    ).stdout
    return {m.split('"')[1] for m in out.split() if m.startswith('RuleSetId(')}


def render(ids: set[str]) -> str:
    out: list[str] = []
    skipping = False
    for line in SRC.read_text().splitlines(keepends=True):
        if line and not line[0].isspace() and line.rstrip().endswith(':'):
            skipping = line.rstrip()[:-1] in ids
        if not skipping:
            out.append(line)
    return HEADER + apply_overrides(''.join(out))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()

    ids = custom_rule_set_ids()
    if not ids:
        sys.exit('no custom ruleSetId found — is detekt-rules in the expected place?')
    expected = render(ids)

    if args.check:
        current = DST.read_text() if DST.exists() else ''
        if current != expected:
            print(f'{DST.relative_to(ROOT)} is stale — run scripts/gen-detekt-rules-config.py')
            return 1
        print(f'detekt-rules-module.yml is up to date ({len(ids)} custom blocks excluded)')
        return 0

    # The file this writes is parsed by SnakeYAML, which rejects a repeated key outright
    # — so a duplicate here does not degrade the config, it makes :detekt-rules:detekt
    # unable to start. Checking the generator's own output is what makes that impossible
    # to ship: the rule it broke on was a bug in `_rule_name_of`, and the comment in
    # `apply_overrides` describing this failure did not prevent it.
    duplicate = _first_duplicate_key(expected)
    if duplicate is not None:
        key, line_no = duplicate
        print(
            f'refusing to write {DST.relative_to(ROOT)}: duplicate key {key!r} at line {line_no}. '
            f'SnakeYAML rejects the whole file, so the rule-set config would be unreachable.',
            file=sys.stderr,
        )
        return 1

    DST.write_text(expected)
    print(f'wrote {DST.relative_to(ROOT)} ({len(ids)} custom rule-set blocks excluded)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
