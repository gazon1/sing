"""sgate.gatekit.tables — a document table checked against the code.

Several gates compare a table in a document with the code: the ports table of
`docs/PLATFORM-REFERENCE.md`, the transformation table of the text-field
skill. Each of them wrote its own reader for the table — where it starts,
where it stops, which cell names the thing — and its own way of saying "this
row is about that declaration".

This module is the one reader. A rule says *which* table and *which* column
means what; that is data, and it lives in `config/gates/`. The part that
compares them is code, and it is written once here.

What the engine does, for every gate of the family:

  * finds the table by its header, so a document that loses the table is a
    document the gate reports rather than one it silently finds empty;
  * reads the name of each row, and the row's verdict;
  * compares the names in the table with the names the code declares, in both
    directions;
  * says when the scan read nothing, which makes every other rule vacuous.

What it deliberately does not do: read the code. How a gate finds what the
code declares — the interfaces the platform modules bind, the functions that
return a transformation — is the gate's own business, and stays in the gate.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass(frozen=True)
class TableRule:
    """A table in a document, and the columns that matter."""

    #: The document, repo-relative.
    doc: str = ""
    #: The header line that introduces the table.
    header: str = ""
    #: The column whose cell names the thing the row is about.
    name_column: int = 0
    #: The column that says how the row was checked, if there is one.
    verdict_column: int | None = None
    #: The vocabulary of that column.
    verdicts: tuple[str, ...] = ()
    #: The other columns, by name.
    columns: dict[str, int] = field(default_factory=dict)

    @classmethod
    def from_file(cls, path: Path) -> TableRule:
        """Read a rule from a YAML file."""
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        table = data.get("table") or {}
        return cls(
            doc=data.get("doc", ""),
            header=table.get("header", ""),
            name_column=int(table.get("name_column", 0)),
            verdict_column=(
                int(table["verdict_column"]) if table.get("verdict_column") is not None else None
            ),
            verdicts=tuple(table.get("verdicts", ())),
            columns=dict(data.get("columns", {})),
        )


def rows_at_header(text: str, header: str) -> list[list[str]]:
    """The rows of the table whose header is `header`.

    The table starts under its header — the head, then the separator — and
    ends at the first line that is not a row. A row with the wrong number of
    cells ends the table too: the row below it belongs to another table, and
    reading it as a row of this one is how a gate ends up checking prose.

    No header, no rows: the caller decides what an absent table means, and
    for every gate of this family it means "nothing was checked".
    """
    lines = text.splitlines()
    start = -1
    for index, line in enumerate(lines):
        if line.strip() == header:
            start = index + 2  # the head, then the separator
            break
    if start == -1:
        return []

    rows: list[list[str]] = []
    for line in lines[start:]:
        stripped = line.strip()
        if not stripped.startswith("|"):
            break
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        rows.append(cells)
    return rows


def cell_identifier(cell: str) -> str:
    """The identifier a cell names, or an empty string when it names none.

    A cell that says `-` names nothing; a cell that says `` `Foo` `` names
    `Foo`; a cell that says `` `Foo` the one `` names `Foo`.
    """
    cleaned = cell.replace("`", "").strip()
    if not cleaned or cleaned == "-":
        return ""
    return cleaned.split()[0]


def names_of(rows: list[list[str]], rule: TableRule) -> list[str]:
    """The name of each row, in the order the rows appear."""
    names: list[str] = []
    for row in rows:
        if rule.name_column >= len(row):
            continue
        names.append(cell_identifier(row[name_column]))
    return names


def unknown_verdicts(rows: list[list[str]], rule: TableRule) -> list[tuple[int, str]]:
    """The rows whose verdict cell is not in the rule's vocabulary.

    The row's index and what the cell said: a verdict the reader does not
    recognise is a claim about how the row was checked, and it has to name
    the row it belongs to.
    """
    if rule.verdict_column is None or not rule.verdicts:
        return []
    unknown: list[tuple[int, str]] = []
    for index, row in enumerate(rows):
        if rule.verdict_column >= len(row):
            continue
        verdict = row[rule.verdict_column].strip().lower()
        if verdict not in rule.verdicts:
            unknown.append((index, verdict or "(empty)"))
    return unknown


def compare_names(
    declared: list[str] | set[str], documented: list[str] | set[str]
) -> tuple[list[str], list[str]]:
    """The names the code declares and the table does not, and the reverse.

    Both directions, because the table can be wrong in two ways: it can name
    a thing the code does not have, and the code can have a thing the table
    never mentions.
    """
    declared_set = set(declared)
    documented_set = set(documented)
    return (
        sorted(declared_set - documented_set),
        sorted(documented_set - declared_set),
    )
