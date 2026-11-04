#!/usr/bin/env python3
"""check-arch-rule-controls.py — every arch rule declares its control or debt.

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
   the debt, pinned by rule id and ratcheted.

## The registry

`scripts/arch-rule-controls.tsv`, one row per derived rule, plus one
`# grandfathered: <rule_id>` line for each rule that is still uncontrolled:

    rule_id <TAB> control_kind <TAB> control_ref <TAB> expects

- `rule_id` — `<TestClass>.<method name>`, exactly as the derivation spells it.
- `control_kind` — `fixture`, `synthetic`, or `none`.
- `control_ref` — `fixture`: a repo-relative path. `synthetic`:
  `<TestClass>.<method>` naming the control test, which must not be the rule
  itself nor any other derived rule. `none`: the document that records the debt
  (the row is a declaration, not a control).
- `expects` — for a fixture, the shape the rule hunts, as a substring the file
  must still contain. The match collapses runs of whitespace on both sides, and
  says so here because the contract is what the failure is read against:
  re-indenting or rewrapping a fixture is not fixing it, so the shape still
  counts; the shape going away is what fails.

A `fixture` ref that has lost its shape fails, so a "fixed" fixture cannot
quietly stop proving anything. A `synthetic` ref must exist and must assert a
finding, not its absence — and it must not be the rule it protects: a rule ends
in "no offenders", so its own body would satisfy its own control without ever
proving that a finding can be produced.

## The ratchet is per rule, not per count

A `none` row is allowed only when a `# grandfathered: <rule_id>` line names the
same rule; a `none` row with no line fails, and a line with no `none` row fails
too (the debt was paid, the line has to go).

There is no `# ceiling: N` any more, and the gate rejects one. A count is
satisfied by one debt replacing another: delete the control of rule A, add a new
rule B with `none`, and the number is where it was — so the gate would pass a
new rule that arrived with a promise instead of a control, which is the thing
this gate exists to forbid. Pinned by rule id, that swap fails on B. The one way
left to add debt is to add a line naming the rule, which is a line the reviewer
sees; the gate cannot forbid an edit to its own baseline, and pretending a
declaration file is immutable would be the same promise in a different hat.

## Why `none` is a third kind, and what it costs

The issue lists two kinds, `fixture` and `synthetic` (its prose names a third,
a corpus guard). Most of the rules derived today have neither. Giving each a
control means rewriting the rule, and the change that lands the mechanism is
explicitly not the change that clears the backlog; the audit's migration order
is *register the current state, then land controls*
(`docs/architecture/rule-control-audit.md` §6.9). `none` is that registration:
it makes the debt visible, named and ratcheted instead of invisible.

It does not prove such a rule can fail. For those the gate proves only that the
debt is declared and shrinking; only the rules whose row is a `fixture` or a
`synthetic` control are the ones the gate actually holds to "can fail". That
limit is recorded in the audit, §6.1, so the registry cannot be read as a
stronger claim than it makes.

The counts are deliberately not restated here. A number copied into prose is
the first thing to go stale — this paragraph used to say "fourteen of the
nineteen", the derivation found eighteen, and arithmetic in a docstring was
being read as evidence about a registry it does not measure. The registry is
the authority for how many rows are which kind; `findings()` is the authority
for whether that registry covers the derivation.

`fixture` is implemented and exercised by the tests, but no row in the landed
registry is one. That is not an oversight: a fixture has to be *the rule's own
corpus*, so a row pointing at a file the rule never scans would prove nothing —
the defect this gate exists to name. None of the rules derived today has a
fixture corpus; when one does, its row is the one that exercises the
shape-still-present check against real input.

## What the gate enforces, and what it does not

`--check` enforces two things: every rule the derivation finds has a row, and
a row whose kind is `fixture` or `synthetic` still proves something. A `none`
row is not a control. It declares the debt, pins it by rule id and fails when
the declaration drifts — it does not show that rule can fail. So the gate's
name is the end state the ratchet walks toward, not a claim about the rows
still on `none`; the audit's §6.1 records the same limit, and the SI
comments at the registry entries say it where a reader meets the gate.

Usage:
    python3 scripts/check-arch-rule-controls.py --check
    python3 scripts/check-arch-rule-controls.py --json

Exit codes:
    0 — every derived rule has a control, and every control is still a control
    1 — a rule with no control, a control that no longer proves anything, or
        debt that is not declared (or declared and no longer owed)
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

#: One row per derived rule, plus one `# grandfathered:` line per rule whose
#: control is still owed. The debt is pinned by rule id, not by a count.
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

#: The "no offenders" assertion: the shape every rule ends in. The emptiness
#: form is matched structurally, on the argument `assertTrue` is given, not on a
#: character window. A window is wrong both ways: it runs past the assertion's
#: closing paren, so an unrelated `x.isEmpty()` later in the body makes a rule
#: out of a test that only asserted a non-empty finding; and it gives up after
#: 300 characters, so a genuine rule whose argument is longer is silently absent
#: from the inventory — the exact failure this gate hunts.
ASSERTS_NOTHING_FOUND = re.compile(r"\bassertNoOffenders\s*\(|\bfail\s*\(")
EMPTINESS = re.compile(r"\.\s*isEmpty\s*\(\s*\)")

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


def _expression_after(text: str, equals: int) -> str:
    """The expression that follows the `=` at `equals`, to the end of the declaration."""
    depth = 0
    index = equals + 1
    while index < len(text):
        char = text[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth = max(0, depth - 1)
        elif char == "\n" and depth == 0:
            break
        index += 1
    return text[equals + 1 : index]


def _body_after(text: str, after: int) -> str:
    """The body that follows `after`: a `{…}` block or a `= expression`.

    A block body is read to its matching brace. An expression body is read to
    the end of the declaration, bracket-aware — because `= assertTrue(…)` has no
    braces at all, and accepting only an expression whose brace sat on the `=`
    line returned "" for it. A rule written that way then left the inventory
    with no row and no complaint, which is the silent omission this gate exists
    to end. Stopping at the newline that closes every bracket keeps a multi-line
    expression whole without swallowing the next method's body and inventing a
    rule.
    """
    index = after
    while index < len(text) and text[index] not in "{=":
        index += 1
    if index >= len(text):
        return ""
    if text[index] == "=":
        return _expression_after(text, index)
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
            if SCOPE_QUERY.search(body) and has_no_offenders_assertion(body):
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


def parse_registry(text: str) -> tuple[list[Row], dict[str, int]]:
    """The rows and the grandfathered debt of the TSV, or `Unreadable` naming the line."""
    rows: list[Row] = []
    grandfathered: dict[str, int] = {}
    for number, raw in enumerate(text.splitlines(), 1):
        # Only the line ending is stripped: the fourth field is empty for every
        # kind but `fixture`, and `rstrip()` would eat the tab that carries it.
        line = raw.rstrip("\r\n")
        if not line.strip():
            continue
        if line.lstrip().startswith("#"):
            if re.search(r"\bceiling\s*:", line):
                raise Unreadable(
                    f"{CONTROLS}:{number}: `# ceiling:` is gone. A count of "
                    "uncontrolled rules is satisfied by one debt replacing "
                    "another — delete rule A's control, add a new rule B with "
                    "`none`, and the number never moves — so it cannot forbid a "
                    "new rule arriving with a promise. Pin the debt by rule id "
                    "with `# grandfathered: <rule_id>` instead."
                )
            declared = re.search(r"\bgrandfathered\s*:\s*(\S.*?)\s*$", line)
            if declared:
                rule_id = declared.group(1)
                if rule_id in grandfathered:
                    raise Unreadable(
                        f"{CONTROLS}:{number}: `# grandfathered: {rule_id}` is "
                        f"declared again; the first line is {grandfathered[rule_id]}. "
                        "A second copy is not read as a second declaration: the "
                        "ratchet is per rule, so two lines for one rule hide the "
                        "first position and let a stale copy outlive the debt it "
                        "describes. Keep exactly one line per rule"
                    )
                grandfathered[rule_id] = number
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
    return rows, grandfathered


def _balanced_argument(text: str, open_index: int) -> str:
    """The text between the `(` at `open_index` and its matching `)`."""
    close = _matching_paren(text, open_index)
    return "" if close < 0 else text[open_index + 1 : close]


def has_no_offenders_assertion(body: str) -> bool:
    """True when `body` asserts that a scan found no offenders.

    `assertTrue(…)` counts only when the `.isEmpty()` sits *inside* the argument
    that `assertTrue` is given. Reaching for the next `.isEmpty()` in the body
    would let an assertion about something else stand in for this one, and
    bounding the reach by a character count would drop a genuine rule whose
    argument is long.
    """
    if ASSERTS_NOTHING_FOUND.search(body):
        return True
    return any(
        EMPTINESS.search(_balanced_argument(body, call.end() - 1))
        for call in re.finditer(r"\bassertTrue\s*\(", body)
    )


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


def _squashed(text: str) -> str:
    """`text` with every run of whitespace collapsed to one space.

    `expects` is matched this way on both sides, and the docstring says so: a
    fixture that was re-indented or rewrapped still holds the shape it held,
    and failing it would call a control broken for a formatting change. What
    the check is for is the shape going away, which no amount of whitespace
    hides.
    """
    return re.sub(r"\s+", " ", text).strip()


def _check_synthetic(
    row: Row,
    methods: dict[tuple[str, str], str],
    rules: dict[str, Rule],
    problems: list[str],
) -> None:
    if "." not in row.ref:
        problems.append(
            f"{row.rule_id}: synthetic control_ref {row.ref!r} is not "
            "`<TestClass>.<method>`"
        )
        return
    if row.ref == row.rule_id:
        problems.append(
            f"{row.rule_id}: the synthetic control names the rule itself. A rule "
            "ends in `no offenders`, so its own body would satisfy its own control "
            "without ever proving that a finding can be produced"
        )
        return
    if row.ref in rules:
        problems.append(
            f"{row.rule_id}: the synthetic control {row.ref} is itself a derived "
            "rule ({rules[row.ref].source}). A rule asserts that nothing was found; "
            "over another rule's body that proves only that absence reads as "
            "success, which is the confusion this gate exists to end. The control "
            "must be a @Test the derivation does not count as a rule, asserting a "
            "finding"
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
    shape = _squashed(row.expects)
    if not shape:
        problems.append(
            f"{row.rule_id}: the fixture control {row.ref} names no `expects` shape, "
            "so nothing would notice the fixture losing what it proves"
        )
        return
    fixture = root / row.ref
    if not fixture.is_file():
        problems.append(f"{row.rule_id}: the fixture {row.ref} does not exist")
        return
    if shape not in _squashed(fixture.read_text(encoding="utf-8")):
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
    rows, grandfathered = parse_registry(registry.read_text(encoding="utf-8"))
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

    # The ratchet is per rule, not per count. A count is satisfied by one debt
    # replacing another — delete rule A's control, add a new rule B with `none`,
    # and the number never moves — so a count would let a new rule in on a
    # promise. Pinned by id, that swap fails on B, and the one edit that can add
    # debt is a `# grandfathered:` line a reviewer sees.
    uncontrolled: list[str] = []
    for row in rows:
        if row.kind == "fixture":
            _check_fixture(root, row, problems)
        elif row.kind == "synthetic":
            _check_synthetic(row, methods, problems)
        else:
            uncontrolled.append(row.rule_id)
            if row.rule_id not in grandfathered:
                problems.append(
                    f"{CONTROLS}:{row.line}: {row.rule_id} has a `none` row but no "
                    f"`# grandfathered: {row.rule_id}` line. A `none` row is legal "
                    "only when the debt is declared by rule id: add a fixture or a "
                    "synthetic source, or add the line in a commit that says why the "
                    "rule cannot have one yet"
                )
            if not (root / row.ref).is_file():
                problems.append(
                    f"{row.rule_id}: the debt record {row.ref} does not exist"
                )

    owed = set(uncontrolled)
    for rule_id in sorted(set(grandfathered) - owed):
        line = grandfathered[rule_id]
        if rule_id in seen and seen[rule_id].kind != "none":
            problems.append(
                f"{CONTROLS}:{line}: `# grandfathered: {rule_id}` is stale — that "
                f"rule's row on line {seen[rule_id].line} is a `{seen[rule_id].kind}` "
                "control, so the debt was paid and the line has to go. A declaration "
                "that outlives its debt makes the next `none` row read as already "
                "declared"
            )
        else:
            problems.append(
                f"{CONTROLS}:{line}: `# grandfathered: {rule_id}` names no `none` row "
                "— the rule was renamed, removed, or the row was deleted, and the "
                "line now declares debt nothing owes"
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
