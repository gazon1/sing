#!/usr/bin/env python3
"""check-text-field-transformations-table.py — the skill's transformation table against the code.

The skill `.agents/skills/singularity-todo-text-field-states/SKILL.md` carries a
table of the text-field transformations and the rule each one applies. Nothing
checked it: a transformation could be added, renamed, or have its rule changed,
and the table kept claiming the old thing. A table that can lie is worse than
no table, because a reader has no way to know which half of it is true.

So the table is read and compared with
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/TextFieldTransformations.kt`:

* every transformation the code declares appears in the table, and every row of
  the table names a transformation the code declares;
* the row's parameter list is the function's parameter list;
* the facts the row claims are the facts the code enforces.

What is checked, and what is not
--------------------------------

The gate derives a small set of **facts** per transformation from the Kotlin
source — the facts that have a shape in the code:

  drops=non-digit   the transformation (or a function it calls) filters digits
  rewrite           the transformation rewrites the buffer's text
  rejects=edit      the transformation reverts the whole edit
  limit=maxDigits   the digit count is the function's own parameter
  limit=maxLength   the length bound is the function's own parameter
  limit=2           the digit count is a constant (`TWO_DIGITS`)
  min=1             the value must be at least one
  range=1..31       the value must be between 1 and 31

The table's third column carries exactly those facts, one `key=value` per
entry, and the gate holds the two lists equal. The second column — the
sentence a human reads — is checked as well, but only as far as a sentence can
be: each fact requires the sentence to *mention* it (`drops` for
`drops=non-digit`, `maxDigits` for `limit=maxDigits`, "two" for `limit=2`, and
so on). A sentence that stops mentioning what the code does fails the gate.

That is the limit of what a text comparison can do, and the gate says so in its
findings: **whether the rule is the right rule for the field it is used in is
not checked**, nor is the order in which the filter and the range check run,
nor whether the transformation is used where the table implies. Those are
judgements, and the repository keeps them in the prose of the skill and in the
tests of the rules themselves
(`shared/src/commonTest/.../TextFieldTransformationsTest.kt`).

Usage:
    python3 scripts/check-text-field-transformations-table.py [--repo-root PATH]

Exit codes:
    0  The table and the code agree
    1  They disagree (blocking mode)
    2  A file the gate reads is missing
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

import click

_SRC = Path(__file__).resolve().parent.parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run  # noqa: E402

#: The document: the table a reader believes.
SKILL = Path(".agents/skills/singularity-todo-text-field-states/SKILL.md")

#: The code: the transformations the document describes.
CODE = Path(
    "shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/"
    "TextFieldTransformations.kt"
)

#: The header of the table inside the document.
TABLE_HEADER = "| Transformation | Rule | Checked |"

#: A top-level function declaration. The body of a function is everything from
#: its parameter list to the next declaration, which is enough for the facts
#: below and does not pretend to be a Kotlin parser.
_FUNC_RE = re.compile(r"^(?:(?:internal|private|public|internal)\s+)?fun\s+(\w+)\s*\(", re.MULTILINE)

#: `private const val TWO_DIGITS = 2` — the digit count the code names.
_CONST_RE = re.compile(r"^\s*(?:private\s+)?const\s+val\s+(\w+)\s*=\s*(\d+)", re.MULTILINE)

#: `digitsOnlyTransformation(maxDigits)` in the table's first column.
_ROW_NAME_RE = re.compile(r"^`(\w+)\(([^)]*)\)`$")


@dataclass(frozen=True)
class Func:
    """One Kotlin function: its name, its parameters and its body."""

    name: str
    params: tuple[str, ...]
    body: str


@dataclass(frozen=True)
class Row:
    """One row of the table: the transformation, the rule and the facts."""

    name: str
    params: tuple[str, ...]
    rule: str
    facts: frozenset[str]


@dataclass
class Finding:
    """One disagreement, with the transformation it is about."""

    kind: str
    name: str
    detail: str

    def render(self) -> str:
        return f"{self.kind}: {self.name} — {self.detail}"


#: The words a sentence may use for a fact. A sentence is prose, so the gate
#: asks for a mention rather than for an exact wording: the point is that the
#: sentence still says what the code does.
_KEYWORDS: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {
    # fact: (any of these groups must appear — each group is a set of synonyms)
    "drops=non-digit": (
        ("drop", "drops", "dropped", "removes", "removes", "filters", "filter", "keeps only"),
        ("digit", "digits"),
    ),
    "rewrite": (("rewrite", "rewrites", "rewritten", "replaces the field", "replaces the text"),),
    "rejects=edit": (
        ("reject", "rejects", "rejected", "revert", "reverts", "snaps back", "refuses"),
    ),
    "limit=maxDigits": (("maxdigits",),),
    "limit=maxLength": (("maxlength",),),
    "limit=2": (("two", "2"),),
    "min=1": (("at least one", "at least 1", "greater than zero", "> 0"),),
    "range=1..31": (("1",), ("31",)),
}


def _read(path: Path, root: Path) -> str:
    full = root / path
    if not full.is_file():
        raise FileNotFoundError(str(full))
    return full.read_text(encoding="utf-8")


def _matching_paren(text: str, open_at: int) -> int:
    """The index of the `)` that closes the `(` at [open_at]."""
    depth = 0
    for index in range(open_at, len(text)):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
    return -1


def parse_functions(source: str) -> dict[str, Func]:
    """Every top-level function in the Kotlin source, with its body.

    The body runs from the end of the parameter list to the next declaration:
    the file's functions are either one expression or one block, and the facts
    below are found by reading those lines rather than by evaluating Kotlin.
    """
    matches = list(_FUNC_RE.finditer(source))
    functions: dict[str, Func] = {}
    for index, match in enumerate(matches):
        open_at = source.index("(", match.start())
        close_at = _matching_paren(source, open_at)
        if close_at == -1:
            continue
        raw_params = source[open_at + 1 : close_at]
        params = []
        for part in raw_params.split(","):
            part = part.strip()
            if not part:
                continue
            name = part.split(":")[0].split("=")[0].strip()
            if name:
                params.append(name)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(source)
        functions[match.group(1)] = Func(
            name=match.group(1),
            params=tuple(params),
            body=source[close_at:end],
        )
    return functions


def parse_constants(source: str) -> dict[str, int]:
    """The `const val` numbers of the source, by name."""
    return {name: int(value) for name, value in _CONST_RE.findall(source)}


def transformations(functions: dict[str, Func]) -> list[str]:
    """The functions that return an `InputTransformation`.

    The rule functions — `filterDigits`, `isWithinLength` — return something
    else, and a rule is not a transformation: only the adapters that carry a
    rule into a text field are rows of the table.
    """
    return sorted(
        name
        for name, func in functions.items()
        if re.search(r":\s*InputTransformation\b", func.body)
    )


def _closure(functions: dict[str, Func], name: str) -> set[str]:
    """The transformation and every function it calls, transitively."""
    seen: set[str] = set()
    pending = [name]
    while pending:
        current = pending.pop()
        if current in seen or current not in functions:
            continue
        seen.add(current)
        body = functions[current].body
        for called in re.findall(r"\b(\w+)\s*\(", body):
            if called in functions and called not in seen:
                pending.append(called)
    return seen


def _call_args(body: str, callee: str) -> list[str]:
    """The argument lists of every call to [callee] in [body].

    The arguments are read with a balanced-paren walk: a call such as
    `isWithinLength(asCharSequence().toString(), maxLength)` has a `)` inside
    its first argument, and a regex that stops at the first `)` would read the
    call as if it had no length bound at all.
    """
    args: list[str] = []
    for match in re.finditer(rf"\b{re.escape(callee)}\s*\(", body):
        open_at = body.index("(", match.start())
        close_at = _matching_paren(body, open_at)
        if close_at == -1:
            continue
        args.append(body[open_at + 1 : close_at])
    return args


def derive_facts(
    functions: dict[str, Func], constants: dict[str, int], name: str
) -> set[str]:
    """The facts the code enforces for one transformation.

    Every fact is read from the source: the calls the transformation makes, the
    arguments it passes, and the comparisons its helpers make.
    """
    facts: set[str] = set()
    reached = _closure(functions, name)
    bodies = {key: functions[key].body for key in reached}

    if any("filterDigits(" in body for body in bodies.values()):
        facts.add("drops=non-digit")
    if any("revertAllChanges(" in body for body in bodies.values()):
        facts.add("rejects=edit")
    if any(re.search(r"\breplace\s*\(", body) for body in bodies.values()):
        facts.add("rewrite")

    for key, body in bodies.items():
        if key == "filterDigits":
            # The rule's own body holds the parameter, not a limit the
            # transformation enforces.
            continue
        for args in _call_args(body, "filterDigits"):
            if "TWO_DIGITS" in args:
                for const, value in constants.items():
                    if const in args:
                        facts.add(f"limit={value}")
            elif "maxDigits" in args:
                facts.add("limit=maxDigits")
        for args in _call_args(body, "isWithinLength"):
            if "maxLength" in args:
                facts.add("limit=maxLength")

    for body in bodies.values():
        for bound in re.findall(r">\s*(\d+)", body):
            facts.add(f"min={int(bound) + 1}")
        for low, high in re.findall(r"\bin\s+(\d+)\.\.(\d+)", body):
            facts.add(f"range={low}..{high}")

    return facts


def parse_table(document: str) -> list[Row]:
    """The rows of the table in the document.

    The table is found by its header, so a document that loses the table is a
    document the gate reports rather than one it silently finds empty.
    """
    lines = document.splitlines()
    start = -1
    for index, line in enumerate(lines):
        if line.strip() == TABLE_HEADER:
            start = index + 2  # the header, then the separator
            break
    if start == -1:
        return []

    rows: list[Row] = []
    for line in lines[start:]:
        stripped = line.strip()
        if not stripped.startswith("|"):
            break
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if len(cells) != 3:
            break
        name_match = _ROW_NAME_RE.match(cells[0])
        if name_match is None:
            break
        params = tuple(
            part.strip() for part in name_match.group(2).split(",") if part.strip()
        )
        facts = frozenset(
            token.strip()
            for token in cells[2].replace("`", "").replace(";", ",").split(",")
            if token.strip()
        )
        rows.append(
            Row(
                name=name_match.group(1),
                params=params,
                rule=cells[1],
                facts=facts,
            )
        )
    return rows


def _rule_mentions(rule: str, fact: str) -> bool:
    """Whether the rule sentence mentions the fact.

    A prose check, deliberately shallow: it asks whether the sentence still
    says what the code does, not whether it says it well.
    """
    groups = _KEYWORDS.get(fact)
    if groups is None:
        return True
    lowered = rule.lower()
    return all(any(word in lowered for word in group) for group in groups)


def check(rows: list[Row], functions: dict[str, Func], constants: dict[str, int]) -> list[Finding]:
    """Compare the table with the code and report every disagreement."""
    findings: list[Finding] = []

    declared = transformations(functions)
    table_names = [row.name for row in rows]

    for name in declared:
        if name not in table_names:
            findings.append(
                Finding(
                    kind="not-in-the-table",
                    name=name,
                    detail=(
                        "the code declares this transformation but the table does not "
                        "name it — add a row, or the table no longer describes the file"
                    ),
                )
            )
    for row in rows:
        if row.name not in declared:
            findings.append(
                Finding(
                    kind="not-in-the-code",
                    name=row.name,
                    detail=(
                        "the table names this transformation but the code does not "
                        "declare it — it was renamed or removed; fix the table"
                    ),
                )
            )

    for row in rows:
        func = functions.get(row.name)
        if func is None:
            continue
        if tuple(row.params) != func.params:
            findings.append(
                Finding(
                    kind="wrong-parameters",
                    name=row.name,
                    detail=(
                        f"the table says ({', '.join(row.params)}) but the code "
                        f"declares ({', '.join(func.params)})"
                    ),
                )
            )
        facts = derive_facts(functions, constants, row.name)
        if facts != set(row.facts):
            findings.append(
                Finding(
                    kind="wrong-facts",
                    name=row.name,
                    detail=(
                        "the table claims "
                        f"[{', '.join(sorted(row.facts)) or 'nothing'}] but the code "
                        f"enforces [{', '.join(sorted(facts)) or 'nothing'}]"
                    ),
                )
            )
        for fact in sorted(facts):
            if not _rule_mentions(row.rule, fact):
                findings.append(
                    Finding(
                        kind="rule-does-not-mention-it",
                        name=row.name,
                        detail=(
                            f"the code enforces {fact!r} but the rule sentence does not "
                            f"say so: {row.rule!r}"
                        ),
                    )
                )
    return findings


def _not_vacuous(rows: list[Row], functions: dict[str, Func]) -> list[Finding]:
    """The scan has to have read something, or every rule above is vacuous."""
    findings: list[Finding] = []
    if not rows:
        findings.append(
            Finding(
                kind="scan-found-nothing",
                name="-",
                detail=(
                    f"no table was read from {SKILL}. The header {TABLE_HEADER!r} is "
                    "not there, so nothing below was checked."
                ),
            )
        )
    if not transformations(functions):
        findings.append(
            Finding(
                kind="scan-found-nothing",
                name="-",
                detail=(
                    f"no transformation was read from {CODE}. Every rule below it is "
                    "vacuously satisfied."
                ),
            )
        )
    return findings


@click.command()
@click.option(
    "--repo-root",
    type=click.Path(file_okay=False, path_type=Path),
    default=Path("."),
    show_default=True,
    help="The repository root to read the skill and the transformations from.",
)
@click.pass_context
def cli(ctx: click.Context, repo_root: Path) -> None:
    """Fail when the skill's transformation table and the code disagree."""
    root = repo_root.resolve()
    try:
        document = _read(SKILL, root)
        source = _read(CODE, root)
    except FileNotFoundError as exc:
        click.echo(f"::error::file not found: {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    rows = parse_table(document)
    functions = parse_functions(source)
    constants = parse_constants(source)

    findings = _not_vacuous(rows, functions) + check(rows, functions, constants)

    if findings:
        click.echo("::group::The text-field transformation table is out of step")
        for finding in findings:
            click.echo(f"::error::{finding.render()}")
        click.echo("::endgroup::")
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo(
        f"the text-field transformation table agrees with the code: "
        f"{len(transformation_names(functions))} transformation(s), "
        f"{len(rows)} row(s)"
    )
    raise ctx.exit(CLEAN)


def transformation_names(functions: dict[str, Func]) -> list[str]:
    """The transformations the code declares — the rows the table must have."""
    return transformations(functions)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
