#!/usr/bin/env python3
"""check-readme-claims.py — a number in the README must be the number in the tree.

Why this exists (2026-10-06): the README is the first thing a reader of a public
repository sees, and a wrong number in it is the cheapest way to lose them. The
scan that prompted this check found four claims that had drifted, in six places:

| Claim | README | Tree |
|---|---|---|
| MCP tools | 32 | 37 registered on JVM |
| Room schema version | v37 | `SCHEMA_VERSION = 38` |
| ADR count | "270+" | 492 files |
| Agent skills | "90+" | 115 |

None of them were lies. Each was true when written and left behind by the thing
it counted. That is exactly the class of defect a gate catches and a review does
not, because the README is edited rarely and the tree is edited daily.

## What is checked

Each claim below has a source of truth that is computed, not recorded:

1. **MCP tool count** — the `listOf(...)` inside the
   `single<List<ai.koog...Tool<*, *>>>` binding in the JVM DI module. That is the
   list `ToolRegistrar` registers verbatim, so it is the count, not an estimate.
   The Android module is asserted to declare the **same** tools, so the number in
   the README is not platform-specific — a divergence between the two is itself a
   finding, not something to paper over with a caveat.
2. **Room schema version** — `SCHEMA_VERSION` in `AppDatabase.kt`.
3. **ADR count** — files matching `docs/decisions/2026-*.md`.
4. **Skill count** — directories under `.agents/skills/` containing a `SKILL.md`.

The `+` suffix is accepted for the two counts that grow (`ADR`, `skills`) and
means "at least this many"; the stated number must still be no greater than the
actual one, so a README that says "270+" when the tree holds 100 fails. It does
not require exactness, because a doc that must be edited whenever a decision is
recorded is a doc that goes stale in a different way — by people editing the
source and not the README, out of spite.

## The positive control

Rules 2–4 are regexes over source text. The MCP count is a parse of a Kotlin
literal list. Both can silently stop matching.

- `--self-test` builds a synthetic tree containing a README whose claims
  contradict its sources, and asserts every one is reported; then it builds a
  consistent tree and asserts none is.
- `scripts/tests/test_check_readme_claims.py` asserts the same properties,
  including the `+` suffix and the platform-divergence case.

## Usage

    python3 scripts/check-readme-claims.py             # check the repository
    python3 scripts/check-readme-claims.py --self-test  # prove the rules fire

Exit codes:
    0 — every README claim matches the tree, and the rules work
    1 — a violation
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"

DB_FILE = ROOT / ("shared/src/commonMain/kotlin/com/singularity/todo/core/database"
                 "/AppDatabase.kt")
MCP_JVM = ROOT / ("shared/src/jvmMain/kotlin/com/singularity/todo/core/di"
                 "/AiToolsModule.jvm.kt")
MCP_ANDROID = ROOT / ("shared/src/androidMain/kotlin/com/singularity/todo/core/di"
                      "/AiToolsModule.android.kt")
ADR_DIR = ROOT / "docs" / "decisions"
SKILLS_DIR = ROOT / ".agents" / "skills"


class Violation(Exception):
    """A check that did not hold."""


def _rel(path: Path) -> str:
    """Repo-relative when possible, absolute otherwise.

    The self-test builds trees under a temp dir, and `relative_to(ROOT)` on those
    raises — turning a useful error message into a traceback from inside the
    exception handler.
    """
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


# --------------------------------------------------------------------------
# Sources of truth
# --------------------------------------------------------------------------

# `single<List<ai.koog.agents.core.tools.Tool<*, *>>> { listOf( ... ) }`
_TOOL_LIST_RE = re.compile(
    r"single<List<ai\.koog\.agents\.core\.tools\.Tool<\*,\s*\*>>>"
    r"\s*\{\s*listOf\((.*?)\)\s*\}",
    re.S,
)
_TOOL_ENTRY_RE = re.compile(r"get<(\w+Tool)>")

_SCHEMA_RE = re.compile(r"SCHEMA_VERSION\s*=\s*(\d+)")


def mcp_tools(path: Path) -> list[str]:
    """Tool class names from the DI module's Koin list, in declaration order.

    Parsed rather than counted from source text because the count is the whole
    claim: `ToolRegistrar` registers exactly this list, so anything less precise
    than this parse would be an estimate wearing a fact's clothes.
    """
    if not path.exists():
        raise Violation(f"not found: {_rel(path)}")
    text = path.read_text(encoding="utf-8")
    m = _TOOL_LIST_RE.search(text)
    if not m:
        raise Violation(
            f"no `single<List<Tool<*, *>>>` listOf(...) found in "
            f"{_rel(path)} — the Koin binding shape changed, so the tool "
            f"count can no longer be derived and the README claim is unverifiable"
        )
    tools = _TOOL_ENTRY_RE.findall(m.group(1))
    if not tools:
        raise Violation(f"the tool list in {_rel(path)} is empty")
    return tools


def schema_version(path: Path = DB_FILE) -> int:
    if not path.exists():
        raise Violation(f"not found: {_rel(path)}")
    m = _SCHEMA_RE.search(path.read_text(encoding="utf-8"))
    if not m:
        raise Violation(f"SCHEMA_VERSION not found in {_rel(path)}")
    return int(m.group(1))


def adr_count(root: Path = ROOT) -> int:
    # Both this and `skill_count` resolve against `root` unconditionally. An
    # earlier version compared `root == ROOT` to pick between two spellings of
    # the same path, which meant the self-test silently counted the *real*
    # repository's ADRs and skills while asserting against a synthetic README —
    # the fixture could never fail, and the `+`-claim rule went untested.
    return len(list((root / ADR_DIR.relative_to(ROOT)).glob("2026-*.md")))


def skill_count(root: Path = ROOT) -> int:
    base = root / SKILLS_DIR.relative_to(ROOT)
    return sum(1 for d in base.iterdir() if d.is_dir() and (d / "SKILL.md").is_file())


# --------------------------------------------------------------------------
# README parsing
# --------------------------------------------------------------------------

# `32` / `v37` / `270+` / `90+`. Captured as (value, has_plus).
_TOOL_CLAIM_RE = re.compile(r"\b(\d+)\+?\s+(?:Koog-powered tools|read/write/list tools)")
_BADGE_CLAIM_RE = re.compile(r"MCP%20Server-(\d+)%20tools")
_SCHEMA_CLAIM_RE = re.compile(r"schema\s+v(\d+)")
_ADR_CLAIM_RE = re.compile(r"Auto-generated index of\s+(\d+)(\+?)\s+ADRs")
_SKILL_CLAIM_RE = re.compile(r"Auto-generated index of\s+(\d+)(\+?)\s+agent skills")


def parse_claims(text: str) -> dict[str, list[tuple[int, bool]]]:
    """Every place the README states each number, as `(value, has_plus)`.

    A list rather than a single value because these claims appear more than once
    (`32` appears in a badge, a feature table and a second table). Checking only
    the first would leave the others to drift, which is how a README ends up
    contradicting itself three lines apart.

    The `has_plus` flag is carried per occurrence rather than recovered later by
    searching the raw text for `"<n>+"`. That search was wrong: it asks whether
    the digits followed by a plus appear *anywhere*, so `270+` in one sentence
    launders an unrelated `90+` on another line into "this is a lower bound".
    """
    def collect(regex, text) -> list[tuple[int, bool]]:
        return [(int(m.group(1)), bool(m.group(2)))
                for m in regex.finditer(text)]

    return {
        # The badge URL carries no `+`, so it is always an exact claim.
        "mcp_tools": [(int(m.group(1)), False) for m in _TOOL_CLAIM_RE.finditer(text)]
        + [(int(m.group(1)), False) for m in _BADGE_CLAIM_RE.finditer(text)],
        "schema_version": [(int(m.group(1)), False)
                           for m in _SCHEMA_CLAIM_RE.finditer(text)],
        "adr_count": collect(_ADR_CLAIM_RE, text),
        "skill_count": collect(_SKILL_CLAIM_RE, text),
    }


def _check(claim: str, stated: list[tuple[int, bool]], actual: int,
           allow_plus: bool) -> list[str]:
    out: list[str] = []
    if not stated:
        return [f"README states no {claim} claim; add one so the gate has something to check"]
    for value, has_plus in sorted(set(stated)):
        if has_plus and allow_plus:
            if value > actual:
                out.append(
                    f"README claims {value}+ {claim}, but the tree has {actual}. "
                    f"A lower bound above the truth is a false claim."
                )
        elif value != actual:
            suffix = "+" if has_plus else ""
            out.append(f"README claims {value}{suffix} {claim}, but the tree has {actual}")
    return out


def run_checks(root: Path = ROOT) -> tuple[list[str], dict[str, int | str]]:
    # Every source path is resolved against `root`, so the self-test can hand in
    # a synthetic tree and get exactly the code path the real check takes.
    readme = root / "README.md"
    if not readme.exists():
        raise Violation("README.md not found")
    text = readme.read_text(encoding="utf-8")

    jvm = mcp_tools(root / MCP_JVM.relative_to(ROOT))
    android_tools = mcp_tools(root / MCP_ANDROID.relative_to(ROOT))
    version = schema_version(root / DB_FILE.relative_to(ROOT))
    adrs = adr_count(root)
    skills = skill_count(root)

    claims = parse_claims(text)
    violations: list[str] = []
    violations += _check("mcp tools", claims["mcp_tools"], len(jvm), False)
    violations += _check("schema version", claims["schema_version"], version, False)
    violations += _check("ADRs", claims["adr_count"], adrs, True)
    violations += _check("agent skills", claims["skill_count"], skills, True)

    # The README states one number; the two platforms must agree, or that number
    # is a lie for whichever platform it is not.
    only_jvm = sorted(set(jvm) - set(android_tools))
    only_android = sorted(set(android_tools) - set(jvm))
    if only_jvm:
        violations.append(
            f"{len(only_jvm)} tool(s) are registered on JVM but not Android: "
            f"{', '.join(only_jvm)}. A single README count cannot describe both — "
            f"register the tools on both platforms or state the counts separately."
        )
    if only_android:
        violations.append(
            f"{len(only_android)} tool(s) are registered on Android but not JVM: "
            f"{', '.join(only_android)}."
        )

    stats: dict[str, int | str] = {
        "mcp_tools": len(jvm),
        "schema_version": version,
        "adr_count": adrs,
        "skill_count": skills,
    }
    return violations, stats


# --------------------------------------------------------------------------
# The positive control
# --------------------------------------------------------------------------

_KOTLIN_HEADER = (
    "package com.singularity.todo\n\n"
    "import ai.koog.agents.core.tools.Tool\n"
)

def _db_source(schema: int) -> str:
    # Parameterised, not a constant. A fixed `SCHEMA_VERSION = 44` meant the
    # corpus builder's `schema` argument was accepted and then ignored, so any
    # test asserting a schema mismatch was really asserting against 44 — the
    # fixture could not fail for the reason it was written.
    return _KOTLIN_HEADER + f"\nconst val SCHEMA_VERSION = {schema}\n"


def _mcp_module(n: int, offset: int = 0) -> str:
    # Names must end in `Tool` — `_TOOL_ENTRY_RE` matches `get<XTool>()`, and a
    # fixture that does not is a fixture testing the regex rather than the gate.
    names = ", ".join(f"get<{offset + i}Tool>()" for i in range(n))
    return (
        _KOTLIN_HEADER
        + "\nfun aiToolsModule() = module {\n"
        "    single<List<ai.koog.agents.core.tools.Tool<*, *>>> {\n"
        f"        listOf({names})\n"
        "    }\n"
        "}\n"
    )


class _tree:
    """Materialise a synthetic tree and hand back its root."""

    def __init__(self, files: dict[str, str]):
        self.files = files
        self._tmp = None

    def __enter__(self) -> Path:
        self._tmp = tempfile.TemporaryDirectory(prefix="readmeclaims-selftest-")
        root = Path(self._tmp.name)
        for rel, content in self.files.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
        return root

    def __exit__(self, *exc):
        if self._tmp:
            self._tmp.cleanup()
        return False


def _corpus(readme: str, tools: int, schema: int, adrs: int, skills: int) -> dict[str, str]:
    files = {
        "README.md": readme,
        "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt":
            _db_source(schema),
        "shared/src/jvmMain/kotlin/com/singularity/todo/core/di/AiToolsModule.jvm.kt":
            _mcp_module(tools),
        "shared/src/androidMain/kotlin/com/singularity/todo/core/di/AiToolsModule.android.kt":
            _mcp_module(tools),
    }
    for i in range(adrs):
        files[f"docs/decisions/2026-01-{i:02d}-note.md"] = "title: note\n"
    for i in range(skills):
        files[f".agents/skills/skill-{i}/SKILL.md"] = "name: s\n"
    return files


_GOOD_README = (
    "# T\n\n"
    "| AI | 9 Koog-powered tools |\n"
    "| MCP | 9 read/write/list tools |\n"
    "Auto-generated index of 5+ ADRs\n"
    "Auto-generated index of 3+ agent skills\n"
    "Room with auto-migrations (schema v44)\n"
)

_BAD_README = _GOOD_README.replace("9 Koog-powered", "4 Koog-powered") \
                        .replace("schema v44", "schema v37") \
                        .replace("5+ ADRs", "9+ ADRs") \
                        .replace("3+ agent skills", "11+ agent skills")


def run_self_test() -> int:
    failures: list[str] = []

    with _tree(_corpus(_BAD_README, tools=9, schema=44, adrs=5, skills=3)) as root:
        violations = run_checks(root)[0]
    if len(violations) < 4:
        failures.append(
            f"self-test: a README contradicting all four counts produced {len(violations)} "
            f"violation(s), expected at least 4: {violations}"
        )

    with _tree(_corpus(_GOOD_README, tools=9, schema=44, adrs=5, skills=3)) as root:
        violations = run_checks(root)[0]
    if violations:
        failures.append(f"self-test: a consistent corpus was reported as: {violations}")

    # A platform divergence must be caught even though the README number matches
    # one platform: that is the case where a single number silently misleads.
    divergent = _corpus(_GOOD_README, tools=9, schema=44, adrs=5, skills=3)
    divergent["shared/src/androidMain/kotlin/com/singularity/todo/core/di/AiToolsModule.android.kt"] \
        = _mcp_module(9, offset=100)
    with _tree(divergent) as root:
        violations = run_checks(root)[0]
    if not violations:
        failures.append(
            "self-test: JVM/Android tool-list divergence was not reported — a README "
            "number that describes only one platform would pass"
        )

    if failures:
        print("check-readme-claims: self-test FAILED\n", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print(
        "check-readme-claims self-test: OK — four claim types report a contradicting "
        "README, a consistent one is silent, and a platform divergence is caught"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the rules still fire on a known-bad corpus")
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    try:
        violations, stats = run_checks()
    except Violation as exc:
        print(f"check-readme-claims: {exc}", file=sys.stderr)
        return 1

    print(
        "readme claims: "
        f"{stats['mcp_tools']} MCP tools (jvm == android), "
        f"schema v{stats['schema_version']}, "
        f"{stats['adr_count']} ADRs, "
        f"{stats['skill_count']} skills"
    )
    if violations:
        print(f"\ncheck-readme-claims: {len(violations)} violation(s)\n", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())