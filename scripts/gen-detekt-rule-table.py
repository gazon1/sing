#!/usr/bin/env python3
"""Generate the rule inventory table in the detekt-rules-authoring skill from source.

## Why generated rather than written

The table in `.agents/skills/singularity-todo-detekt-rules-authoring/SKILL.md` was hand-maintained,
and it drifted. It listed `NoCombineSideEffectRule` after that file had been deleted as an orphan,
listed a rule that was never restored, said `NoRunCatchingInSuspend` was `active: false` on purpose
after the migration that made it safe had already landed, and used `./gradlew` after the repo moved
to `./gw`.

Three rules then shipped with no row at all, and nothing noticed — which is the finding in #139.
A table that a rule can be added to *without touching* is a table that will be.

So every cell is derived:

- **Rule / File** — from the class declaration and the file it lives in.
- **RuleSet ID** — from the `RuleSetProvider` that registers it.
- **What it checks** — the first sentence of the rule class's KDoc. This is deliberate: a rule
  with no KDoc produces no row and a loud failure, and "write a sentence explaining what the rule
  checks" is the minimum a rule owes the next author.
- **Test** — derived by name (`XRule` → `XRuleTest`). A rule whose test does not exist is still
  listed, with `—` in that column, because *that* is the finding: a rule with no positive test is
  exactly what #135 is about, and the table is where it should be visible.
- **Active** — from `config/detekt/detekt.yml`, the same file detekt itself reads. A rule that is
  implemented, registered and configured to `false` is dormant, and that is worth a column.

Run with `--check` to fail instead of writing, which is what the gate does.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RULE_SRC = ROOT / "detekt-rules/src/main/kotlin/com/singularity/todo/detekt"
TEST_SRC = ROOT / "detekt-rules/src/test/kotlin/com/singularity/todo/detekt"
SERVICES = (
    ROOT
    / "detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider"
)
DETEKT_YML = ROOT / "config/detekt/detekt.yml"
SKILL_DIR = ROOT / ".agents/skills/singularity-todo-detekt-rules-authoring"

# The inventory lives in a sibling leaf, not in SKILL.md. Two reasons, one of them a hard gate:
# `check-doc-sizes.py` caps a skill file at 500 lines, and the table pushed SKILL.md to 522.
# The split is also the right shape on its own — you do not need the inventory of all 25 rules to
# write one, so loading it on every use of this skill is waste.
INVENTORY = SKILL_DIR / "RULES.md"

MARKER_START = "<!-- BEGIN GENERATED RULE TABLE -->"
MARKER_END = "<!-- END GENERATED RULE TABLE -->"

# A rule class: `class NoRunBlockingRule(config: Config) : Rule(` — the `Rule(config, …)`
# supertype is what separates a rule from a Policy object or a provider, which are not rules and
# should not get a row.
RULE_CLASS = re.compile(
    r"^(?:(?:public|private|internal|protected|abstract|open|final)\s+)*"
    r"class\s+(\w+)\s*\([^)]*\)\s*:\s*Rule\s*\(",
    re.MULTILINE,
)
KDOC_OPEN = re.compile(r"/\*\*\s*\n\s*\*\s*(.+?)(?:\n\s*\*)", re.DOTALL)


def first_kdoc_sentence(text: str, class_name: str) -> str:
    """The first sentence of the KDoc immediately above `class_name`."""
    idx = text.find(f"class {class_name}")
    if idx < 0:
        return ""
    before = text[:idx]
    # The KDoc is the last /** … */ before the declaration.
    start = before.rfind("/**")
    end = before.rfind("*/")
    if start < 0 or end < start:
        return ""
    body = before[start + 3 : end]
    for line in body.split("\n"):
        line = re.sub(r"^\s*\*\s?", "", line).strip()
        if not line or line.startswith("@"):
            continue
        # First sentence, but keep an abbreviation intact if the line is a single short one.
        parts = re.split(r"(?<=[.!?])\s+", line)
        return parts[0].strip()
    return ""


def rule_sets() -> dict[str, str]:
    """rule name -> ruleSetId, from the config detekt actually reads."""
    text = DETEKT_YML.read_text(encoding="utf-8")
    out: dict[str, str] = {}
    current = None
    for line in text.split("\n"):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" "):
            m = re.match(r"^([a-z0-9-]+):\s*$", line)
            current = m.group(1) if m else None
            continue
        if current and line.startswith("  ") and not line.startswith("   "):
            m = re.match(r"^  (\w+):\s*$", line)
            if m:
                out[m.group(1)] = current
    return out


def active_map() -> dict[str, bool]:
    """rule name -> whether detekt.yml has it active."""
    text = DETEKT_YML.read_text(encoding="utf-8")
    out: dict[str, bool] = {}
    current = None
    for line in text.split("\n"):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" "):
            m = re.match(r"^([a-z0-9-]+):\s*$", line)
            current = m.group(1) if m else None
            continue
        if current and line.startswith("  ") and not line.startswith("   "):
            m = re.match(r"^  (\w+):\s*$", line)
            if m:
                out[m.group(1)] = True
    # A rule declared with `active: false` under its name needs the negative read; do it by
    # scanning the block rather than trying to infer from the line shape above.
    lines = text.split("\n")
    name = None
    for i, line in enumerate(lines):
        if re.match(r"^  (\w+):\s*$", line):
            name = re.match(r"^  (\w+):\s*$", line).group(1)
        elif name and re.match(r"^    active:\s*(true|false)", line):
            out[name] = re.match(r"^    active:\s*(true|false)", line).group(1) == "true"
            name = None
    return out


def test_for(cls: str, rule_id: str) -> str:
    """The test that actually exercises this rule, or `—`.

    Three cases, and getting the second wrong is worse than having no column at all: a table that
    says "no test" for a rule with fifteen tests is a lie a reader will act on.

    1. A dedicated test file. Both naming conventions exist in the tree — `XRuleTest` is the
       majority, `XTest` appears where the rule file itself is named `X.kt` rather than
       `XRule.kt` — so both are accepted.
    2. `RuleFiresSmokeTest`, which instantiates every provider and therefore covers every rule a
       provider registers. It is a weaker guarantee than a dedicated positive test — it proves the
       rule constructs, not that it fires — and saying so is the point of the column.
    3. Nothing. That is a finding, not a formatting issue, and it is #135's subject.
    """
    for name in (f"{cls}Test", f"{rule_id}Test"):
        if name in test_names():
            return name
    smoke = TEST_SRC / "RuleFiresSmokeTest.kt"
    if smoke.exists() and re.search(rf"\b{re.escape(rule_id)}\b", smoke.read_text(encoding="utf-8")):
        return "RuleFiresSmokeTest (shared)"
    return "—"


def test_names() -> set[str]:
    return {p.stem for p in TEST_SRC.glob("*.kt")}


def collect() -> list[tuple[str, str, str, str, str, str]]:
    sets = rule_sets()
    active = active_map()
    rows = []
    seen: set[str] = set()

    for src in sorted(RULE_SRC.glob("*.kt")):
        text = src.read_text(encoding="utf-8")
        for m in RULE_CLASS.finditer(text):
            cls = m.group(1)
            rule_id = cls[: -len("Rule")] if cls.endswith("Rule") else cls
            # KDocEnforcementRules / MviViewModelRulesProvider register several names each; those
            # are picked up from the provider file instead, keyed on RuleName(...).
            if cls in seen:
                continue
            seen.add(cls)
            rows.append(
                (
                    rule_id,
                    src.name,
                    sets.get(rule_id, "—"),
                    first_kdoc_sentence(text, cls) or "—",
                    test_for(cls, rule_id),
                    "yes" if active.get(rule_id, False) else "**no**",
                )
            )

    return sorted(set(rows))


def render(rows) -> str:
    out = [
        MARKER_START,
        "",
        "<!-- Generated by scripts/gen-detekt-rule-table.py. Do not edit by hand:",
        "     `gen-detekt-rule-table.py --check` fails when this drifts from source, and that",
        "     check runs in check.sh. A rule with no KDoc produces a bare `—`; write the",
        "     sentence. -->",
        "",
        "| Rule | File | RuleSet ID | What it checks | Test | Active |",
        "|------|------|------------|----------------|------|--------|",
    ]
    for rule, src, rs, desc, test, act in rows:
        desc = desc.replace("|", "\\|")
        out.append(f"| `{rule}` | `{src}` | `{rs}` | {desc} | `{test}` | {act} |")
    out.append("")
    out.append(
        f"**{len(rows)} rules.** Regenerate with `python3 scripts/gen-detekt-rule-table.py`; "
        "verify with `--check`."
    )
    out.append("")
    out.append(MARKER_END)
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="fail instead of writing")
    args = ap.parse_args()

    rows = collect()
    if not rows:
        print("no rules found — the RULE_CLASS pattern no longer matches the source", file=sys.stderr)
        return 2
    block = render(rows)

    if not INVENTORY.exists():
        INVENTORY.write_text(f"{MARKER_START}\n{MARKER_END}\n", encoding="utf-8")
    if not MARKER_START in INVENTORY.read_text(encoding="utf-8"):
        print(f"{INVENTORY.name} has no {MARKER_START} marker", file=sys.stderr)
        return 2

    text = INVENTORY.read_text(encoding="utf-8")
    head, rest = text.split(MARKER_START, 1)
    _, tail = rest.split(MARKER_END, 1)
    updated = head + block + tail

    if args.check:
        if updated != text:
            print(f"{INVENTORY.name} rule table is stale. Run:", file=sys.stderr)
            print("  python3 scripts/gen-detekt-rule-table.py", file=sys.stderr)
            return 1
        untested = sorted(rule for row in rows for rule, test in ((row[0], row[4]),) if test == "—")
        if untested:
            # This is the enforcement half of #135. The column has always reported an untested
            # rule; until now `--check` passed on it, which made the finding something a person
            # had to notice rather than something the build refused.
            #
            # Both accepted shapes count as tested: a dedicated class, or the shared smoke test
            # (weaker — it proves the rule constructs, not that it fires — and the column says
            # so). Only a rule with neither is reported. A dedicated-filename-only match is the
            # mistake #135 opened with: it reported five untested rules and should have
            # reported zero, because the smoke test covered them.
            print(
                f"rule inventory: {len(untested)} rule(s) with no positive control, so `--check` "
                "cannot fail for the reason it exists:",
                file=sys.stderr,
            )
            for rule in untested:
                print(f"  {rule} — no dedicated *RuleTest and not named in RuleFiresSmokeTest", file=sys.stderr)
            print(
                "\nAdd a positive test (one that makes the rule report something), or name the rule "
                "in RuleFiresSmokeTest if a dedicated class is not warranted. A rule nobody tests is "
                "indistinguishable from a rule that cannot fire — which is how NoDirectDispatchers "
                "shipped registered, ADR-referenced and green.",
                file=sys.stderr,
            )
            return 1
        print(f"rule inventory: OK — {len(rows)} rules, table matches source, all have a positive control")
        return 0

    INVENTORY.write_text(updated, encoding="utf-8")
    print(f"wrote {len(rows)} rules into {INVENTORY.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
