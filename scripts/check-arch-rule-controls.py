#!/usr/bin/env python3
"""check-arch-rule-controls.py — every architecture rule must have a control that can fail.

Why this exists (2026-11): `shared/src/jvmTest/kotlin/com/singularity/todo/arch/`
holds the Konsist rules and the hand-written scanners that guard the layering.
Each rule asserts that a scan found no offenders. A scan that has stopped
scanning finds no offenders too, so a rule whose corpus cannot contain the shape
it hunts passes for the same reason a clean tree does — and nothing distinguishes
the two.

That is not hypothetical. `repository read methods are user-scoped` read
`@Query` annotations out of every `*RepositoryImpl` file. Twenty-four files
matched the filter; none of them held a `@Query`, because the queries had moved
to the DAOs. The rule was green for its whole life and could not have gone red
(issue #788). `docs/architecture/rule-control-audit.md` §3 lists the class, and
§6 sketches this gate.

Two things keep the next one from hiding:

1. **The inventory is derived, not listed.** The rule sources are parsed for test
   methods whose body carries a Konsist scope query (`scopeFromExternalDirectory`,
   `scope.files`, `scope.interfaces()`) *and* a "no offenders" assertion
   (`assertNoOffenders`, `fail(`, `assertTrue(x.isEmpty())`). A rule added today
   is in the inventory today; there is no second list to forget.
2. **Every rule needs a control that proves the rule can fail.** A committed
   fixture that still contains the hunted shape, or a synthetic source that
   asserts a finding, or — while the backlog is paid down — a row that records
   the debt and a ceiling that only falls.

## The registry

`scripts/arch-rule-controls.tsv`, one row per derived rule and a `# ceiling: N`
line for the ratchet:

    rule_id <TAB> control_kind <TAB> control_ref <TAB> expects

- `rule_id` — `<TestClass>.<method name>`, exactly as the derivation spells it.
- `control_kind` — `fixture`, `synthetic`, or `none`.
- `control_ref` — `fixture`: a repo-relative path. `synthetic`:
  `<TestClass>.<method>` naming the control test. `none`: the document that
  records the debt (the row is a declaration, not a control).
- `expects` — for a fixture, the shape the rule hunts, as a substring the file
  must still contain. Empty for the other kinds.

A `fixture` ref that has lost its shape fails, so a "fixed" fixture cannot
quietly stop proving anything. A `synthetic` ref must exist and must assert a
finding, not its absence. A `none` row is counted against the ceiling: a new
rule cannot arrive without a control, and the ceiling must come down when the
debt does — a ratchet that is allowed to stay loose is not a ratchet.

Why `none` and not only the two kinds in the issue: about eighteen rules have no
control today, and the audit's migration order is `register with the current
state, then land controls`. Forcing a control onto every one of them in the
change that lands the mechanism would mean rewriting the rules themselves, which
that change is explicitly not for.

## What this gate does not prove

It reads sources; it does not run the JVM test suite. `synthetic` is checked by
reading the named control's body — the assertion it must contain, not its
result. A control that is itself broken is a test failure, and the test suite is
where that is caught.

Usage:
    python3 scripts/check-arch-rule-controls.py --check
    python3 scripts/check-arch-rule-controls.py --json

Exit codes:
    0 — every derived rule has a control, and every control is still a control
    1 — a rule with no control, a control that no longer proves anything, or a
        ceiling that disagrees with the debt
    2 — the arch sources or the registry could not be read
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import click


# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = Path(__file__).resolve().parent.parent

#: The rule sources: the inventory is derived from these, never listed.
ARCH_SOURCES = Path("shared/src/jvmTest/kotlin/com/singularity/todo/arch")

#: One row per derived rule, plus the ceiling of rows that record debt.
CONTROLS = Path("scripts/arch-rule-controls.tsv")

#: The audit that lists the debt a `none` row points at.
BACKLOG = "docs/architecture/rule-control-audit.md"

CONTROL_KINDS = ("fixture", "synthetic", "none")

#: A Konsist scope query — the rule looks at declarations, not at one file.
#: The issue names these three; a rule that reaches its scope another way is
#: outside the derivation on purpose (widening the list would make the gate
#: depend on every helper's internals).
SCOPE_QUERY = re.compile(
    r"Konsist\s*\.\s*scopeFromExternalDirectory\b"
    r"|\bscope\s*\.\s*files\b"
    r"|\bscope\s*\.\s*interfaces\s*\(\s*\)"
)

#: The "no offenders" assertion: the shape every rule ends in.
NO_OFFENDERS = re.compile(
    r"\bassertNoOffenders\s*\("
    r"|\bfail\s*\("
    r"|assertTrue\s*\([\s\S]{0,300}?\.isEmpty\s*\(\s*\)"
)

#: A `@Test` method, whichever way its name is spelled.
_FUN = re.compile(r"\bfun\s+(?:`([^`]+)`|([A-Za-z_]\w*))\s*\(")


class Unreadable(Exception):
    """The gate could not read what it measures. Exit code 2, not a finding."""


@dataclass(frozen=True)
class Rule:
    """One derived architecture rule."""

    rule_id: str
    source: str


@dataclass(frozen=True)
class Row:
    """One line of the registry."""

    rule_id: str
    kind: str
    ref: str
    expects: str
    line: int


def mask_kotlin(source: str) -> str:
    """Blank comments and string bodies, keeping offsets and newlines.

    Brace matching has to run over code, not over the synthetic Kotlin sources
    the controls embed in raw strings: a `{` inside `\"\"\"…\"\"\"` would move the
    end of a method, and the rule that follows would be read as part of it. The
    mask is the smallest thing that makes the parse trustworthy — the same job
    `KotlinText` does for the Kotlin-side scanners.
    """
    out = list(source)

    def blank(start: int, stop: int) -> None:
        for index in range(start, stop):
            if out[index] != "\n":
                out[index] = " "

    index, size = 0, len(source)
    while index < size:
        char = source[index]
        if source.startswith("//", index):
            end = source.find("\n", index)
            end = size if end < 0 else end
            blank(index, end)
            index = end
        elif source.startswith("/*", index):
            depth, end = 1, index + 2
            while end < size and depth:
                if source.startswith("/*", end):
                    depth, end = depth + 1, end + 2
                elif source.startswith("*/", end):
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
            blank(index, end)
            index = end
        elif source.startswith('"""', index):
            end = source.find('"""', index + 3)
            end = size if end < 0 else end + 3
            blank(index, end)
            index = end
        elif char in ('"', "'"):
            end = index + 1
            while end < size:
                if source[end] == "\\":
                    end += 2
                elif source[end] == char:
                    end += 1
                    break
                elif source[end] == "\n":
                    break
                else:
                    end += 1
            blank(index, end)
            index = end
        else:
            index += 1
    return "".join(out)


