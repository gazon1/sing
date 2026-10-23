"""sgate.gates.rules — a document-against-the-code rule that is data.

The gates of this family all ask the same question in different clothes: a
table in a document names things, the code declares things, and the two must
agree. Every one of them wrote its own reader for the table, its own reader
for the sources, and its own way of saying "this row is about that
declaration".

A rule that says *which* table and *which* naming convention applies is
data. The part that compares them is code, and it is written once here.

The rule this module carries is the deep-link table: a row names a link, the
link is declared by an `object …LinkSpec`, and the link is implemented by a
`class …DeepLink` — the naming convention the deep-link registry uses. The
types a row names must appear in the matcher that implements it, and every
spec the code declares must have a row.

Usage:
    rule = LinkTableRule.from_file(Path("config/gates/navigation-doc-sync.yaml"))
    findings = check_link_table(root, rule)
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import re

import yaml

from sgate.gatekit.findings import BLOCKING, Finding
from sgate.gatekit.markdown import code_spans, rows_matching, type_names
from sgate.gatekit.kotlin import link_matchers, link_specs


@dataclass(frozen=True)
class LinkTableRule:
    """A deep-link table, and the naming convention that ties it to the code."""

    #: The document, repo-relative.
    doc: str
    #: The source set the declarations live in, repo-relative.
    sources: str
    #: The column of each part of a row, by name.
    columns: dict[str, int] = field(default_factory=dict)
    #: The suffix of the object that declares a link.
    spec_suffix: str = "LinkSpec"
    #: The suffix of the class that matches a link.
    matcher_suffix: str = "DeepLink"
    #: The columns whose type names must appear in the matcher.
    type_columns: tuple[str, ...] = ("tab", "screen")

    @classmethod
    def from_file(cls, path: Path) -> LinkTableRule:
        """Read a rule from a YAML file."""
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        return cls(
            doc=data["doc"],
            sources=data["sources"],
            columns=dict(data.get("columns", {})),
            spec_suffix=data.get("spec_suffix", "LinkSpec"),
            matcher_suffix=data.get("matcher_suffix", "DeepLink"),
            type_columns=tuple(data.get("type_columns", ("tab", "screen"))),
        )


def _link_rows(text: str, rule: LinkTableRule) -> list[dict[str, str]]:
    """The rows of the table the rule describes."""
    rows: list[dict[str, str]] = []
    for cells in rows_matching(text, re.compile(r"`[^`]*://[^`]+`")):
        if len(cells) <= max(rule.columns.values()):
            continue
        row = {name: cells[index] for name, index in rule.columns.items()}
        spans = code_spans(row.get("link", ""))
        row["link"] = spans[0] if spans else row.get("link", "")
        rows.append(row)
    return rows


def check_link_table(root: Path, rule: LinkTableRule) -> list[Finding]:
    """Compare the table of the rule with the code it names.

    Two directions, because the table can be wrong in two ways: it can name a
    type the matcher does not have, and the code can declare a link the table
    never mentions.
    """
    findings: list[Finding] = []
    doc_path = root / rule.doc
    doc_relative = Path(rule.doc).as_posix()
    text = doc_path.read_text(encoding="utf-8") if doc_path.exists() else ""

    matchers = link_matchers(root / rule.sources)
    specs = link_specs(root / rule.sources)
    documented: set[str] = set()

    for row in _link_rows(text, rule):
        owner = row.get("owner", "")
        spec = None
        for span in code_spans(owner):
            if span.endswith(rule.spec_suffix):
                spec = span
                break
        if spec is None:
            findings.append(
                Finding(BLOCKING, f"{doc_relative}: the row for `{row.get('link', '')}` names no link spec")
            )
            continue
        documented.add(spec)

        matcher_name = spec[: -len(rule.spec_suffix)] + rule.matcher_suffix
        matcher_path = matchers.get(matcher_name)
        if matcher_path is None:
            findings.append(
                Finding(
                    BLOCKING,
                    f"{doc_relative}: the row for `{row.get('link', '')}` names "
                    f"`{spec}`, but no matcher `{matcher_name}` exists in the sources",
                )
            )
            continue

        source = matcher_path.read_text(encoding="utf-8")
        matcher_relative = matcher_path.relative_to(root).as_posix()
        for column in rule.type_columns:
            for name in type_names(row.get(column, "")):
                if name not in source:
                    findings.append(
                        Finding(
                            BLOCKING,
                            f"{doc_relative}: the row for `{row.get('link', '')}` says the "
                            f"{column} is `{name}`, but `{matcher_relative}` never names it",
                        )
                    )

    for spec in sorted(specs - documented):
        findings.append(
            Finding(
                BLOCKING,
                f"{doc_relative}: the code declares `{spec}`, and no deep-link row "
                f"in the doc names it",
            )
        )

    return findings
