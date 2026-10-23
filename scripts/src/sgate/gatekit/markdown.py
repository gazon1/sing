"""sgate.gates.markdown — reading the tables a document claims things in.

Several gates compare a markdown table against the code: the deep-link table
of `docs/navigation.md`, the ports table of `docs/PLATFORM-REFERENCE.md`, the
transformation table of the text-field skill. Each of them used to parse
markdown on its own, and each parsed it slightly differently — so a table
that one gate understood was a table another gate could not read.

This module is the one reader. It answers three questions:

  * which lines of a document are rows of a table,
  * what the cells of a row say,
  * which type names a cell names.

It is deliberately small and deliberately literal: a markdown table is a
sequence of lines that start with a pipe, and a cell is what stands between two
pipes. It does not parse inline markup, because a cell that says
`` `AgendaGraph(Today)` `` names the type `AgendaGraph` and the argument
`Today`, and the caller decides which of those it cares about.
"""
from __future__ import annotations

import re
from pathlib import Path

#: A table row: `| a | b | c |`. The cells are captured; the pipes are not.
ROW = re.compile(r"^\|(?P<cells>.*)\|\s*$")

#: A type name: a capitalised identifier. `AgendaGraph(Today)` names
#: `AgendaGraph` and `Today`.
TYPE_NAME = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")


def cells_of(line: str) -> list[str] | None:
    """The cells of a table row, or None when the line is not a row.

    A cell keeps its inner whitespace and loses the padding around it: a cell
    that says `` `AgendaGraph` `` says `AgendaGraph`.
    """
    match = ROW.match(line.strip())
    if not match:
        return None
    return [cell.strip() for cell in match.group("cells").split("|")]


def is_separator(line: str) -> bool:
    """Whether a line is the `|---|---|` row that separates a head from a body."""
    cells = cells_of(line)
    if not cells:
        return False
    return all(re.fullmatch(r":?-{2,}:?", cell) for cell in cells)


def table_rows(text: str) -> list[list[str]]:
    """The body rows of every table in the text.

    Two rules, and they are the same in both directions: the separator row is
    not a row, and the row directly above a separator is the *head* — it names
    the columns rather than carrying data.

    A table is not required to have a head. A document that writes a row
    without a header above it still writes a row, and a reader that skipped it
    would silently see a table with a hole in it.
    """
    lines = text.splitlines()
    rows: list[list[str]] = []
    for index, line in enumerate(lines):
        cells = cells_of(line)
        if cells is None or is_separator(line):
            continue
        following = lines[index + 1] if index + 1 < len(lines) else ""
        if is_separator(following):
            continue  # the head row
        rows.append(cells)
    return rows


def rows_matching(text: str, pattern: re.Pattern[str]) -> list[list[str]]:
    """The body rows whose first cell matches the pattern."""
    matched: list[list[str]] = []
    for row in table_rows(text):
        if not row:
            continue
        if pattern.match(row[0]):
            matched.append(row)
    return matched


def type_names(cell: str) -> list[str]:
    """The type names a cell names, in the order they appear."""
    return TYPE_NAME.findall(cell)


def code_spans(cell: str) -> list[str]:
    """The contents of the inline code spans of a cell.

    A cell that says `` `AgendaGraph(SavedAgendaResults(viewId))` `` holds one
    span; a cell that says `` `a` — `b` `` holds two.
    """
    return re.findall(r"`([^`]+)`", cell)


def read(path: Path) -> str:
    """The text of a document, or an empty string when there is none.

    A missing document is not an error here: a gate that compares a document
    against the code has nothing to say when the document is absent, and the
    gate that *requires* the document says so in its own terms.
    """
    return path.read_text(encoding="utf-8") if path.exists() else ""
