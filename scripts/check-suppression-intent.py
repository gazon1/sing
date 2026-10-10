#!/usr/bin/env python3
"""check-suppression-intent.py — a file-level suppression must say what it is hiding.

Why this exists (2026-10-05): `check-rule-intent.py` asks whether a rule was
*declared* in `config/detekt/detekt.yml`. This asks the other half. A rule can
be perfectly declared, correctly configured, and provably able to fire — and
still be switched off for a whole file by one line that no gate can see.

`@file:Suppress("SomeRule")` is the widest tool the language offers. It does not
say *this call* is acceptable; it says *nothing in this file is checked*, which
includes every future call nobody has written yet. Measured on 2026-10-05: **12**
production files carry `@file:Suppress("NoDirectClockSystem")` with no comment,
accounting for **38** direct `Clock.System` references. The rule itself is fine —
probed, it fires on a new file within seconds — and the file is
`config/detekt/baseline-shared.xml`, which holds exactly **one** suppression for
it. So the baseline and the file-level suppressions disagreed about how much of
that rule was in force, and only one of the two is visible in review.

`docs/decisions/deferred-backlog.md` closes the `empty-handler-lambdas` entry
with "**Do not** blanket-suppress these to make the count drop. That is the move
that produced this entry." The rule was written down for one finding and then
broken twelve times, which is what happens to a written rule that nothing
enforces.

## Scope, and why it is this narrow

Only **custom** rule ids — the ones this repository defines in `detekt-rules/`.
A file-level `@file:Suppress("LongMethod")` on a 400-line Composable is ordinary
practice and is not what this gate is about; detekt's own defaults are not a
decision this repository made, so silencing one is a local call. The ids are
**derived** from the rules themselves (`RuleName("…")` in the provider sources),
not from a list, so a rule registered tomorrow is covered the moment it exists.
That is the same derivation `check-gate-wiring.py` Part F applies to gates, and
the reason this file is not a fourth copy of the predicate.

Two escapes, both explicit:

- **A reason on the next lines.** A comment naming why the whole file is exempt
  is a decision somebody wrote down, and review can argue with it.
- **`JUSTIFIED_FILE_SUPPRESSIONS`.** For a file where the exemption is structural
  — a fake, a preview surface — the entry carries the reason in the registry,
  because a comment above `@file:Suppress` is invisible to anyone reading the
  class.

Usage:
    python3 scripts/check-suppression-intent.py
    python3 scripts/check-suppression-intent.py --list

Exit codes:
    0 — every file-level custom-rule suppression is justified
    1 — an unjustified one, or a registry entry that no longer matches anything
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULE_SOURCES = ROOT / "detekt-rules" / "src" / "main" / "kotlin"
PROVIDER_SERVICE = (
    ROOT / "detekt-rules" / "src" / "main" / "resources"
    / "META-INF" / "services" / "dev.detekt.api.RuleSetProvider"
)

# Where production code lives. A test double in `test/fakes/` is exempt through
# the registry below, not through this list — the list is "not a test source
# set", which is a different and much weaker claim.
SOURCE_ROOTS = [
    ROOT / "shared" / "src" / "commonMain" / "kotlin",
    ROOT / "shared" / "src" / "jvmMain" / "kotlin",
    ROOT / "shared" / "src" / "androidMain" / "kotlin",
    ROOT / "androidApp" / "src" / "main" / "kotlin",
    ROOT / "desktopApp" / "src" / "jvmMain" / "kotlin",
    ROOT / "mcp-server" / "src" / "main" / "kotlin",
]

FILE_SUPPRESS = re.compile(r'@file:Suppress\(\s*(?P<body>[^)]*)\)')
QUOTED = re.compile(r'"([^"]+)"')


def custom_rule_ids() -> set[str]:
    """Every rule id this repository defines, derived from the rule sources.

    `RuleName("X") to { … }` is the only place an id becomes real: it is what
    detekt registers, what a `@Suppress` has to name, and what `check-rule-intent.py`
    matches against `detekt.yml`. Reading it from the providers means a new rule
    is covered without anyone remembering to add it here.
    """
    if not RULE_SOURCES.is_dir():
        return set()
    ids: set[str] = set()
    for path in RULE_SOURCES.rglob("*.kt"):
        ids.update(re.findall(r'RuleName\(\s*"([A-Za-z0-9_-]+)"\s*\)', path.read_text(encoding="utf-8")))
    return ids


def provider_names() -> set[str]:
    """RuleSetProvider class names registered through the service loader.

    Kept separate from `custom_rule_ids` because the two answer different
    questions: an id is what a `@Suppress` names, a provider is what detekt
    loads. A provider with no `RuleName` is a rule set that registers nothing,
    which is a different defect and is not this gate's business.
    """
    if not PROVIDER_SERVICE.is_file():
        return set()
    return {
        line.strip().rsplit(".", 1)[-1]
        for line in PROVIDER_SERVICE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }


def _is_test_path(path: Path) -> bool:
    rel = path.relative_to(ROOT)
    parts = rel.parts
    return any(p.endswith("Test") or p == "test" for p in parts) or path.name.endswith("Test.kt")


def scan_file_suppressions() -> list[tuple[Path, str, str, int]]:
    """`(path, rule_id, rest_of_annotation_block, line_number)` per custom id."""
    ids = custom_rule_ids()
    if not ids:
        return []
    found: list[tuple[Path, str, str, int]] = []
    for root in SOURCE_ROOTS:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.kt")):
            if _is_test_path(path):
                continue
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
            in_block_comment = False
            for index, line in enumerate(lines):
                match = None if in_block_comment else FILE_SUPPRESS.search(line)
                # A KDoc that *names* the annotation — which is what the notes
                # explaining a removal have to do — is prose, not a suppression.
                # Without this, writing down why a `@file:Suppress` was deleted
                # re-registers it, and the gate then demands a justification for
                # a justification. Found by doing exactly that.
                if match and not _inside_block_comment(lines, index):
                    for quoted in QUOTED.findall(match.group("body")):
                        if quoted in ids:
                            block = " ".join(
                                ln.strip() for ln in lines[index:index + 4] if ln.strip()
                            )
                            found.append((path, quoted, block, index + 1))
                in_block_comment = _block_comment_state(lines, index, in_block_comment)
    return found


def _block_comment_state(lines: list[str], index: int, was_open: bool) -> bool:
    """Track whether line `index` leaves a `/* … */` comment open."""
    text = lines[index]
    if was_open:
        return "*/" not in text
    if text.lstrip().startswith("/*") and "*/" not in text:
        return True
    return False


def _inside_block_comment(lines: list[str], index: int) -> bool:
    """Is line `index` itself inside a block comment?"""
    state = False
    for earlier in range(0, index + 1):
        state = _block_comment_state(lines, earlier, state)
        if earlier == index:
            return state
    return state


def has_adjacent_reason(block: str) -> bool:
    """Is there a comment on the annotation line or the lines right after it?

    A comment anywhere later in the file does not count. The reason has to be
    where a reader meets the suppression, or it is a note about something else
    that happens to live in the same class.
    """
    if "//" in block:
        comment = block.split("//", 1)[1].strip()
        if comment and comment not in (")", "*/"):
            return True
    if "/*" in block:
        return True
    return False


# Files whose exemption is structural, with the reason recorded where review can
# find it. Two shapes are represented, and they are not interchangeable:
#
#   * a *live defect* — the file really does read the system clock and a test
#     cannot supply a different one. Recorded here so the gate can pass while the
#     fix is tracked, rather than suppressing the gate instead. Each of these
#     names its issue, because "we will get to it" without a number is how a
#     permanent exemption becomes invisible.
#   * a *constructural exemption* — the file is where such a read belongs.
#
# Every entry names the reason, is checked for a real file, and is checked for
# still carrying the suppression it justifies. A registry that cannot notice its
# own staleness is a sink, not a list.
JUSTIFIED_FILE_SUPPRESSIONS: dict[str, str] = {
    # ── Live defects, tracked. ────────────────────────────────────────────────
    #
    # Kept as a section even though it is now empty: the four entries that lived
    # here were fixed on 2026-10-05 (CalendarDiModule, NoteEditor,
    # ProfileSwitcherViewModel, RemoteConfigCacheRepositoryImpl — all of #91), and
    # the section is where the next one goes. An empty list is the honest state; a
    # deleted section is a shape that has to be re-invented.
    #
    # ── Live injection, blanket suppression nonetheless. The parameter is honoured;
    #    only its *default* names the system clock, and the rule reads that as a call.
    # Entries removed on 2026-10-10: NoDirectClockSystemRule is retired (#160, ADR
    # 2026-10-10-no-direct-clock-system-rule-retired). SavedAgendaViewsRepositoryImpl
    # and SavedAgendaViewModel no longer carry @file:Suppress("NoDirectClockSystem").
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--list", action="store_true", help="print every finding and exit"
    )
    args = parser.parse_args()

    ids = custom_rule_ids()
    if not ids:
        print(
            "ERROR: no custom rule ids derived from detekt-rules/. The check cannot "
            "run, and reporting success here would be a vacuous green — exactly the "
            "failure this file exists to catch.",
            file=sys.stderr,
        )
        return 1

    findings = scan_file_suppressions()
    errors: list[str] = []

    print(f"custom rule ids derived from detekt-rules/: {len(ids)}")
    print(f"rule set providers registered        : {len(provider_names())}")
    print(f"file-level custom suppressions found : {len(findings)}")

    for path, rule_id, block, line in findings:
        rel = path.relative_to(ROOT)
        entry = JUSTIFIED_FILE_SUPPRESSIONS.get(str(rel))
        state = "justified" if entry or has_adjacent_reason(block) else "UNJUSTIFIED"
        print(f"  {state:11} {rel}:{line}  {rule_id}")

        if state == "justified":
            continue
        errors.append(
            f"{rel}:{line} — @file:Suppress(\"{rule_id}\") with no reason. "
            f"A file-level suppression says nothing in this file is checked, "
            f"including calls nobody has written yet. Put the reason on the line "
            f"below, or add the path to JUSTIFIED_FILE_SUPPRESSIONS in "
            f"scripts/check-suppression-intent.py with it."
        )

    for path, reason in sorted(JUSTIFIED_FILE_SUPPRESSIONS.items()):
        if not reason.strip():
            errors.append(
                f"{path} is in JUSTIFIED_FILE_SUPPRESSIONS with an empty reason. "
                f"An exemption with no defence is an exemption nobody can review."
            )
        if not (ROOT / path).is_file():
            errors.append(
                f"{path} is in JUSTIFIED_FILE_SUPPRESSIONS but does not exist. "
                f"A stale entry is an exemption for a file that was fixed or moved."
            )
        elif str(path) not in {str(p.relative_to(ROOT)) for p, _, _, _ in findings}:
            errors.append(
                f"{path} is in JUSTIFIED_FILE_SUPPRESSIONS but no longer carries a "
                f"file-level custom-rule suppression. Remove the entry, or it will "
                f"hide a future suppression of any kind."
            )

    if args.list:
        for err in errors:
            print(f"ERROR: {err}")
        return 1 if errors else 0

    if errors:
        print("")
        for err in errors:
            print(f"ERROR: {err}")
        print("")
        print("A rule that is switched off silently is not a rule. Silence and a clean")
        print("result are the same observation, which is the only reason this exists.")
        return 1

    print("check-suppression-intent: OK — every file-level custom suppression is justified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
