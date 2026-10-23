"""sgate.gatekit.room — the tables the Kotlin entities declare.

Room writes the exported schema at build time: KSP reads the `@Entity`
classes, works out the tables and columns, and writes `NN.json`. The export is
therefore the *result* of a build, and a build is what the repository wants to
avoid when a question can be answered from the sources.

The question this module answers is: what do the entity classes say? Not what
Room would make of them — that is Room's business, and re-implementing its
naming rules would produce a second, disagreeing answer. What the classes say
is the table name they declare and the column names they spell out.

Two rules of Room's naming are read here, and only because the sources cannot
be read without them:

  * a column is named by `@ColumnInfo("name")`, by `@ColumnInfo(name = "name")`,
    or — when neither says — by the property's own name;
  * an `@Embedded` property is not a column: Room flattens the embedded class's
    properties into the parent's table.

Everything else — the column's type, its affinity, its default, whether it is
part of the primary key — is left to Room. A gate that guessed at those would
report a difference where there is none, and a gate that reports differences
where there are none gets ignored.

ADR `docs/decisions/2026-10-22-the-gate-suite-runs-this-checkout.md`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

#: A block comment, replaced by as many newlines as it had, so the line numbers
#: of the text around it do not move.
_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)

#: A full-line `//` comment.
_LINE_COMMENT_RE = re.compile(r"^\s*//.*$", re.MULTILINE)

#: `tableName = "tasks"` inside an `@Entity` annotation.
_TABLE_NAME_RE = re.compile(r'tableName\s*=\s*"([^"]+)"')

#: `@ColumnInfo("name")` or `@ColumnInfo(name = "name")`.
_COLUMN_NAME_RE = re.compile(r'@ColumnInfo\s*\(\s*(?:name\s*=\s*)?"([^"]+)"')

#: A constructor property: `val name: Type`, `var name: Type`.
_PROPERTY_RE = re.compile(
    r"\b(?:val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?P<type>[^=,\n]+)"
)


@dataclass(frozen=True)
class RoomEntity:
    """One table, as the entity class declares it."""

    table: str
    columns: tuple[str, ...]
    class_name: str
    path: Path
    embedded: tuple[str, ...] = field(default=())

    def column_set(self) -> set[str]:
        return set(self.columns)


def strip_comments(text: str) -> str:
    """The text with its comments removed, keeping the line numbering intact."""
    without_block = _BLOCK_COMMENT_RE.sub(
        lambda m: "\n" * m.group(0).count("\n"), text
    )
    return _LINE_COMMENT_RE.sub("", without_block)


def _balanced(text: str, start: int) -> tuple[str, int]:
    """The text inside the parentheses at `start`, and the index after them."""
    depth = 0
    for index in range(start, len(text)):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:index], index + 1
    return "", len(text)


def _split_top_level(body: str) -> list[str]:
    """The comma-separated parts of a parameter list, ignoring nested commas."""
    parts: list[str] = []
    current: list[str] = []
    depth = 0
    for char in body:
        if char in "([<{":
            depth += 1
        elif char in ")]>}":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if "".join(current).strip():
        parts.append("".join(current))
    return parts


def _skip_annotations(text: str, index: int) -> int:
    """The index after the annotations that start at `index`."""
    while True:
        match = re.compile(r"\s*@\w+").match(text, index)
        if not match:
            return index
        index = match.end()
        if index < len(text) and text[index] == "(":
            _, index = _balanced(text, index)


def _entity_declarations(text: str) -> list[tuple[str, str, str]]:
    """`(table, class name, parameter list)` for each `@Entity` in the text.

    The table is the one the annotation declares; a class that does not
    declare one has no table of its own name in the schema — Room would use
    the class name — and this module reports the class name in its place so a
    difference against the export is visible rather than silent.
    """
    found: list[tuple[str, str, str]] = []
    for match in re.finditer(r"@Entity\b", text):
        arguments, after_annotation = "", match.end()
        probe = after_annotation
        while probe < len(text) and text[probe] in " \t\r\n":
            probe += 1
        if probe < len(text) and text[probe] == "(":
            arguments, after_annotation = _balanced(text, probe)
        table_match = _TABLE_NAME_RE.search(arguments)
        index = _skip_annotations(text, after_annotation)
        class_match = re.compile(
            r"\s*(?:public\s+|internal\s+|private\s+|data\s+|abstract\s+|open\s+)*"
            r"class\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
            r"(?P<brace>[\(\{])?"
        ).match(text, index)
        if not class_match:
            continue
        name = class_match.group("name")
        parameters = ""
        if class_match.group("brace") == "(":
            parameters, _ = _balanced(text, class_match.end() - 1)
        table = table_match.group(1) if table_match else name
        found.append((table, name, parameters))
    return found


def _properties(parameters: str) -> list[tuple[str, str, str | None, bool]]:
    """`(property, annotations, embedded type, ignored)` for each constructor property."""
    out: list[tuple[str, str, str | None, bool]] = []
    for raw in _split_top_level(parameters):
        part = raw.strip()
        if not part:
            continue
        property_match = _PROPERTY_RE.search(part)
        if not property_match:
            continue
        annotations = " ".join(re.findall(r"@\w+(?:\([^)]*\))?", part))
        embedded = None
        if "@Embedded" in annotations:
            embedded = property_match.group("type").strip()
        out.append(
            (
                property_match.group("name"),
                annotations,
                embedded,
                "@Ignore" in annotations,
            )
        )
    return out


def _columns_of(
    parameters: str,
    classes: dict[str, str],
    seen: tuple[str, ...] = (),
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """The columns the parameters declare, and the embedded classes they name."""
    columns: list[str] = []
    embedded: list[str] = []
    for name, annotations, embedded_type, ignored in _properties(parameters):
        if ignored:
            continue
        if embedded_type is not None:
            if embedded_type in seen:
                continue
            embedded.append(embedded_type)
            nested = classes.get(embedded_type)
            if nested is None:
                continue
            inner, inner_embedded = _columns_of(
                nested, classes, seen + (embedded_type,)
            )
            columns.extend(inner)
            embedded.extend(inner_embedded)
            continue
        column_match = _COLUMN_NAME_RE.search(annotations)
        columns.append(column_match.group(1) if column_match else name)
    return tuple(columns), tuple(embedded)


def entities(
    root: Path,
    source_set: str = "commonMain",
) -> dict[str, RoomEntity]:
    """The tables the entity classes declare, by table name.

    `root` is the source root of a module — `shared/src` — and `source_set`
    names the source set inside it. A table declared twice (two classes, one
    table) is a finding, not a merge: the caller compares the sets and reports
    the difference, because two classes claiming one table is exactly the kind
    of thing that must not be resolved silently.
    """
    sources = {
        path: strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for path in sorted(root.rglob("*.kt"))
        if source_set in path.parts
    }
    classes: dict[str, str] = {}
    for text in sources.values():
        for match in re.finditer(r"(?:data\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", text):
            parameters, _ = _balanced(text, match.end() - 1)
            classes.setdefault(match.group(1), parameters)

    found: dict[str, RoomEntity] = {}
    for path, text in sources.items():
        for table, class_name, parameters in _entity_declarations(text):
            columns, embedded = _columns_of(parameters, classes)
            found[table] = RoomEntity(
                table=table,
                columns=columns,
                class_name=class_name,
                path=path,
                embedded=embedded,
            )
    return found