def _matching_paren(text: str, open_index: int) -> int:
    """The index of the `)` closing the `(` at `open_index`, or -1."""
    depth = 0
    for index in range(open_index, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return index
    return -1


def _body_after(text: str, after: int) -> str:
    """The `{…}` body that follows `after`, or "" when there is none.

    An expression body is accepted only when its brace sits on the same line
    (`= runBlocking { … }`). `= assertTrue(…)` has no body of its own, and
    swallowing the next method's braces would invent a rule.
    """
    index = after
    while index < len(text) and text[index] not in "{=":
        index += 1
    if index >= len(text):
        return ""
    if text[index] == "=":
        line_end = text.find("\n", index)
        stop = len(text) if line_end < 0 else line_end
        brace = text.find("{", index, stop)
        if brace < 0:
            return ""
        index = brace
    start, depth = index, 0
    while index < len(text):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
        index += 1
    return ""


def test_methods(source: str) -> list[tuple[str, str]]:
    """Every `@Test` method of one Kotlin source, as `(name, body)`.

    The body is the masked text between the method's braces — comments and
    string contents are spaces, so a token in a KDoc or in an embedded fixture
    does not count as the method doing it.
    """
    masked = mask_kotlin(source)
    methods: list[tuple[str, str]] = []
    for annotation in re.finditer(r"@Test\b", masked):
        following = masked.find("@Test", annotation.end())
        window_end = len(masked) if following < 0 else following
        window = masked[annotation.end() : window_end]
        signature = _FUN.search(window)
        if not signature:
            continue
        name = signature.group(1) or signature.group(2)
        open_paren = annotation.end() + signature.end() - 1
        close_paren = _matching_paren(masked, open_paren)
        if close_paren < 0:
            continue
        methods.append((name, _body_after(masked, close_paren + 1)))
    return methods


def derived_rules(root: Path) -> dict[str, Rule]:
    """Every architecture rule the rule sources declare, keyed by rule id."""
    sources = root / ARCH_SOURCES
    if not sources.is_dir():
        raise Unreadable(f"the architecture test sources are missing: {sources}")
    rules: dict[str, Rule] = {}
    for path in sorted(sources.glob("*.kt")):
        for name, body in test_methods(path.read_text(encoding="utf-8")):
            if SCOPE_QUERY.search(body) and NO_OFFENDERS.search(body):
                rule_id = f"{path.stem}.{name}"
                rules[rule_id] = Rule(
                    rule_id=rule_id,
                    source=str(path.relative_to(root)),
                )
    return rules


def _all_test_methods(root: Path) -> dict[tuple[str, str], str]:
    """Every `@Test` method in the arch sources, keyed by `(class, method)`."""
    sources = root / ARCH_SOURCES
    methods: dict[tuple[str, str], str] = {}
    for path in sorted(sources.glob("*.kt")):
        for name, body in test_methods(path.read_text(encoding="utf-8")):
            methods[(path.stem, name)] = body
    return methods


def parse_registry(text: str) -> tuple[list[Row], int]:
    """The rows and the ceiling of the TSV, or `Unreadable` naming the line."""
    rows: list[Row] = []
    ceiling: int | None = None
    for number, raw in enumerate(text.splitlines(), 1):
        # Only the line ending is stripped: the fourth field is empty for every
        # kind but `fixture`, and `rstrip()` would eat the tab that carries it.
        line = raw.rstrip("\r\n")
        if not line.strip():
            continue
        if line.lstrip().startswith("#"):
            declared = re.search(r"ceiling:\s*(\d+)", line)
            if declared:
                ceiling = int(declared.group(1))
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise Unreadable(
                f"{CONTROLS}:{number}: expected 4 tab-separated fields "
                f"(rule_id, control_kind, control_ref, expects), found {len(fields)}"
            )
        rule_id, kind, ref, expects = (field.strip() for field in fields)
        if kind not in CONTROL_KINDS:
            raise Unreadable(
                f"{CONTROLS}:{number}: control_kind {kind!r} is not one of "
                f"{', '.join(CONTROL_KINDS)}"
            )
        rows.append(Row(rule_id=rule_id, kind=kind, ref=ref, expects=expects, line=number))
    if ceiling is None:
        raise Unreadable(
            f"{CONTROLS}: no `# ceiling: N` line. The ceiling is what stops a new "
            "rule from arriving without a control; without it the registry is a "
            "list, not a ratchet."
        )
    return rows, ceiling


def _balanced_argument(text: str, open_index: int) -> str:
    """The text between the `(` at `open_index` and its matching `)`."""
    close = _matching_paren(text, open_index)
    return "" if close < 0 else text[open_index + 1 : close]


def asserts_a_finding(body: str) -> bool:
    """True when a synthetic control asserts that something *was* found.

    The audit's rule is that a control asserting emptiness is not a control: a
    scanner that reports nothing and a scanner that is broken both produce an
    empty list. Emptiness assertions are therefore counted apart from the rest.
    """
    compact = re.sub(r"\s+", "", body)
    if "isNotEmpty()" in compact or "fail(" in compact:
        return True
    for call in re.finditer(r"assertEquals\s*\(", body):
        first = _balanced_argument(body, call.end() - 1).split(",", 1)[0].strip()
        if first != "0":
            return True
    for call in re.finditer(r"assertTrue\s*\(", body):
        argument = re.sub(r"\s+", "", _balanced_argument(body, call.end() - 1))
        if "isEmpty" not in argument:
            return True
    return False


def _check_synthetic(row: Row, methods: dict[tuple[str, str], str], problems: list[str]) -> None:
    if "." not in row.ref:
        problems.append(
            f"{row.rule_id}: synthetic control_ref {row.ref!r} is not "
            "`<TestClass>.<method>`"
        )
        return
    class_name, method = row.ref.split(".", 1)
    body = methods.get((class_name, method))
    if body is None:
        problems.append(
            f"{row.rule_id}: the synthetic control {row.ref} is not a @Test method "
            f"in {ARCH_SOURCES}/{class_name}.kt — a control that does not exist "
            "proves nothing"
        )
        return
    if not asserts_a_finding(body):
        problems.append(
            f"{row.rule_id}: the synthetic control {row.ref} asserts no finding — "
            "a control that passes when nothing was found cannot tell a clean tree "
            "from a scanner that stopped scanning"
        )


def _check_fixture(root: Path, row: Row, problems: list[str]) -> None:
    if not row.expects:
        problems.append(
            f"{row.rule_id}: the fixture control {row.ref} names no `expects` shape, "
            "so nothing would notice the fixture losing what it proves"
        )
        return
    fixture = root / row.ref
    if not fixture.is_file():
        problems.append(f"{row.rule_id}: the fixture {row.ref} does not exist")
        return
    if row.expects not in fixture.read_text(encoding="utf-8"):
        problems.append(
            f"{row.rule_id}: the fixture {row.ref} no longer contains "
            f"{row.expects!r} — it was fixed, and a fixed fixture proves nothing"
        )


def findings(root: Path) -> list[str]:
    """Everything wrong with the registry, in the order a reader should fix it."""
    rules = derived_rules(root)
    problems: list[str] = []
    if not rules:
        problems.append(
            f"the derivation found no rules under {ARCH_SOURCES} — a gate that "
            "measures nothing passes, and this one refuses to"
        )
        return problems

    registry = root / CONTROLS
    if not registry.is_file():
        raise Unreadable(f"the registry is missing: {registry}")
    rows, ceiling = parse_registry(registry.read_text(encoding="utf-8"))
    methods = _all_test_methods(root)

    seen: dict[str, Row] = {}
    for row in rows:
        if row.rule_id in seen:
            problems.append(
                f"{CONTROLS}:{row.line}: {row.rule_id} already has a row on line "
                f"{seen[row.rule_id].line} — one rule, one control"
            )
            continue
        seen[row.rule_id] = row

    for rule_id in sorted(rules):
        if rule_id not in seen:
            problems.append(
                f"{rule_id}: derived from {rules[rule_id].source} but has no row in "
                f"{CONTROLS} — name the control that proves it can fail "
                "(a fixture, a synthetic source, or the document that records the "
                "debt)"
            )
    for rule_id in sorted(seen):
        if rule_id not in rules:
            problems.append(
                f"{rule_id} ({CONTROLS}:{seen[rule_id].line}): the row names a rule "
                "the derivation no longer finds — the rule was renamed or removed, "
                "and the row now protects nothing"
            )

    uncontrolled: list[str] = []
    for row in rows:
        if row.kind == "fixture":
            _check_fixture(root, row, problems)
        elif row.kind == "synthetic":
            _check_synthetic(row, methods, problems)
        else:
            uncontrolled.append(row.rule_id)
            if not (root / row.ref).is_file():
                problems.append(
                    f"{row.rule_id}: the debt record {row.ref} does not exist"
                )

    if len(uncontrolled) > ceiling:
        added = sorted(set(uncontrolled))
        problems.append(
            f"{len(uncontrolled)} rule(s) have no control, but the ceiling is "
            f"{ceiling}. A rule arrives with a control, not with a promise: add a "
            f"fixture or a synthetic source for {', '.join(added)} — or, if the rule "
            "genuinely cannot have one yet, raise `# ceiling:` in a commit that says "
            "why"
        )
    elif len(uncontrolled) < ceiling:
        problems.append(
            f"only {len(uncontrolled)} rule(s) have no control, but the ceiling is "
            f"still {ceiling}. Lower it to {len(uncontrolled)} — a ratchet that is "
            "allowed to stay loose is not a ratchet"
        )

    return problems


@click.command()
@click.option("--quiet", is_flag=True, help="Findings only, for CI.")
@click.option("--check", is_flag=True, help="Fail when anything is found.")
@click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
@click.option(
    "--root",
    "root",
    default=None,
    type=click.Path(file_okay=False, path_type=Path),
    help="Read the tree under this directory instead of this checkout.",
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, check: bool, as_json: bool, root: Path | None) -> None:
    """Report architecture rules whose control can no longer prove they can fail."""
    base = ROOT if root is None else Path(root).resolve()
    try:
        problems = findings(base)
    except Unreadable as exc:
        if as_json:
            click.echo(json.dumps({"error": str(exc)}, indent=2))
        else:
            click.echo(f"ERROR: {exc}", err=True)
        raise ctx.exit(IO_ERROR) from None

    if as_json:
        click.echo(json.dumps({"problems": problems, "count": len(problems)}, indent=2))
    else:
        if not quiet:
            click.echo(
                "check-arch-rule-controls: "
                f"{len(problems)} problem(s) in the architecture rules"
            )
        for problem in problems:
            click.echo(f"  {problem}")
        if not quiet and not problems:
            click.echo("Every derived rule has a control that can still fail.")

    if problems and check:
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
